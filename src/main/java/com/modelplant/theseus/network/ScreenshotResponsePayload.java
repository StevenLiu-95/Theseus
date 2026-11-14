package com.modelplant.theseus.network;

import com.modelplant.theseus.Theseus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record ScreenshotResponsePayload(
        UUID requestId,
        boolean success,
        String pathOrError,
        int width,
        int height,
        String mimeType,
        byte[] imageBytes
) implements CustomPacketPayload {
    public static final Type<ScreenshotResponsePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Theseus.MOD_ID, "screenshot_response"));

    public static final StreamCodec<FriendlyByteBuf, ScreenshotResponsePayload> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUUID(value.requestId);
                buf.writeBoolean(value.success);
                buf.writeUtf(value.pathOrError == null ? "" : value.pathOrError, 32767);
                buf.writeVarInt(value.width);
                buf.writeVarInt(value.height);
                buf.writeUtf(value.mimeType == null || value.mimeType.isBlank() ? "image/png" : value.mimeType, 64);
                byte[] bytes = value.imageBytes == null ? new byte[0] : value.imageBytes;
                buf.writeVarInt(bytes.length);
                if (bytes.length > 0) {
                    buf.writeBytes(bytes);
                }
            },
            buf -> {
                UUID id = buf.readUUID();
                boolean success = buf.readBoolean();
                String path = buf.readUtf(32767);
                int width = buf.readVarInt();
                int height = buf.readVarInt();
                String mime = buf.readUtf(64);
                int len = buf.readVarInt();
                byte[] bytes = new byte[Math.max(0, len)];
                if (len > 0) {
                    buf.readBytes(bytes);
                }
                return new ScreenshotResponsePayload(id, success, path, width, height, mime, bytes);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
