package com.modelplant.theseus.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke coverage for nav/combat end-reason vocabulary used in status JSON.
 */
class NavEndReasonTest {
    @Test
    void endReasonsCoverNavAndCombatOutcomes() {
        String[] expected = {
                "ARRIVED", "TIMEOUT", "STUCK", "CANCELLED",
                "TARGET_LOST", "TARGET_DEAD", "DEFEATED", "MAX_HITS"
        };
        PlayerController.EndReason[] values = PlayerController.EndReason.values();
        assertEquals(expected.length, values.length);
        for (String name : expected) {
            assertTrue(
                    java.util.Arrays.stream(values).anyMatch(v -> v.name().equals(name)),
                    "missing " + name);
        }
    }
}
