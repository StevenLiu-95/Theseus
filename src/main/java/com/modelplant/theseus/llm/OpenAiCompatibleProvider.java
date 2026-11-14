package com.modelplant.theseus.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ChatRequest;
import com.modelplant.theseus.api.llm.ChatResponse;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.api.llm.StreamDeltaConsumer;
import com.modelplant.theseus.api.llm.TokenUsage;
import com.modelplant.theseus.api.llm.ToolCall;
import com.modelplant.theseus.api.tool.ToolSchema;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.runtime.TheseusExecutors;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * OpenAI Chat Completions compatible provider (GPT, DeepSeek, Qwen-compatible gateways, Ollama, etc.).
 */
public final class OpenAiCompatibleProvider implements LlmProvider {
    private final String id;
    private final String displayName;
    private final boolean vision;
    private final Supplier<String> baseUrlSupplier;

    public OpenAiCompatibleProvider() {
        this("openai", "OpenAI Compatible", true);
    }

    public OpenAiCompatibleProvider(String id, String displayName, boolean vision) {
        this(id, displayName, vision, () -> ProviderEndpoints.openAiCompatibleBase(id, null));
    }

    public OpenAiCompatibleProvider(String id, String displayName, boolean vision, Supplier<String> baseUrlSupplier) {
        this.id = id;
        this.displayName = displayName;
        this.vision = vision;
        this.baseUrlSupplier = baseUrlSupplier != null
                ? baseUrlSupplier
                : () -> ProviderEndpoints.openAiCompatibleBase(id, null);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public boolean supportsVision() {
        return vision;
    }

    @Override
    public CompletableFuture<ChatResponse> chat(ChatRequest request, String apiKey) {
        return chat(request, apiKey, null);
    }

    @Override
    public CompletableFuture<ChatResponse> chat(ChatRequest request, String apiKey, StreamDeltaConsumer onDelta) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Tool calls are unreliable over stream for many gateways — use non-stream when tools present.
                if (onDelta != null && request.tools().isEmpty()) {
                    return doChatStreaming(request, apiKey, onDelta);
                }
                return doChat(request, apiKey);
            } catch (Exception e) {
                throw new RuntimeException("OpenAI-compatible request failed: " + e.getMessage(), e);
            }
        }, TheseusExecutors.workers());
    }

    private JsonObject buildBody(ChatRequest request, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("temperature", request.temperature());
        body.addProperty("max_tokens", request.maxTokens());
        body.addProperty("stream", stream);

        JsonArray messages = new JsonArray();
        for (ChatMessage message : request.messages()) {
            messages.add(message.toOpenAiJson());
        }
        body.add("messages", messages);

        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSchema schema : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", schema.toOpenAiFunction());
                tools.add(tool);
            }
            body.add("tools", tools);
            body.addProperty("tool_choice", "auto");
        }
        return body;
    }

    private String baseUrl(ChatRequest request) {
        String fallback = baseUrlSupplier.get();
        String base = request.effectiveBaseUrl(fallback);
        if (base == null) {
            base = "";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private ChatResponse doChat(ChatRequest request, String apiKey) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl(request) + "/chat/completions"))
                .timeout(Duration.ofSeconds(TheseusServerConfig.REQUEST_TIMEOUT_SECONDS.getAsInt()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(request, false).toString()))
                .build();

        HttpResponse<String> response = HttpClients.get().send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        return parse(response.body());
    }

    private ChatResponse doChatStreaming(ChatRequest request, String apiKey, StreamDeltaConsumer onDelta) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl(request) + "/chat/completions"))
                .timeout(Duration.ofSeconds(TheseusServerConfig.REQUEST_TIMEOUT_SECONDS.getAsInt()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(request, true).toString()))
                .build();

        HttpResponse<InputStream> response = HttpClients.get().send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + truncate(err));
        }

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        TokenUsage usage = TokenUsage.UNKNOWN;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                try {
                    JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                    TokenUsage chunkUsage = parseUsage(chunk);
                    if (chunkUsage.known()) {
                        usage = chunkUsage;
                    }
                    JsonArray choices = chunk.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) {
                        continue;
                    }
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                            ? choice.getAsJsonObject("delta") : null;
                    if (delta != null) {
                        String thinkPiece = extractReasoning(delta);
                        if (thinkPiece != null && !thinkPiece.isEmpty()) {
                            reasoning.append(thinkPiece);
                        }
                        if (delta.has("content") && !delta.get("content").isJsonNull()) {
                            String piece = delta.get("content").getAsString();
                            content.append(piece);
                            onDelta.onDelta(piece);
                        }
                    }
                } catch (Exception ignored) {
                    // skip malformed SSE chunks
                }
            }
        }
        return new ChatResponse(content.toString(), List.of(), "stop", content.toString(),
                reasoning.toString(), usage);
    }

    static ChatResponse parse(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return ChatResponse.text("");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        String content = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString()
                : null;
        List<ToolCall> toolCalls = new ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement el : message.getAsJsonArray("tool_calls")) {
                JsonObject tc = el.getAsJsonObject();
                String id = tc.get("id").getAsString();
                JsonObject fn = tc.getAsJsonObject("function");
                toolCalls.add(new ToolCall(id, fn.get("name").getAsString(), fn.get("arguments").getAsString()));
            }
        }
        String finish = choices.get(0).getAsJsonObject().has("finish_reason")
                ? choices.get(0).getAsJsonObject().get("finish_reason").getAsString()
                : "stop";
        return new ChatResponse(content, toolCalls, finish, raw, extractReasoning(message), parseUsage(root));
    }

    static TokenUsage parseUsage(JsonObject root) {
        if (root == null || !root.has("usage") || !root.get("usage").isJsonObject()) {
            return TokenUsage.UNKNOWN;
        }
        JsonObject usage = root.getAsJsonObject("usage");
        return new TokenUsage(
                intOrNeg(usage, "prompt_tokens", "input_tokens"),
                intOrNeg(usage, "completion_tokens", "output_tokens"),
                intOrNeg(usage, "total_tokens")
        );
    }

    static String extractReasoning(JsonObject messageOrDelta) {
        if (messageOrDelta == null) {
            return "";
        }
        for (String key : List.of("reasoning_content", "reasoning", "thinking")) {
            if (messageOrDelta.has(key) && !messageOrDelta.get(key).isJsonNull()) {
                return messageOrDelta.get(key).getAsString();
            }
        }
        return "";
    }

    private static int intOrNeg(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                try {
                    return obj.get(key).getAsInt();
                } catch (Exception ignored) {
                    // keep looking
                }
            }
        }
        return -1;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
