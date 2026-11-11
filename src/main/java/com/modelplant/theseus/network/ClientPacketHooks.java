package com.modelplant.theseus.network;

import java.util.function.Consumer;

/**
 * Client-only packet handlers are registered here to keep dedicated servers free of client classloads.
 */
public final class ClientPacketHooks {
    public static Consumer<PlayerControlPayload> PLAYER_CONTROL = payload -> {
    };
    public static Consumer<ScreenshotRequestPayload> SCREENSHOT_REQUEST = payload -> {
    };

    private ClientPacketHooks() {
    }
}
