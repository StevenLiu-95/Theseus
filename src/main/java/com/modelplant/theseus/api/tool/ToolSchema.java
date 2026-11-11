package com.modelplant.theseus.api.tool;

import com.google.gson.JsonObject;

/**
 * JSON Schema fragment describing tool parameters for LLM / MCP consumers.
 */
public record ToolSchema(String name, String description, JsonObject parameters) {
    public ToolSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name required");
        }
        if (description == null) {
            description = "";
        }
        if (parameters == null) {
            parameters = new JsonObject();
        }
    }

    public JsonObject toOpenAiFunction() {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", description);
        fn.add("parameters", parameters);
        return fn;
    }

    public JsonObject toMcpTool() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", parameters);
        return tool;
    }
}
