package com.modelplant.theseus.api.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public record ToolResult(boolean success, String message, JsonElement data) {
    public static ToolResult ok(String message) {
        return new ToolResult(true, message, null);
    }

    public static ToolResult ok(String message, JsonElement data) {
        return new ToolResult(true, message, data);
    }

    public static ToolResult okJson(JsonObject data) {
        return new ToolResult(true, "ok", data);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, message, null);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("success", success);
        obj.addProperty("message", message == null ? "" : message);
        if (data != null) {
            obj.add("data", data);
        }
        return obj;
    }

    public String toText() {
        if (data instanceof JsonPrimitive primitive && primitive.isString()) {
            return success ? primitive.getAsString() : "ERROR: " + message;
        }
        if (data != null) {
            return toJson().toString();
        }
        return success ? (message == null ? "ok" : message) : "ERROR: " + message;
    }
}
