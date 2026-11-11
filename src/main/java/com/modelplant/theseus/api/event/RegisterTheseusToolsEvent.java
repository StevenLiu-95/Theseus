package com.modelplant.theseus.api.event;

import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.api.tool.ToolRegistry;
import net.neoforged.bus.api.Event;

public final class RegisterTheseusToolsEvent extends Event {
    private final ToolRegistry registry;

    public RegisterTheseusToolsEvent(ToolRegistry registry) {
        this.registry = registry;
    }

    public ToolRegistry getRegistry() {
        return registry;
    }

    public void register(Tool tool) {
        registry.register(tool);
    }
}
