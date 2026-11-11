package com.modelplant.theseus.permission;

import com.modelplant.theseus.config.TheseusServerConfig;
import net.minecraft.server.level.ServerPlayer;

public final class TheseusPermissions {
    private TheseusPermissions() {
    }

    public static boolean isOperator(ServerPlayer player) {
        return player != null && player.hasPermissions(2);
    }

    public static boolean canUseTheseus(ServerPlayer player) {
        // Singleplayer integrated owners and ops can always use; others need permission level 1+
        return player != null && (player.hasPermissions(1) || player.getServer() != null && player.getServer().isSingleplayerOwner(player.getGameProfile()));
    }

    public static boolean canRunCommands(ServerPlayer player) {
        return player != null && player.hasPermissions(TheseusServerConfig.COMMAND_WHITELIST_LEVEL.getAsInt());
    }

    public static boolean canControlPlayers(ServerPlayer actor, ServerPlayer target) {
        if (actor == null || target == null) {
            return false;
        }
        if (actor.getUUID().equals(target.getUUID())) {
            return canUseTheseus(actor);
        }
        return isOperator(actor);
    }
}
