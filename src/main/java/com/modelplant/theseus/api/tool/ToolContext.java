package com.modelplant.theseus.api.tool;

import com.google.gson.JsonObject;
import com.modelplant.theseus.api.gate.GateContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public final class ToolContext {
    private final MinecraftServer server;
    private final ServerPlayer player;
    private final UUID sessionId;
    private final JsonObject arguments;
    private final GateContext gateContext;

    public ToolContext(MinecraftServer server, ServerPlayer player, UUID sessionId, JsonObject arguments, GateContext gateContext) {
        this.server = server;
        this.player = player;
        this.sessionId = sessionId;
        this.arguments = arguments == null ? new JsonObject() : arguments;
        this.gateContext = gateContext;
    }

    public MinecraftServer server() {
        return server;
    }

    public ServerPlayer player() {
        return player;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public JsonObject arguments() {
        return arguments;
    }

    public GateContext gateContext() {
        return gateContext;
    }

    public String argString(String key, String fallback) {
        return arguments.has(key) && arguments.get(key).isJsonPrimitive()
                ? arguments.get(key).getAsString()
                : fallback;
    }

    public int argInt(String key, int fallback) {
        return arguments.has(key) && arguments.get(key).isJsonPrimitive()
                ? arguments.get(key).getAsInt()
                : fallback;
    }

    public double argDouble(String key, double fallback) {
        return arguments.has(key) && arguments.get(key).isJsonPrimitive()
                ? arguments.get(key).getAsDouble()
                : fallback;
    }

    public boolean argBool(String key, boolean fallback) {
        return arguments.has(key) && arguments.get(key).isJsonPrimitive()
                ? arguments.get(key).getAsBoolean()
                : fallback;
    }
}
