package com.modelplant.theseus.tool;

import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.api.tool.ToolRegistry;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class TheseusToolRegistry implements ToolRegistry {
    private static final TheseusToolRegistry INSTANCE = new TheseusToolRegistry();

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    private TheseusToolRegistry() {
    }

    public static TheseusToolRegistry get() {
        return INSTANCE;
    }

    @Override
    public void register(Tool tool) {
        Tool previous = tools.put(tool.name(), tool);
        if (previous != null) {
            Theseus.LOGGER.warn("Tool '{}' overwritten", tool.name());
        } else {
            Theseus.LOGGER.debug("Registered tool '{}'", tool.name());
        }
    }

    @Override
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public Collection<Tool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    public void clear() {
        tools.clear();
    }
}
