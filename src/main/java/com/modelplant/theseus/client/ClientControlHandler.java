package com.modelplant.theseus.client;

import com.modelplant.theseus.config.TheseusClientConfig;
import com.modelplant.theseus.network.PlayerControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

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
