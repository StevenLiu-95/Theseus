package com.modelplant.theseus.network;

import com.modelplant.theseus.Theseus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record ScreenshotRequestPayload(UUID requestId) implements CustomPacketPayload {
    public static final Type<ScreenshotRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Theseus.MOD_ID, "screenshot_request"));

    public static final StreamCodec<FriendlyByteBuf, ScreenshotRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> buf.writeUUID(value.requestId),
            buf -> new ScreenshotRequestPayload(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
