package com.modelplant.theseus.api.event;

import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.llm.LlmProviderRegistry;
import net.neoforged.bus.api.Event;

public final class RegisterTheseusProvidersEvent extends Event {
    private final LlmProviderRegistry registry;

    public RegisterTheseusProvidersEvent(LlmProviderRegistry registry) {
        this.registry = registry;
    }

    public LlmProviderRegistry getRegistry() {
        return registry;
    }

    public void register(LlmProvider provider) {
        registry.register(provider);
    }
}
