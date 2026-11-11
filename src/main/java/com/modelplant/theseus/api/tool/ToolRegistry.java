package com.modelplant.theseus.api.tool;

import java.util.Collection;
import java.util.Optional;

public interface ToolRegistry {
    void register(Tool tool);

    Optional<Tool> get(String name);

    Collection<Tool> all();
}
