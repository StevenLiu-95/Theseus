package com.modelplant.theseus.llm;

import com.modelplant.theseus.local.LocalModelCatalog;
import com.modelplant.theseus.local.LocalModelSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Curated model-id suggestions for {@code /theseus config model} tab-complete.
 * These are recommendations only; any model string remains accepted.
 */
public final class SuggestedModels {
    private static final Map<String, List<String>> BY_PROVIDER = new LinkedHashMap<>();
    private static final CopyOnWriteArrayList<String> CACHED_OLLAMA_INSTALLED = new CopyOnWriteArrayList<>();

    static {
        BY_PROVIDER.put("mock", List.of("mock-theseus"));
        BY_PROVIDER.put("openai", List.of(
                "gpt-4o-mini",
                "gpt-4o",
                "gpt-4.1-mini",
                "gpt-4.1",
                "o4-mini",
                "o3-mini"
        ));
        BY_PROVIDER.put("deepseek", List.of(
                "deepseek-chat",
                "deepseek-reasoner"
        ));
        BY_PROVIDER.put("qwen", List.of(
                "qwen-plus",
                "qwen-turbo",
                "qwen-max",
                "qwen2.5-72b-instruct",
                "qwen2.5-32b-instruct"
        ));
        BY_PROVIDER.put("anthropic", List.of(
                "claude-sonnet-4-20250514",
                "claude-3-5-sonnet-latest",
                "claude-3-5-haiku-latest",
                "claude-3-opus-latest"
        ));
        BY_PROVIDER.put("gemini", List.of(
                "gemini-2.0-flash",
                "gemini-2.0-flash-lite",
                "gemini-2.5-flash",
                "gemini-2.5-pro",
                "gemini-1.5-flash",
                "gemini-1.5-pro"
        ));
        // ollama curated ids / names come from LocalModelCatalog + cached installed tags
        BY_PROVIDER.put("ollama", List.of());
    }

    private SuggestedModels() {
    }

    /** Refresh from a worker thread after Ollama list/status/download. */
    public static void updateOllamaInstalledCache(List<String> installed) {
        CACHED_OLLAMA_INSTALLED.clear();
        if (installed != null) {
            CACHED_OLLAMA_INSTALLED.addAll(installed);
        }
    }

    public static List<String> forProvider(String providerId) {
        String id = providerId == null ? "" : providerId.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();

        if ("ollama".equals(id)) {
            for (LocalModelSpec spec : LocalModelCatalog.all()) {
                out.add(spec.ollamaName());
                out.add(spec.id());
            }
            out.addAll(CACHED_OLLAMA_INSTALLED);
        } else {
            out.addAll(BY_PROVIDER.getOrDefault(id, List.of()));
        }

        return new ArrayList<>(out);
    }

    public static List<String> forActiveProvider(String providerId) {
        return forProvider(providerId);
    }
}
