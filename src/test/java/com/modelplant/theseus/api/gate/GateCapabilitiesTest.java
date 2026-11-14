package com.modelplant.theseus.api.gate;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateCapabilitiesTest {

    @Test
    void matchesExactAndWildcard() {
        assertTrue(GateCapabilities.enabled(Set.of("read", "echo"), "read"));
        assertTrue(GateCapabilities.enabled(Set.of("read", "echo"), "echo"));
        assertFalse(GateCapabilities.enabled(Set.of("read", "echo"), "command"));
        assertTrue(GateCapabilities.enabled(Set.of("*"), "world_edit"));
        assertFalse(GateCapabilities.enabled(null, "read"));
        assertFalse(GateCapabilities.enabled(Set.of("read"), null));
    }
}
