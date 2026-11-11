package com.modelplant.theseus.local;

/**
 * Curated local model entry (downloaded via Ollama).
 */
public record LocalModelSpec(
        String id,
        String ollamaName,
        String displayName,
        String approxSize,
        String notes
) {
}
