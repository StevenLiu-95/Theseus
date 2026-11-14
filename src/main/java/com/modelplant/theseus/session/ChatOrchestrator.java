package com.modelplant.theseus.session;

import com.google.gson.JsonObject;
import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.api.gate.GateContext;
import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ChatRequest;
import com.modelplant.theseus.api.llm.ChatResponse;
import com.modelplant.theseus.api.llm.ImageData;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.api.llm.TokenUsage;
import com.modelplant.theseus.api.llm.ToolCall;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.api.tool.ToolSchema;
import com.modelplant.theseus.audit.AuditLog;
import com.modelplant.theseus.config.PlayerConfigStore;
import com.modelplant.theseus.config.PlayerLlmSettings;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.gate.ToolGateEngine;
import com.modelplant.theseus.llm.LlmProviderRegistry;
import com.modelplant.theseus.llm.ProviderEndpoints;
import com.modelplant.theseus.permission.TheseusPermissions;
import com.modelplant.theseus.runtime.TheseusExecutors;
import com.modelplant.theseus.tool.ToolExecutor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class ChatOrchestrator {
    private static final int VERBOSE_CHUNK = 180;
    private static final long CANCEL_POLL_MS = 200L;

    private ChatOrchestrator() {
    }

    public static CompletableFuture<Void> handlePlayerChat(ServerPlayer player, String userText) {
        AiSession session = AiSessionManager.get().getOrCreate(player);
        if (!session.tryAcquire()) {
            player.sendSystemMessage(Component.literal("[Theseus] Session busy, wait for the current reply. Use /theseus cancel to abort."));
            return CompletableFuture.completedFuture(null);
        }

        player.sendSystemMessage(Component.literal("[You] " + userText));
        ThinkingIndicator.begin(player);

        return CompletableFuture.runAsync(() -> {
            try {
                runLoop(player, session, userText);
            } catch (CancellationException e) {
                tell(player, "Cancelled.");
            } catch (Exception e) {
                Theseus.LOGGER.error("Chat orchestration failed", e);
                player.getServer().execute(() ->
                        player.sendSystemMessage(Component.literal("[Theseus] Error: " + e.getMessage())));
            } finally {
                ThinkingIndicator.end(player);
                session.release();
            }
        }, TheseusExecutors.workers());
    }

    /**
     * Abort the player's in-flight chat turn, if any.
     *
     * @return {@code true} if a busy turn was asked to stop
     */
    public static boolean cancelPlayerChat(ServerPlayer player) {
        AiSession session = AiSessionManager.get().getOrCreate(player);
        return session.requestCancel();
    }

    private static void runLoop(ServerPlayer player, AiSession session, String userText) throws Exception {
        session.add(ChatMessage.user(userText));

        PlayerConfigStore.maybeMigrateLegacyKeys(player.getUUID());
        PlayerLlmSettings prefs = PlayerConfigStore.get(player.getUUID());
        String providerId = prefs.providerId();
        LlmProvider provider = LlmProviderRegistry.get().require(providerId);
        String model = prefs.model();
        String apiKey = prefs.apiKey(providerId);
        if ("ollama".equals(providerId) && (apiKey == null || apiKey.isBlank())) {
            apiKey = "ollama";
        }
        if (!"mock".equals(providerId) && !"ollama".equals(providerId) && (apiKey == null || apiKey.isBlank())) {
            tell(player, "No API key for provider '" + providerId
                    + "'. Use /theseus config api <provider> <model> <key>");
            return;
        }

        String baseUrl = ProviderEndpoints.openAiCompatibleBase(providerId, prefs);
        if ("anthropic".equals(providerId) || "gemini".equals(providerId)) {
            baseUrl = ProviderEndpoints.resolve(providerId, prefs);
        }

        boolean verbose = VerboseChatPrefs.isEnabled(player.getUUID());
        if (verbose) {
            debug(player, "model=" + providerId + "/" + model + " tools=" + countVisibleTools(player, session, provider));
        }

        TokenUsage totalUsage = TokenUsage.UNKNOWN;
        int maxIter = prefs.maxToolIterations();
        for (int i = 0; i < maxIter; i++) {
            throwIfCancelled(session);

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
                    model,
                    messages,
                    schemas,
                    prefs.temperature(),
                    prefs.maxTokens(),
                    provider.supportsVision(),
                    baseUrl
            );

            if (verbose) {
                debug(player, "round=" + (i + 1) + "/" + maxIter + " schemas=" + schemas.size());
            }

            StreamBuffer streamBuffer = new StreamBuffer(player);
            ChatResponse response = awaitCancellable(
                    session,
                    provider.chat(request, apiKey, streamBuffer::onDelta)
            );
            streamBuffer.flushFinal();
            totalUsage = totalUsage.plus(response.usage());

            if (verbose) {
                if (response.hasReasoning()) {
                    debugChunks(player, "think", response.reasoning());
                }
                if (response.content() != null && !response.content().isBlank() && response.hasToolCalls()) {
                    debugChunks(player, "aside", response.content());
                }
                if (response.usage().known()) {
                    debug(player, "usage round=" + (i + 1) + " " + response.usage().format());
                }
            }

            if (response.hasToolCalls()) {
                session.add(ChatMessage.assistantToolCalls(response.toolCalls()));
                List<ToolCall> calls = response.toolCalls();
                for (int t = 0; t < calls.size(); t++) {
                    if (session.isCancelRequested()) {
                        padCancelledTools(session, calls, t);
                        if (verbose) {
                            debug(player, "cancelled mid-tools model=" + providerId + "/" + model
                                    + " tokens=" + totalUsage.format());
                        }
                        throw new CancellationException("cancelled");
                    }
                    ToolCall call = calls.get(t);
                    if (verbose) {
                        debug(player, "tool → " + call.name() + "(" + truncate(call.argumentsJson(), 240) + ")");
                    }
                    ToolResult result;
                    try {
                        result = awaitCancellable(
                                session,
                                ToolExecutor.execute(player, session, call, gateContext)
                        );
                    } catch (CancellationException e) {
                        padCancelledTools(session, calls, t);
                        if (verbose) {
                            debug(player, "cancelled mid-tools model=" + providerId + "/" + model
                                    + " tokens=" + totalUsage.format());
                        }
                        throw e;
                    }
                    AuditLog.record(player, call.name(), call.argumentsJson(), result);
                    if (verbose) {
                        String status = result.success() ? "ok" : "ERR";
                        debug(player, "tool ← " + call.name() + " [" + status + "] "
                                + truncate(result.toText(), 280));
                    }
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
            if (verbose) {
                debug(player, "done model=" + providerId + "/" + model
                        + " rounds=" + (i + 1)
                        + " tokens=" + totalUsage.format());
            }
            return;
        }

        tell(player, "Stopped after max tool iterations.");
        if (verbose) {
            debug(player, "aborted model=" + providerId + "/" + model + " tokens=" + totalUsage.format());
        }
    }

    private static void throwIfCancelled(AiSession session) {
        if (session.isCancelRequested()) {
            throw new CancellationException("cancelled");
        }
    }

    private static <T> T awaitCancellable(AiSession session, CompletableFuture<T> future) throws Exception {
        session.attachInFlight(future);
        try {
            while (true) {
                throwIfCancelled(session);
                try {
                    return future.get(CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // keep polling for cancel
                } catch (CancellationException e) {
                    throw e;
                } catch (CompletionException | ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CancellationException cancellation) {
                        throw cancellation;
                    }
                    if (session.isCancelRequested()) {
                        throw new CancellationException("cancelled");
                    }
                    if (cause instanceof Exception ex) {
                        throw ex;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw e;
                }
            }
        } finally {
            session.clearInFlight(future);
        }
    }

    private static void padCancelledTools(AiSession session, List<ToolCall> calls, int fromIndex) {
        for (int i = fromIndex; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            session.add(ChatMessage.tool(
                    call.id(),
                    call.name(),
                    ToolResult.error("Cancelled by player").toText()
            ));
        }
    }

    private static int countVisibleTools(ServerPlayer player, AiSession session, LlmProvider provider) {
        GateContext gateContext = buildGateContext(player, session, provider.supportsVision());
        return ToolGateEngine.get().filter(gateContext).size();
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

    private static void debug(ServerPlayer player, String message) {
        if (player.getServer() == null) {
            return;
        }
        player.getServer().execute(() ->
                player.sendSystemMessage(Component.literal("[Theseus:debug] " + message)));
    }

    private static void debugChunks(ServerPlayer player, String tag, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        for (int i = 0; i < normalized.length(); i += VERBOSE_CHUNK) {
            int end = Math.min(normalized.length(), i + VERBOSE_CHUNK);
            debug(player, tag + ": " + normalized.substring(i, end));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
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
