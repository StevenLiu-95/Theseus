package com.modelplant.theseus.mcp;

import java.util.Locale;
import java.util.Set;

/** Bind-host helpers for MCP bridge startup policy. */
public final class McpBindHosts {
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "localhost", "0:0:0:0:0:0:0:1");

    private McpBindHosts() {
    }

    public static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        return LOOPBACK.contains(host.trim().toLowerCase(Locale.ROOT));
    }
}
