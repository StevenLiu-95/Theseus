package com.modelplant.theseus.api.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public record ChatMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String name,
        List<ImageData> images
) {
    public ChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ChatMessage(String role, String content) {
        this(role, content, List.of(), null, null, List.of());
    }

    public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId, String name) {
        this(role, content, toolCalls, toolCallId, name, List.of());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage userWithImages(String content, List<ImageData> images) {
        return new ChatMessage("user", content, List.of(), null, null, images);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", null, List.copyOf(toolCalls), null, null, List.of());
    }

    public static ChatMessage tool(String toolCallId, String name, String content) {
        return new ChatMessage("tool", content, List.of(), toolCallId, name, List.of());
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }

    public JsonObject toOpenAiJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", role);
        if (hasImages()) {
            JsonArray contentArr = new JsonArray();
            if (content != null && !content.isBlank()) {
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", content);
                contentArr.add(text);
            }
            for (ImageData image : images) {
                JsonObject imagePart = new JsonObject();
                imagePart.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", "data:" + image.mimeType() + ";base64," + image.base64());
                imagePart.add("image_url", url);
                contentArr.add(imagePart);
            }
            obj.add("content", contentArr);
        } else if (content != null) {
            obj.addProperty("content", content);
        } else if (toolCalls != null && !toolCalls.isEmpty()) {
            // Some OpenAI-compatible gateways (e.g. DeepSeek) expect an explicit content field.
            obj.add("content", com.google.gson.JsonNull.INSTANCE);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (ToolCall call : toolCalls) {
                arr.add(call.toOpenAiJson());
            }
            obj.add("tool_calls", arr);
        }
        if (toolCallId != null) {
            obj.addProperty("tool_call_id", toolCallId);
        }
        if (name != null) {
            obj.addProperty("name", name);
        }
        return obj;
    }
}
