package com.modelplant.theseus.api.gate;

import java.util.Set;

/** Capability-set helpers shared by gate rules and tests. */
public final class GateCapabilities {
    private GateCapabilities() {
    }

    public static boolean enabled(Set<String> enabledCapabilities, String capability) {
        if (enabledCapabilities == null || capability == null) {
            return false;
        }
        return enabledCapabilities.contains(capability) || enabledCapabilities.contains("*");
    }
}
