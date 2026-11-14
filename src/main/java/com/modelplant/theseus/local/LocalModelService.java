package com.modelplant.theseus.local;

import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.config.PlayerConfigStore;
import com.modelplant.theseus.config.PlayerLlmSettings;
import com.modelplant.theseus.llm.SuggestedModels;
import com.modelplant.theseus.runtime.TheseusExecutors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Download curated models via Ollama and activate them for the calling player's chat config.
 */
public final class LocalModelService {
    private static final AtomicBoolean DOWNLOAD_BUSY = new AtomicBoolean(false);
    private static final AtomicReference<String> ACTIVE_DOWNLOAD = new AtomicReference<>(null);

    private LocalModelService() {
    }

    public static boolean isDownloading() {
        return DOWNLOAD_BUSY.get();
    }

    public static String activeDownload() {
        return ACTIVE_DOWNLOAD.get();
    }

    public static String statusSummary() {
        return statusSummary(null);
    }

    public static String statusSummary(PlayerLlmSettings settings) {
        boolean up = OllamaClient.isReachable(settings);
        StringBuilder sb = new StringBuilder();
        sb.append("ollama=").append(up ? "online" : "offline")
                .append(" url=").append(OllamaClient.apiBase(settings));
        if (isDownloading()) {
            sb.append(" downloading=").append(activeDownload());
        }
        if (!up) {
            sb.append(" | Install Ollama from https://ollama.com and keep it running.");
            return sb.toString();
        }
        try {
            List<String> installed = OllamaClient.listInstalledModelNames(settings);
            SuggestedModels.updateOllamaInstalledCache(installed);
            sb.append(" installed=").append(installed.size());
            for (LocalModelSpec spec : LocalModelCatalog.all()) {
                boolean yes = containsModel(installed, spec.ollamaName());
                sb.append(" | ").append(spec.id()).append('=').append(yes ? "ready" : "missing");
            }
        } catch (Exception e) {
            sb.append(" error=").append(e.getMessage());
        }
        return sb.toString();
    }

    public static String catalogListing() {
        return catalogListing(null);
    }

    public static String catalogListing(PlayerLlmSettings settings) {
        StringBuilder sb = new StringBuilder("Curated local models:\n");
        boolean up = OllamaClient.isReachable(settings);
        List<String> installed = List.of();
        if (up) {
            try {
                installed = OllamaClient.listInstalledModelNames(settings);
                SuggestedModels.updateOllamaInstalledCache(installed);
            } catch (Exception ignored) {
            }
        }
        for (LocalModelSpec spec : LocalModelCatalog.all()) {
            boolean yes = containsModel(installed, spec.ollamaName());
            sb.append("- ").append(spec.id())
                    .append(" (").append(spec.ollamaName()).append(") ")
                    .append(spec.approxSize())
                    .append(yes ? " [downloaded]" : " [not downloaded]")
                    .append(" — ").append(spec.notes())
                    .append('\n');
        }
        sb.append("Use: /theseus local download <id>  then  /theseus local use <id>");
        return sb.toString().trim();
    }

    public static void startDownload(MinecraftServer server, ServerPlayer player, String rawId) {
        PlayerLlmSettings settings = PlayerConfigStore.get(player.getUUID());
        LocalModelSpec spec = LocalModelCatalog.resolve(rawId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown local model '" + rawId + "'. Try: "
                                + LocalModelCatalog.all().stream().map(LocalModelSpec::id).collect(Collectors.joining(", "))));

        if (!OllamaClient.isReachable(settings)) {
            throw new IllegalStateException("Ollama is offline at " + OllamaClient.apiBase(settings)
                    + ". Install from https://ollama.com and start it, then retry.");
        }
        if (!DOWNLOAD_BUSY.compareAndSet(false, true)) {
            throw new IllegalStateException("Another download is in progress: " + activeDownload());
        }
        ACTIVE_DOWNLOAD.set(spec.ollamaName());
        UUID playerId = player.getUUID();
        tell(server, playerId, "Starting download of " + spec.displayName()
                + " (" + spec.ollamaName() + ", " + spec.approxSize() + ")...");

        TheseusExecutors.workers().execute(() -> {
            try {
                final long[] lastNotify = {0L};
                final int[] lastPercent = {-1};
                OllamaClient.pull(spec.ollamaName(), settings, progress -> {
                    long now = System.currentTimeMillis();
                    int pct = progress.percent();
                    boolean milestone = pct >= 0 && (pct >= lastPercent[0] + 10 || pct == 100);
                    boolean timed = now - lastNotify[0] >= 5000L;
                    if (milestone || timed || "success".equalsIgnoreCase(progress.status())) {
                        lastNotify[0] = now;
                        if (pct >= 0) {
                            lastPercent[0] = pct;
                        }
                        String msg = pct >= 0
                                ? "Downloading " + spec.ollamaName() + ": " + progress.status() + " " + pct + "%"
                                : "Downloading " + spec.ollamaName() + ": " + progress.status();
                        tell(server, playerId, msg);
                    }
                });
                try {
                    SuggestedModels.updateOllamaInstalledCache(OllamaClient.listInstalledModelNames(settings));
                } catch (Exception ignored) {
                }
                tell(server, playerId, "Download complete: " + spec.ollamaName()
                        + ". Run /theseus local use " + spec.id());
            } catch (Exception e) {
                Theseus.LOGGER.error("Local model download failed", e);
                tell(server, playerId, "Download failed: " + e.getMessage());
            } finally {
                ACTIVE_DOWNLOAD.set(null);
                DOWNLOAD_BUSY.set(false);
            }
        });
    }

    public static String activate(ServerPlayer player, String rawId) throws Exception {
        PlayerLlmSettings settings = PlayerConfigStore.get(player.getUUID());
        LocalModelSpec spec = LocalModelCatalog.resolve(rawId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown local model: " + rawId));
        if (!OllamaClient.isReachable(settings)) {
            throw new IllegalStateException("Ollama is offline at " + OllamaClient.apiBase(settings));
        }
        if (!OllamaClient.isInstalled(spec.ollamaName(), settings)) {
            throw new IllegalStateException(spec.ollamaName()
                    + " is not downloaded. Run /theseus local download " + spec.id());
        }

        PlayerConfigStore.update(player.getUUID(), prefs -> {
            prefs.setProviderId("ollama");
            prefs.setModel(spec.ollamaName());
            if (!prefs.hasApiKey("ollama")) {
                prefs.setApiKey("ollama", "ollama");
            }
        });
        return "Active local model for you: provider=ollama model=" + spec.ollamaName()
                + " (" + spec.displayName() + "). You can /theseus chat now.";
    }

    private static boolean containsModel(List<String> installed, String ollamaName) {
        return installed.stream().anyMatch(n -> n.equalsIgnoreCase(ollamaName));
    }

    private static void tell(MinecraftServer server, UUID playerId, String message) {
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(playerId);
            if (p != null) {
                p.sendSystemMessage(Component.literal("[Theseus/Local] " + message));
            }
        });
    }
}
