package com.modelplant.theseus.network;

import com.modelplant.theseus.Theseus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlayerControlPayload(
        Action action,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        boolean sprint
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PlayerControlPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Theseus.MOD_ID, "player_control"));

    public static final StreamCodec<FriendlyByteBuf, PlayerControlPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeEnum(value.action);
                buf.writeDouble(value.x);
                buf.writeDouble(value.y);
                buf.writeDouble(value.z);
                buf.writeFloat(value.yaw);
                buf.writeFloat(value.pitch);
                buf.writeBoolean(value.sprint);
            },
            buf -> new PlayerControlPayload(
                    buf.readEnum(Action.class),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Action {
        MOVE, LOOK, BREAK, PLACE, USE, SELECT, OPEN_INVENTORY
    }
}
