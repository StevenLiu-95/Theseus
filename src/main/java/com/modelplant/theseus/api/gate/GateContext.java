package com.modelplant.theseus.api.gate;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

public final class GateContext {
    private final ServerPlayer player;
    private final UUID sessionId;
    private final Set<String> enabledCapabilities;
    private final boolean operator;
    private final boolean visionCapableModel;
    private final ResourceKey<Level> dimension;
    private final GameType gameType;

    public GateContext(
            ServerPlayer player,
            UUID sessionId,
            Set<String> enabledCapabilities,
            boolean operator,
            boolean visionCapableModel,
            ResourceKey<Level> dimension,
            GameType gameType
    ) {
        this.player = player;
        this.sessionId = sessionId;
        this.enabledCapabilities = enabledCapabilities == null
                ? Set.of()
                : Collections.unmodifiableSet(enabledCapabilities);
        this.operator = operator;
        this.visionCapableModel = visionCapableModel;
        this.dimension = dimension;
        this.gameType = gameType;
    }

    public ServerPlayer player() {
        return player;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public Set<String> enabledCapabilities() {
        return enabledCapabilities;
    }

    public boolean operator() {
        return operator;
    }

    public boolean visionCapableModel() {
        return visionCapableModel;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public GameType gameType() {
        return gameType;
    }

    public boolean capabilityEnabled(String capability) {
        return enabledCapabilities.contains(capability) || enabledCapabilities.contains("*");
    }
}
