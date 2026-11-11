package com.modelplant.theseus.api.tool;

/**
 * A callable capability exposed to LLMs, MCP agents, and other mods.
 */
public interface Tool {
    ToolSchema schema();

    default String name() {
        return schema().name();
    }

    /**
     * Capability flag used by the gate engine (e.g. read, command, control, world_edit, possess, vision).
     */
    default String capability() {
        return "misc";
    }

    /**
     * Whether this tool must run on the server main thread.
     */
    default boolean requiresMainThread() {
        return true;
    }

    ToolResult execute(ToolContext context) throws Exception;
}
