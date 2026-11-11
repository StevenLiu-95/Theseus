package com.modelplant.theseus.session;

import com.google.gson.JsonObject;
import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.api.gate.GateContext;
import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ChatRequest;
import com.modelplant.theseus.api.llm.ChatResponse;
import com.modelplant.theseus.api.llm.ImageData;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.api.llm.ToolCall;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.api.tool.ToolSchema;
import com.modelplant.theseus.audit.AuditLog;
import com.modelplant.theseus.config.ApiKeyStore;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.gate.ToolGateEngine;
import com.modelplant.theseus.llm.LlmProviderRegistry;
import com.modelplant.theseus.permission.TheseusPermissions;
import com.modelplant.theseus.runtime.TheseusExecutors;
import com.modelplant.theseus.tool.ToolExecutor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatOrchestrator {
    private ChatOrchestrator() {
    }

    public static CompletableFuture<Void> handlePlayerChat(ServerPlayer player, String userText) {
        AiSession session = AiSessionManager.get().getOrCreate(player);
        if (!session.tryAcquire()) {
            player.sendSystemMessage(Component.literal("[Theseus] Session busy, wait for the current reply."));
            return CompletableFuture.completedFuture(null);
        }

        player.sendSystemMessage(Component.literal("[Theseus] Thinking..."));

        return CompletableFuture.runAsync(() -> {
            try {
                runLoop(player, session, userText);
            } catch (Exception e) {
                Theseus.LOGGER.error("Chat orchestration failed", e);
                player.getServer().execute(() ->
                        player.sendSystemMessage(Component.literal("[Theseus] Error: " + e.getMessage())));
            } finally {
                session.release();
            }
        }, TheseusExecutors.workers());
    }

    private static void runLoop(ServerPlayer player, AiSession session, String userText) throws Exception {
        session.add(ChatMessage.user(userText));

        String providerId = TheseusServerConfig.PROVIDER_ID.get();
        LlmProvider provider = LlmProviderRegistry.get().require(providerId);
        String apiKey = ApiKeyStore.get(providerId);
        if ("ollama".equals(providerId) && (apiKey == null || apiKey.isBlank())) {
            apiKey = "ollama";
        }
        if (!"mock".equals(providerId) && !"ollama".equals(providerId) && (apiKey == null || apiKey.isBlank())) {
            tell(player, "No API key for provider '" + providerId + "'. Use /theseus config key <provider> <key>");
            return;
        }

        int maxIter = TheseusServerConfig.MAX_TOOL_ITERATIONS.getAsInt();
        for (int i = 0; i < maxIter; i++) {
            GateContext gateContext = buildGateContext(player, session, provider.supportsVision());
            List<Tool> tools = ToolGateEngine.get().filter(gateContext);
            List<ToolSchema> schemas = tools.stream().map(Tool::schema).toList();

            List<ChatMessage> messages = session.mutableHistoryCopy();
            if (provider.supportsVision()) {
                List<ImageData> vision = session.drainVision();
                if (!vision.isEmpty()) {
                    messages.add(ChatMessage.userWithImages(
                            "Attached screenshot from take_screenshot for visual context.",
                            vision
                    ));
                }
            }

            ChatRequest request = new ChatRequest(
                    TheseusServerConfig.MODEL.get(),
                    messages,
                    schemas,
                    TheseusServerConfig.LLM_TEMPERATURE.get(),
                    TheseusServerConfig.MAX_TOKENS.getAsInt(),
                    provider.supportsVision()
            );

            StreamBuffer streamBuffer = new StreamBuffer(player);
            ChatResponse response = provider.chat(request, apiKey, streamBuffer::onDelta).join();
            streamBuffer.flushFinal();

            if (response.hasToolCalls()) {
                session.add(ChatMessage.assistantToolCalls(response.toolCalls()));
                for (ToolCall call : response.toolCalls()) {
                    ToolResult result = ToolExecutor.execute(player, session, call, gateContext).join();
                    AuditLog.record(player, call.name(), call.argumentsJson(), result);
                    session.add(ChatMessage.tool(call.id(), call.name(), slimToolText(result)));
                    maybeEnqueueVision(session, provider, result);
                }
                continue;
            }

            String content = response.content() == null ? "" : response.content();
            session.add(ChatMessage.assistant(content));
            if (!streamBuffer.emittedAny()) {
                tell(player, content.isBlank() ? "(empty response)" : content);
            }
            return;
        }

        tell(player, "Stopped after max tool iterations.");
    }

    private static void maybeEnqueueVision(AiSession session, LlmProvider provider, ToolResult result) {
        if (!provider.supportsVision() || !(result.data() instanceof JsonObject data)) {
            return;
        }
        if (!data.has("base64_png")) {
            return;
        }
        String b64 = data.get("base64_png").getAsString();
        if (b64 == null || b64.isBlank()) {
            return;
        }
        String mime = data.has("mime") ? data.get("mime").getAsString() : "image/png";
        session.enqueueVision(new ImageData(mime, b64));
    }

    private static String slimToolText(ToolResult result) {
        if (!(result.data() instanceof JsonObject data) || !data.has("base64_png")) {
            return result.toText();
        }
        JsonObject slim = data.deepCopy();
        slim.addProperty("base64_png", "[omitted; attached as vision image for next model turn]");
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("success", result.success());
        wrapper.addProperty("message", result.message() == null ? "" : result.message());
        wrapper.add("data", slim);
        return wrapper.toString();
    }

    private static GateContext buildGateContext(ServerPlayer player, AiSession session, boolean vision) {
        GameType gameType = player.gameMode.getGameModeForPlayer();
        return new GateContext(
                player,
                session.id(),
                TheseusServerConfig.enabledCapabilities(),
                TheseusPermissions.isOperator(player),
                vision,
                player.level().dimension(),
                gameType
        );
    }

    private static void tell(ServerPlayer player, String message) {
        if (player.getServer() == null) {
            return;
        }
        player.getServer().execute(() -> player.sendSystemMessage(Component.literal("[Theseus] " + message)));
    }

    private static final class StreamBuffer {
        private final ServerPlayer player;
        private final StringBuilder pending = new StringBuilder();
        private final AtomicLong lastFlushMs = new AtomicLong(0);
        private boolean emitted;

        private StreamBuffer(ServerPlayer player) {
            this.player = player;
        }

        private void onDelta(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            synchronized (pending) {
                pending.append(text);
                long now = System.currentTimeMillis();
                if (pending.length() >= 48 || now - lastFlushMs.get() >= 250) {
                    flushLocked();
                }
            }
        }

        private void flushFinal() {
            synchronized (pending) {
                flushLocked();
            }
        }

        private void flushLocked() {
            if (pending.isEmpty()) {
                return;
            }
            String chunk = pending.toString();
            pending.setLength(0);
            lastFlushMs.set(System.currentTimeMillis());
            emitted = true;
            tell(player, chunk);
        }

        private boolean emittedAny() {
            return emitted;
        }
    }
}
