package com.modelplant.theseus.client;

import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.config.TheseusClientConfig;
import com.modelplant.theseus.network.ScreenshotRequestPayload;
import com.modelplant.theseus.network.ScreenshotResponsePayload;
import com.modelplant.theseus.network.TheseusNetwork;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ClientScreenshotHandler {
    /** Keep packets under typical custom payload limits. */
    private static final int MAX_PNG_BYTES = 450_000;

    private ClientScreenshotHandler() {
    }

    public static void handleRequest(ScreenshotRequestPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> capture(payload.requestId()));
    }

    private static void capture(java.util.UUID requestId) {
        Minecraft mc = Minecraft.getInstance();
        try {
            NativeImage image = takeNativeImage(mc);
            if (image == null) {
                TheseusNetwork.sendToServer(new ScreenshotResponsePayload(
                        requestId, false, "Failed to capture framebuffer", 0, 0, new byte[0]));
                return;
            }
            int maxW = TheseusClientConfig.SCREENSHOT_MAX_WIDTH.getAsInt();
            // Prefer a vision-friendly width for network transfer
            maxW = Math.min(maxW, 960);
            NativeImage scaled = maybeScale(image, maxW);
            if (scaled != image) {
                image.close();
            }
            Path dir = FMLPaths.GAMEDIR.get().resolve("theseus_screenshots");
            Files.createDirectories(dir);
            String name = "shot_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")) + ".png";
            Path file = dir.resolve(name);
            scaled.writeToFile(file.toFile());
            int w = scaled.getWidth();
            int h = scaled.getHeight();
            scaled.close();

            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_PNG_BYTES) {
                Theseus.LOGGER.warn("Screenshot {} bytes exceeds cap {}; sending path only", bytes.length, MAX_PNG_BYTES);
                bytes = new byte[0];
            }
            TheseusNetwork.sendToServer(new ScreenshotResponsePayload(
                    requestId, true, file.toAbsolutePath().toString(), w, h, bytes));
        } catch (Exception e) {
            Theseus.LOGGER.error("Screenshot capture failed", e);
            TheseusNetwork.sendToServer(new ScreenshotResponsePayload(
                    requestId, false, e.getMessage() == null ? "error" : e.getMessage(), 0, 0, new byte[0]));
        }
    }

    private static NativeImage takeNativeImage(Minecraft mc) {
        try {
            return net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget());
        } catch (Throwable t) {
            Theseus.LOGGER.error("Native screenshot failed", t);
            return null;
        }
    }

    private static NativeImage maybeScale(NativeImage source, int maxWidth) {
        if (source.getWidth() <= maxWidth) {
            return source;
        }
        int newW = maxWidth;
        int newH = Math.max(1, (int) (source.getHeight() * (maxWidth / (double) source.getWidth())));
        NativeImage scaled = new NativeImage(newW, newH, false);
        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                int sx = x * source.getWidth() / newW;
                int sy = y * source.getHeight() / newH;
                scaled.setPixelRGBA(x, y, source.getPixelRGBA(sx, sy));
            }
        }
        return scaled;
    }
}
