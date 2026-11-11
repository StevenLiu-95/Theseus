package com.modelplant.theseus.session;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AiSessionManager {
    private static final AiSessionManager INSTANCE = new AiSessionManager();

    private final Map<UUID, AiSession> byPlayer = new ConcurrentHashMap<>();

    private AiSessionManager() {
    }

    public static AiSessionManager get() {
        return INSTANCE;
    }

    public AiSession getOrCreate(ServerPlayer player) {
        return byPlayer.computeIfAbsent(player.getUUID(), id -> new AiSession(UUID.randomUUID(), id));
    }

    public Optional<AiSession> find(UUID playerId) {
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    public AiSession renew(ServerPlayer player) {
        AiSession session = new AiSession(UUID.randomUUID(), player.getUUID());
        byPlayer.put(player.getUUID(), session);
        return session;
    }

    public void clear(UUID playerId) {
        AiSession session = byPlayer.get(playerId);
        if (session != null) {
            session.clearHistory();
        }
    }

    public void remove(UUID playerId) {
        byPlayer.remove(playerId);
    }

    public void clearAll() {
        byPlayer.clear();
    }
}
