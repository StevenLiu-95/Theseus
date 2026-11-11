package com.modelplant.theseus.client;

import com.modelplant.theseus.config.TheseusClientConfig;
import com.modelplant.theseus.network.PlayerControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;

public final class ClientControlHandler {
    private ClientControlHandler() {
    }

    public static void handle(PlayerControlPayload payload) {
        if (!TheseusClientConfig.ALLOW_LOCAL_CONTROL_PROXY.getAsBoolean()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        switch (payload.action()) {
            case LOOK -> {
                player.setYRot(payload.yaw());
                player.setXRot(payload.pitch());
                player.yHeadRot = payload.yaw();
            }
            case MOVE -> {
                // Server already simulates movement; client hint for smoother local feel
                player.setYRot(yawToward(player.getX(), player.getZ(), payload.x(), payload.z()));
            }
            case SELECT -> {
                int slot = (int) payload.x();
                if (Inventory.isHotbarSlot(slot)) {
                    player.getInventory().selected = slot;
                }
            }
            case OPEN_INVENTORY -> {
                if (mc.screen == null) {
                    mc.setScreen(new InventoryScreen(player));
                }
            }
            default -> {
            }
        }
    }

    private static float yawToward(double x, double z, double tx, double tz) {
        double dx = tx - x;
        double dz = tz - z;
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }
}
