package com.modelplant.theseus.api.llm;

import java.util.List;

public record ChatResponse(String content, List<ToolCall> toolCalls, String finishReason, String raw) {
    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static ChatResponse text(String content) {
        return new ChatResponse(content, List.of(), "stop", content);
    }
}
