package com.modelplant.theseus.api.llm;

import java.util.List;

public record ChatResponse(
        String content,
        List<ToolCall> toolCalls,
        String finishReason,
        String raw,
        String reasoning,
        TokenUsage usage
) {
    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        reasoning = reasoning == null ? "" : reasoning;
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
    }

    /** Compatibility constructor used by providers that do not yet fill usage/reasoning. */
    public ChatResponse(String content, List<ToolCall> toolCalls, String finishReason, String raw) {
        this(content, toolCalls, finishReason, raw, null, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean hasReasoning() {
        return reasoning != null && !reasoning.isBlank();
    }

    public static ChatResponse text(String content) {
        return new ChatResponse(content, List.of(), "stop", content, null, null);
    }

    public ChatResponse withUsage(TokenUsage usage) {
        return new ChatResponse(content, toolCalls, finishReason, raw, reasoning, usage);
    }

    public ChatResponse withReasoning(String reasoning) {
        return new ChatResponse(content, toolCalls, finishReason, raw, reasoning, usage);
    }
}
