package com.modelplant.theseus.api.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public record ToolCall(String id, String name, String argumentsJson) {
    public JsonObject arguments() {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(argumentsJson).getAsJsonObject();
        } catch (Exception e) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("_raw", argumentsJson);
            return fallback;
        }
    }

    public JsonObject toOpenAiJson() {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("arguments", argumentsJson == null ? "{}" : argumentsJson);
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("type", "function");
        obj.add("function", fn);
        return obj;
    }
}
