package com.modelplant.theseus.api.llm;

import com.modelplant.theseus.api.tool.ToolSchema;

import java.util.List;

public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        List<ToolSchema> tools,
        double temperature,
        int maxTokens,
        boolean vision,
        String baseUrlOverride
) {
    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (baseUrlOverride != null && baseUrlOverride.isBlank()) {
            baseUrlOverride = null;
        }
    }

    public ChatRequest(
            String model,
            List<ChatMessage> messages,
            List<ToolSchema> tools,
            double temperature,
            int maxTokens,
            boolean vision
    ) {
        this(model, messages, tools, temperature, maxTokens, vision, null);
    }

    public String effectiveBaseUrl(String fallback) {
        return baseUrlOverride != null && !baseUrlOverride.isBlank() ? baseUrlOverride : fallback;
    }
}
