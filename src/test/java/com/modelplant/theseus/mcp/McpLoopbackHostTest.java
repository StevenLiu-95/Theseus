package com.modelplant.theseus.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpLoopbackHostTest {

    @Test
    void recognizesLoopbackHosts() {
        assertTrue(McpBindHosts.isLoopback("127.0.0.1"));
        assertTrue(McpBindHosts.isLoopback("localhost"));
        assertTrue(McpBindHosts.isLoopback("::1"));
        assertTrue(McpBindHosts.isLoopback("LOCALHOST"));
        assertFalse(McpBindHosts.isLoopback("0.0.0.0"));
        assertFalse(McpBindHosts.isLoopback("192.168.1.10"));
        assertFalse(McpBindHosts.isLoopback(null));
        assertFalse(McpBindHosts.isLoopback(""));
    }
}
