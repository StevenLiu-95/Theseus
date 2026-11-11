package com.modelplant.theseus.local;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in local models recommended for Theseus tool-calling.
 */
public final class LocalModelCatalog {
    private static final Map<String, LocalModelSpec> BY_ID = new LinkedHashMap<>();
    private static final Map<String, LocalModelSpec> BY_OLLAMA = new LinkedHashMap<>();

    static {
        register(new LocalModelSpec(
                "qwen3-8b",
                "qwen3:8b",
                "Qwen3 8B",
                "~5 GB",
                "Default pick: strong tools + Chinese/English"
        ));
        register(new LocalModelSpec(
                "llama3-groq-tool-use-8b",
                "llama3-groq-tool-use:8b",
                "Llama3 Groq Tool-Use 8B",
                "~5 GB",
                "Tool-call specialist; weaker chat"
        ));
        register(new LocalModelSpec(
                "qwen3-4b",
                "qwen3:4b",
                "Qwen3 4B",
                "~2.5 GB",
                "Small VRAM option; lighter multi-step tools"
        ));
    }

    private LocalModelCatalog() {
    }

    private static void register(LocalModelSpec spec) {
        BY_ID.put(spec.id(), spec);
        BY_OLLAMA.put(spec.ollamaName().toLowerCase(Locale.ROOT), spec);
    }

    public static Collection<LocalModelSpec> all() {
        return BY_ID.values();
    }

    public static Optional<LocalModelSpec> resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        LocalModelSpec byId = BY_ID.get(key);
        if (byId != null) {
            return Optional.of(byId);
        }
        LocalModelSpec byName = BY_OLLAMA.get(key);
        if (byName != null) {
            return Optional.of(byName);
        }
        // allow dashes/colons variants: qwen3_8b, qwen3-8b already covered
        String normalized = key.replace('_', '-');
        byId = BY_ID.get(normalized);
        if (byId != null) {
            return Optional.of(byId);
        }
        return Optional.empty();
    }
}
