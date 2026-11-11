package com.modelplant.theseus.api.gate;

import com.modelplant.theseus.api.tool.Tool;

/**
 * Filters tools for a conversation round. Return false to hide the tool.
 */
public interface GateRule {
    String id();

    default int priority() {
        return 100;
    }

    boolean allow(Tool tool, GateContext context);
}
