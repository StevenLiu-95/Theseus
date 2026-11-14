package com.modelplant.theseus.session;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a persistent action-bar "Thinking..." status for the duration of an AI turn.
 * Chat messages fade; the action bar is refreshed each second while active.
 */
public final class ThinkingIndicator {
    private static final ConcurrentHashMap<UUID, Boolean> ACTIVE = new ConcurrentHashMap<>();
    private static final int REFRESH_INTERVAL_TICKS = 15;
    private static int tickCounter;

    private ThinkingIndicator() {
    }

    public static void begin(ServerPlayer player) {
        ACTIVE.put(player.getUUID(), Boolean.TRUE);
        show(player, 0);
    }

    public static void end(ServerPlayer player) {
        if (player == null || ACTIVE.remove(player.getUUID()) == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> player.displayClientMessage(Component.empty(), true));
    }

    public static void clear(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    public static void onServerTick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        tickCounter++;
        if (tickCounter % REFRESH_INTERVAL_TICKS != 0) {
            return;
        }
        int phase = (tickCounter / REFRESH_INTERVAL_TICKS) % 3;
        for (UUID id : ACTIVE.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                ACTIVE.remove(id);
                continue;
            }
            show(player, phase);
        }
    }

    private static void show(ServerPlayer player, int phase) {
        String dots = switch (phase) {
            case 1 -> "..";
            case 2 -> "...";
            default -> ".";
        };
        player.displayClientMessage(Component.literal("[Theseus] Thinking" + dots), true);
    }
}
