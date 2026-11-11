package com.modelplant.theseus.vision;

import com.google.gson.JsonObject;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.network.ScreenshotRequestPayload;
import com.modelplant.theseus.network.ScreenshotResponsePayload;
import com.modelplant.theseus.network.TheseusNetwork;
import com.modelplant.theseus.runtime.ServerTaskQueue;
import net.minecraft.server.level.ServerPlayer;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ScreenshotService {
    private static final Map<UUID, CompletableFuture<ToolResult>> PENDING = new ConcurrentHashMap<>();

    private ScreenshotService() {
    }

    /**
     * Must be called off the server main thread (worker), otherwise waiting for the client response deadlocks.
     */
    public static ToolResult request(ServerPlayer player) {
        UUID requestId = UUID.randomUUID();
        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        PENDING.put(requestId, future);
        ServerTaskQueue.get().submit(() -> TheseusNetwork.sendToPlayer(player, new ScreenshotRequestPayload(requestId)));
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            PENDING.remove(requestId);
            return ToolResult.error("Screenshot timed out or failed: " + e.getMessage());
        }
    }

    public static void handleResponse(ServerPlayer player, ScreenshotResponsePayload payload) {
        CompletableFuture<ToolResult> future = PENDING.remove(payload.requestId());
        if (future == null) {
            return;
        }
        if (!payload.success()) {
            future.complete(ToolResult.error(payload.pathOrError()));
            return;
        }
        JsonObject data = new JsonObject();
        data.addProperty("path", payload.pathOrError());
        data.addProperty("width", payload.width());
        data.addProperty("height", payload.height());
        data.addProperty("player", player.getGameProfile().getName());
        data.addProperty("mime", "image/png");
        byte[] bytes = payload.pngBytes();
        if (bytes != null && bytes.length > 0) {
            data.addProperty("base64_png", Base64.getEncoder().encodeToString(bytes));
            data.addProperty("byte_length", bytes.length);
        } else {
            data.addProperty("base64_png", "");
            data.addProperty("warning", "No image bytes in packet; path only");
        }
        future.complete(ToolResult.ok("Screenshot captured", data));
    }
}
