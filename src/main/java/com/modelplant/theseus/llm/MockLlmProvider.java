package com.modelplant.theseus.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modelplant.theseus.api.llm.ChatMessage;
import com.modelplant.theseus.api.llm.ChatRequest;
import com.modelplant.theseus.api.llm.ChatResponse;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.api.llm.TokenUsage;
import com.modelplant.theseus.api.llm.ToolCall;
import com.modelplant.theseus.api.tool.ToolSchema;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline provider for development. Supports:
 * - plain text replies
 * - "TOOL echo {...}" / "TOOL get_player_info" style directives
 * - JSON: {"tool":"echo","arguments":{"message":"hi"}}
 */
public final class MockLlmProvider implements LlmProvider {
    private static final Pattern TOOL_LINE = Pattern.compile("(?i)^TOOL\\s+(\\w+)(?:\\s+(\\{.*}))?$");

    @Override
    public String id() {
        return "mock";
    }

    @Override
    public String displayName() {
        return "Mock (offline)";
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    @Override
    public boolean supportsVision() {
        return false;
    }

    @Override
    public CompletableFuture<ChatResponse> chat(ChatRequest request, String apiKey) {
        return CompletableFuture.completedFuture(respond(request));
    }

    private ChatResponse respond(ChatRequest request) {
        ChatMessage lastUser = null;
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatMessage msg = request.messages().get(i);
            if ("user".equals(msg.role())) {
                lastUser = msg;
                break;
            }
            if ("tool".equals(msg.role())) {
                return withMockMeta(ChatResponse.text("Tool result received: " + msg.content()), 24, 18);
            }
        }
        if (lastUser == null || lastUser.content() == null) {
            return withMockMeta(ChatResponse.text("Mock provider online. Say something, or: TOOL echo {\"message\":\"hi\"}"), 8, 20);
        }

        String text = lastUser.content().trim();
        Matcher matcher = TOOL_LINE.matcher(text);
        if (matcher.matches()) {
            String name = matcher.group(1);
            String args = matcher.group(2) == null ? "{}" : matcher.group(2);
            if (hasTool(request.tools(), name)) {
                return withMockMeta(
                        new ChatResponse(null, List.of(new ToolCall(idOf(name), name, args)), "tool_calls", text,
                                "Mock deciding to call tool " + name, null),
                        16, 12);
            }
            return withMockMeta(ChatResponse.text("Tool not available under current gate: " + name), 12, 10);
        }

        if (text.startsWith("{") && text.contains("\"tool\"")) {
            try {
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                String name = obj.get("tool").getAsString();
                String args = obj.has("arguments") ? obj.get("arguments").toString() : "{}";
                if (hasTool(request.tools(), name)) {
                    return withMockMeta(
                            new ChatResponse(null, List.of(new ToolCall(idOf(name), name, args)), "tool_calls", text,
                                    "Mock JSON tool directive for " + name, null),
                            16, 12);
                }
            } catch (Exception ignored) {
            }
        }

        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("player info") || lower.contains("who am i") || lower.contains("我是谁")) {
            if (hasTool(request.tools(), "get_player_info")) {
                return withMockMeta(
                        new ChatResponse(null, List.of(new ToolCall(idOf("get_player_info"), "get_player_info", "{}")),
                                "tool_calls", text, "Mock inferred get_player_info from user text", null),
                        14, 10);
            }
        }

        return withMockMeta(
                ChatResponse.text("Mock Theseus heard: " + text + "\nTip: TOOL get_player_info  or  TOOL echo {\"message\":\"hello\"}")
                        .withReasoning("Mock echo path; no tool needed"),
                20, 28);
    }

    private static ChatResponse withMockMeta(ChatResponse response, int prompt, int completion) {
        return response.withUsage(new TokenUsage(prompt, completion, prompt + completion));
    }

    private static boolean hasTool(List<ToolSchema> tools, String name) {
        return tools.stream().anyMatch(t -> t.name().equals(name));
    }

    private static String idOf(String name) {
        return "mock_" + name + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
