package com.modelplant.theseus.audit;

import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.config.TheseusServerConfig;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class AuditLog {
    private AuditLog() {
    }

    public static void record(ServerPlayer player, String tool, String args, ToolResult result) {
        if (!TheseusServerConfig.AUDIT_LOG_ENABLED.getAsBoolean()) {
            return;
        }
        String line = Instant.now() + " | " + player.getGameProfile().getName() + " | " + tool + " | "
                + truncate(args) + " | success=" + result.success() + " | " + truncate(result.message());
        Theseus.LOGGER.info("[audit] {}", line);
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("theseus");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("audit.log"), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Theseus.LOGGER.debug("Audit file write failed", e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
