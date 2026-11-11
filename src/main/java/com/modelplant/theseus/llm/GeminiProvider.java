package com.modelplant.theseus.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ChatRequest;
import com.modelplant.theseus.api.llm.ChatResponse;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.api.llm.ToolCall;
import com.modelplant.theseus.api.tool.ToolSchema;
import com.modelplant.theseus.config.TheseusServerConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class GeminiProvider implements LlmProvider {
    @Override
    public String id() {
        return "gemini";
    }

    @Override
    public String displayName() {
        return "Google Gemini";
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
                throw new RuntimeException("Gemini request failed: " + e.getMessage(), e);
            }
        });
    }

    private ChatResponse doChat(ChatRequest request, String apiKey) throws Exception {
        String base = TheseusServerConfig.GEMINI_BASE_URL.get();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String model = request.model();
        String url = base + "/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        JsonObject body = new JsonObject();
        JsonArray contents = new JsonArray();
        String system = null;
        for (ChatMessage message : request.messages()) {
            if ("system".equals(message.role())) {
                system = message.content();
                continue;
            }
            JsonObject content = new JsonObject();
            content.addProperty("role", "assistant".equals(message.role()) ? "model" : "user");
            JsonArray parts = new JsonArray();
            if ("tool".equals(message.role())) {
                JsonObject fr = new JsonObject();
                JsonObject resp = new JsonObject();
                resp.addProperty("name", message.name() == null ? "tool" : message.name());
                JsonObject response = new JsonObject();
                response.addProperty("result", message.content());
                resp.add("response", response);
                fr.add("functionResponse", resp);
                parts.add(fr);
            } else if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                for (ToolCall call : message.toolCalls()) {
                    JsonObject fc = new JsonObject();
                    JsonObject callObj = new JsonObject();
                    callObj.addProperty("name", call.name());
                    callObj.add("args", call.arguments());
                    fc.add("functionCall", callObj);
                    parts.add(fc);
                }
            } else {
                if (message.content() != null && !message.content().isBlank()) {
                    JsonObject text = new JsonObject();
                    text.addProperty("text", message.content());
                    parts.add(text);
                }
                if (message.hasImages()) {
                    for (var image : message.images()) {
                        JsonObject inline = new JsonObject();
                        JsonObject data = new JsonObject();
                        data.addProperty("mimeType", image.mimeType());
                        data.addProperty("data", image.base64());
                        inline.add("inlineData", data);
                        parts.add(inline);
                    }
                }
                if (parts.isEmpty()) {
                    JsonObject text = new JsonObject();
                    text.addProperty("text", "");
                    parts.add(text);
                }
            }
            content.add("parts", parts);
            contents.add(content);
        }
        body.add("contents", contents);
        if (system != null) {
            JsonObject sys = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("text", system);
            parts.add(text);
            sys.add("parts", parts);
            body.add("systemInstruction", sys);
        }
        if (!request.tools().isEmpty()) {
            JsonObject toolsWrapper = new JsonObject();
            JsonArray functionDeclarations = new JsonArray();
            for (ToolSchema schema : request.tools()) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", schema.name());
                fn.addProperty("description", schema.description());
                fn.add("parameters", schema.parameters());
                functionDeclarations.add(fn);
            }
            toolsWrapper.add("functionDeclarations", functionDeclarations);
            JsonArray tools = new JsonArray();
            tools.add(toolsWrapper);
            body.add("tools", tools);
        }
        JsonObject generation = new JsonObject();
        generation.addProperty("temperature", request.temperature());
        generation.addProperty("maxOutputTokens", request.maxTokens());
        body.add("generationConfig", generation);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(TheseusServerConfig.REQUEST_TIMEOUT_SECONDS.getAsInt()))
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
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            return ChatResponse.text("");
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        JsonArray parts = content.getAsJsonArray("parts");
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (parts != null) {
            for (JsonElement el : parts) {
                JsonObject part = el.getAsJsonObject();
                if (part.has("text")) {
                    text.append(part.get("text").getAsString());
                }
                if (part.has("functionCall")) {
                    JsonObject fc = part.getAsJsonObject("functionCall");
                    String name = fc.get("name").getAsString();
                    String args = fc.has("args") ? fc.get("args").toString() : "{}";
                    toolCalls.add(new ToolCall("gemini_" + UUID.randomUUID(), name, args));
                }
            }
        }
        return new ChatResponse(text.toString(), toolCalls, toolCalls.isEmpty() ? "stop" : "tool_calls", raw);
    }
}
