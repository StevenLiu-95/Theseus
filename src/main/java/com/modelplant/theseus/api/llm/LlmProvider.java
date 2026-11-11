package com.modelplant.theseus.api.llm;

import java.util.concurrent.CompletableFuture;

public interface LlmProvider {
    String id();

    String displayName();

    boolean supportsTools();

    boolean supportsVision();

    CompletableFuture<ChatResponse> chat(ChatRequest request, String apiKey);

    /**
     * Optional streaming. Default falls back to non-streaming {@link #chat}.
     * Implementations may invoke {@code onDelta} with incremental text on a worker thread.
     */
    default CompletableFuture<ChatResponse> chat(
            ChatRequest request,
            String apiKey,
            StreamDeltaConsumer onDelta
    ) {
        return chat(request, apiKey);
    }
}
