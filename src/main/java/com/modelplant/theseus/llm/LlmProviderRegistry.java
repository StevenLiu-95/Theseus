package com.modelplant.theseus.llm;

import com.modelplant.theseus.api.llm.LlmProvider;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class LlmProviderRegistry {
    private static final LlmProviderRegistry INSTANCE = new LlmProviderRegistry();

    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();

    private LlmProviderRegistry() {
    }

    public static LlmProviderRegistry get() {
        return INSTANCE;
    }

    public void register(LlmProvider provider) {
        providers.put(provider.id(), provider);
    }

    public Optional<LlmProvider> get(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    public LlmProvider require(String id) {
        LlmProvider provider = providers.get(id);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown LLM provider: " + id);
        }
        return provider;
    }

    public Collection<LlmProvider> all() {
        return Collections.unmodifiableCollection(providers.values());
    }
}
