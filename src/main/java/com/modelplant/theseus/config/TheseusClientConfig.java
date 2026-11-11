package com.modelplant.theseus.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class TheseusClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SHOW_AI_TOASTS = BUILDER
            .comment("Show toast notifications for AI status")
            .define("showAiToasts", true);

    public static final ModConfigSpec.IntValue SCREENSHOT_MAX_WIDTH = BUILDER
            .comment("Max screenshot width before downscale")
            .defineInRange("screenshotMaxWidth", 1280, 320, 3840);

    public static final ModConfigSpec.DoubleValue SCREENSHOT_JPEG_QUALITY = BUILDER
            .comment("JPEG quality for vision screenshots (0-1)")
            .defineInRange("screenshotJpegQuality", 0.7d, 0.1d, 1.0d);

    public static final ModConfigSpec.BooleanValue ALLOW_LOCAL_CONTROL_PROXY = BUILDER
            .comment("Allow server to drive local player input proxy")
            .define("allowLocalControlProxy", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private TheseusClientConfig() {
    }
}
