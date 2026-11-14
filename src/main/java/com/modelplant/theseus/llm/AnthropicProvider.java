package com.modelplant.theseus.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ChatRequest;
import com.modelplant.theseus.api.llm.ChatResponse;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.api.llm.TokenUsage;
import com.modelplant.theseus.api.llm.ToolCall;
import com.modelplant.theseus.api.tool.ToolSchema;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.runtime.TheseusExecutors;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AnthropicProvider implements LlmProvider {
    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public String displayName() {
        return "Anthropic Claude";
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public boolean supportsVision() {
        return true;
    }

    @Override
    public CompletableFuture<ChatResponse> chat(ChatRequest request, String apiKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doChat(request, apiKey);
            } catch (Exception e) {
                throw new RuntimeException("Anthropic request failed: " + e.getMessage(), e);
            }
        }, TheseusExecutors.workers());
    }

    private ChatResponse doChat(ChatRequest request, String apiKey) throws Exception {
        String base = request.effectiveBaseUrl(ProviderEndpoints.defaultUrl("anthropic"));
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String system = null;
        JsonArray messages = new JsonArray();
        for (ChatMessage message : request.messages()) {
            if ("system".equals(message.role())) {
                system = message.content();
                continue;
            }
            if ("tool".equals(message.role())) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", "user");
                JsonArray content = new JsonArray();
                JsonObject toolResult = new JsonObject();
                toolResult.addProperty("type", "tool_result");
                toolResult.addProperty("tool_use_id", message.toolCallId());
                toolResult.addProperty("content", message.content());
                content.add(toolResult);
                msg.add("content", content);
                messages.add(msg);
                continue;
            }
            if ("assistant".equals(message.role()) && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", "assistant");
                JsonArray content = new JsonArray();
                if (message.content() != null && !message.content().isBlank()) {
                    JsonObject text = new JsonObject();
                    text.addProperty("type", "text");
                    text.addProperty("text", message.content());
                    content.add(text);
                }
                for (ToolCall call : message.toolCalls()) {
                    JsonObject use = new JsonObject();
                    use.addProperty("type", "tool_use");
                    use.addProperty("id", call.id());
                    use.addProperty("name", call.name());
                    use.add("input", call.arguments());
                    content.add(use);
                }
                msg.add("content", content);
                messages.add(msg);
                continue;
            }
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "assistant".equals(message.role()) ? "assistant" : "user");
            if (message.hasImages()) {
                JsonArray content = new JsonArray();
                if (message.content() != null && !message.content().isBlank()) {
                    JsonObject text = new JsonObject();
                    text.addProperty("type", "text");
                    text.addProperty("text", message.content());
                    content.add(text);
                }
                for (var image : message.images()) {
                    JsonObject imageBlock = new JsonObject();
                    imageBlock.addProperty("type", "image");
                    JsonObject source = new JsonObject();
                    source.addProperty("type", "base64");
                    source.addProperty("media_type", image.mimeType());
                    source.addProperty("data", image.base64());
                    imageBlock.add("source", source);
                    content.add(imageBlock);
                }
                msg.add("content", content);
            } else {
                msg.addProperty("content", message.content() == null ? "" : message.content());
            }
            messages.add(msg);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("max_tokens", request.maxTokens());
        if (system != null) {
            body.addProperty("system", system);
        }
        body.add("messages", messages);

        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSchema schema : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", schema.name());
                tool.addProperty("description", schema.description());
                tool.add("input_schema", schema.parameters());
                tools.add(tool);
            }
            body.add("tools", tools);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/messages"))
                .timeout(Duration.ofSeconds(TheseusServerConfig.REQUEST_TIMEOUT_SECONDS.getAsInt()))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = HttpClients.get().send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return parse(response.body());
    }

    static ChatResponse parse(String raw) {
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        JsonArray content = root.getAsJsonArray("content");
        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (content != null) {
            for (JsonElement el : content) {
                JsonObject block = el.getAsJsonObject();
                String type = block.get("type").getAsString();
                if ("text".equals(type)) {
                    text.append(block.get("text").getAsString());
                } else if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                    if (block.has("thinking") && !block.get("thinking").isJsonNull()) {
                        reasoning.append(block.get("thinking").getAsString());
                    } else if (block.has("data") && !block.get("data").isJsonNull()) {
                        reasoning.append(block.get("data").getAsString());
                    }
                } else if ("tool_use".equals(type)) {
                    String id = block.has("id") ? block.get("id").getAsString() : UUID.randomUUID().toString();
                    toolCalls.add(new ToolCall(id, block.get("name").getAsString(), block.get("input").toString()));
                }
            }
        }
        String stop = root.has("stop_reason") ? root.get("stop_reason").getAsString() : "end_turn";
        return new ChatResponse(text.toString(), toolCalls, stop, raw, reasoning.toString(), parseUsage(root));
    }

    static TokenUsage parseUsage(JsonObject root) {
        if (root == null || !root.has("usage") || !root.get("usage").isJsonObject()) {
            return TokenUsage.UNKNOWN;
        }
        JsonObject usage = root.getAsJsonObject("usage");
        int input = usage.has("input_tokens") ? usage.get("input_tokens").getAsInt() : -1;
        int output = usage.has("output_tokens") ? usage.get("output_tokens").getAsInt() : -1;
        int total = (input >= 0 && output >= 0) ? input + output : -1;
        return new TokenUsage(input, output, total);
    }
}
