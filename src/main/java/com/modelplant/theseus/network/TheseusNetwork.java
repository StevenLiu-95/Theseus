package com.modelplant.theseus.network;

import com.modelplant.theseus.vision.ScreenshotService;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class TheseusNetwork {
    private TheseusNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
                PlayerControlPayload.TYPE,
                PlayerControlPayload.STREAM_CODEC,
                TheseusNetwork::handlePlayerControlClient
        );
        registrar.playToClient(
                ScreenshotRequestPayload.TYPE,
                ScreenshotRequestPayload.STREAM_CODEC,
                TheseusNetwork::handleScreenshotRequestClient
        );
        registrar.playToServer(
                ScreenshotResponsePayload.TYPE,
                ScreenshotResponsePayload.STREAM_CODEC,
                TheseusNetwork::handleScreenshotResponseServer
        );
    }

    private static void handlePlayerControlClient(PlayerControlPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHooks.PLAYER_CONTROL.accept(payload));
    }

    private static void handleScreenshotRequestClient(ScreenshotRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPacketHooks.SCREENSHOT_REQUEST.accept(payload));
    }

    private static void handleScreenshotResponseServer(ScreenshotResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                ScreenshotService.handleResponse(serverPlayer, payload);
            }
        });
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
