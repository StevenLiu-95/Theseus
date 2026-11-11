package com.modelplant.theseus.api.llm;

import com.modelplant.theseus.api.tool.ToolSchema;

import java.util.List;

public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        List<ToolSchema> tools,
        double temperature,
        int maxTokens,
        boolean vision
) {
    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
