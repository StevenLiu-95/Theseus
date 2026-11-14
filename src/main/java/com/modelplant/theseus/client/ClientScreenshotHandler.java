package com.modelplant.theseus.client;

import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.config.TheseusClientConfig;
import com.modelplant.theseus.network.ScreenshotRequestPayload;
import com.modelplant.theseus.network.ScreenshotResponsePayload;
import com.modelplant.theseus.network.TheseusNetwork;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;

public final class ClientScreenshotHandler {
    /** Keep packets under typical custom payload limits. */
    private static final int MAX_IMAGE_BYTES = 450_000;

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
                        requestId, false, "Failed to capture framebuffer", 0, 0, "image/png", new byte[0]));
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

            byte[] pngBytes = Files.readAllBytes(file);
            String mime = "image/png";
            byte[] networkBytes = pngBytes;
            if (pngBytes.length > MAX_IMAGE_BYTES) {
                float quality = TheseusClientConfig.SCREENSHOT_JPEG_QUALITY.get().floatValue();
                byte[] jpeg = encodeJpeg(scaled, quality);
                if (jpeg != null && jpeg.length > 0 && jpeg.length <= MAX_IMAGE_BYTES) {
                    networkBytes = jpeg;
                    mime = "image/jpeg";
                    Theseus.LOGGER.info(
                            "Screenshot PNG {} bytes exceeded cap {}; sent JPEG {} bytes (q={})",
                            pngBytes.length, MAX_IMAGE_BYTES, jpeg.length, quality);
                } else if (jpeg != null && jpeg.length > MAX_IMAGE_BYTES) {
                    Theseus.LOGGER.warn(
                            "Screenshot JPEG {} bytes still exceeds cap {}; sending path only",
                            jpeg.length, MAX_IMAGE_BYTES);
                    networkBytes = new byte[0];
                } else {
                    Theseus.LOGGER.warn(
                            "Screenshot {} bytes exceeds cap {} and JPEG encode failed; sending path only",
                            pngBytes.length, MAX_IMAGE_BYTES);
                    networkBytes = new byte[0];
                }
            }
            scaled.close();

            TheseusNetwork.sendToServer(new ScreenshotResponsePayload(
                    requestId, true, file.toAbsolutePath().toString(), w, h, mime, networkBytes));
        } catch (Exception e) {
            Theseus.LOGGER.error("Screenshot capture failed", e);
            TheseusNetwork.sendToServer(new ScreenshotResponsePayload(
                    requestId, false, e.getMessage() == null ? "error" : e.getMessage(), 0, 0, "image/png", new byte[0]));
        }
    }

    private static byte[] encodeJpeg(NativeImage source, float quality) {
        try {
            BufferedImage buffered = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int rgba = source.getPixelRGBA(x, y);
                    // NativeImage stores ABGR in little-endian int: A B G R packed
                    int a = (rgba >> 24) & 0xFF;
                    int b = (rgba >> 16) & 0xFF;
                    int g = (rgba >> 8) & 0xFF;
                    int r = rgba & 0xFF;
                    if (a == 0) {
                        buffered.setRGB(x, y, 0x000000);
                    } else {
                        buffered.setRGB(x, y, (r << 16) | (g << 8) | b);
                    }
                }
            }
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                return null;
            }
            ImageWriter writer = writers.next();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(Math.max(0.1f, Math.min(1.0f, quality)));
                }
                writer.write(null, new IIOImage(buffered, null, null), param);
            } finally {
                writer.dispose();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            Theseus.LOGGER.error("JPEG encode failed", e);
            return null;
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
