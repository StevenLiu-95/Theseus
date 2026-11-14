package com.modelplant.theseus.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.modelplant.theseus.Theseus;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-player LLM settings + API keys under {@code config/theseus/players/<uuid>.json}.
 * Survives world switches; isolated between players.
 */
public final class PlayerConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, PlayerLlmSettings> CACHE = new ConcurrentHashMap<>();
    private static final Object MIGRATE_LOCK = new Object();
    private static Path playersDir;
    private static boolean legacyKeysLoaded;
    private static JsonObject legacyKeys;

    private PlayerConfigStore() {
    }

    public static synchronized void init() {
        playersDir = FMLPaths.CONFIGDIR.get().resolve("theseus").resolve("players");
        try {
            Files.createDirectories(playersDir);
        } catch (IOException e) {
            Theseus.LOGGER.error("Failed to create player config dir", e);
        }
        loadLegacyKeysIfNeeded();
    }

    public static PlayerLlmSettings get(UUID playerId) {
        return getOrCreate(playerId).copy();
    }

    public static PlayerLlmSettings getOrCreate(UUID playerId) {
        return CACHE.computeIfAbsent(playerId, PlayerConfigStore::loadOrCreate);
    }

    /**
     * Mutate settings and persist. {@code mutator} receives the live cached instance.
     */
    public static synchronized PlayerLlmSettings update(UUID playerId, Consumer<PlayerLlmSettings> mutator) {
        PlayerLlmSettings settings = getOrCreate(playerId);
        mutator.accept(settings);
        save(playerId, settings);
        return settings.copy();
    }

    public static String getApiKey(UUID playerId, String providerId) {
        return getOrCreate(playerId).apiKey(providerId);
    }

    public static boolean hasApiKey(UUID playerId, String providerId) {
        return getOrCreate(playerId).hasApiKey(providerId);
    }

    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * One-time: copy legacy global {@code apikeys.json} into this player's file if they have no keys yet.
     */
    public static void maybeMigrateLegacyKeys(UUID playerId) {
        loadLegacyKeysIfNeeded();
        if (legacyKeys == null || legacyKeys.keySet().isEmpty()) {
            return;
        }
        PlayerLlmSettings settings = getOrCreate(playerId);
        boolean anyKey = settings.mutableApiKeys().values().stream().anyMatch(v -> v != null && !v.isBlank());
        if (anyKey) {
            return;
        }
        synchronized (MIGRATE_LOCK) {
            settings = getOrCreate(playerId);
            anyKey = settings.mutableApiKeys().values().stream().anyMatch(v -> v != null && !v.isBlank());
            if (anyKey || legacyKeys == null) {
                return;
            }
            for (String provider : legacyKeys.keySet()) {
                String encoded = legacyKeys.get(provider).getAsString();
                settings.setApiKey(provider, decode(encoded));
            }
            save(playerId, settings);
            Theseus.LOGGER.info("Migrated legacy API keys into player {}", playerId);
        }
    }

    private static PlayerLlmSettings loadOrCreate(UUID playerId) {
        Path path = fileFor(playerId);
        if (path != null && Files.exists(path)) {
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                JsonObject root = GSON.fromJson(raw, JsonObject.class);
                if (root != null) {
                    return fromJson(root);
                }
            } catch (Exception e) {
                Theseus.LOGGER.error("Failed to load player LLM config for {}", playerId, e);
            }
        }
        PlayerLlmSettings created = PlayerLlmSettings.defaultsFromServer();
        save(playerId, created);
        return created;
    }

    private static void save(UUID playerId, PlayerLlmSettings settings) {
        Path path = fileFor(playerId);
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(toJson(settings)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Theseus.LOGGER.error("Failed to save player LLM config for {}", playerId, e);
        }
    }

    private static Path fileFor(UUID playerId) {
        if (playersDir == null) {
            init();
        }
        return playersDir.resolve(playerId.toString() + ".json");
    }

    private static JsonObject toJson(PlayerLlmSettings s) {
        JsonObject root = new JsonObject();
        root.addProperty("providerId", s.providerId());
        root.addProperty("model", s.model());
        root.addProperty("maxToolIterations", s.maxToolIterations());
        root.addProperty("temperature", s.temperature());
        root.addProperty("maxTokens", s.maxTokens());
        if (s.verboseOverride() != null) {
            root.addProperty("verboseOverride", s.verboseOverride());
        }
        JsonObject urls = new JsonObject();
        s.urlOverridesView().forEach(urls::addProperty);
        root.add("urlOverrides", urls);
        JsonObject keys = new JsonObject();
        s.mutableApiKeys().forEach((k, v) -> keys.addProperty(k, encode(v)));
        root.add("apiKeys", keys);
        return root;
    }

    private static PlayerLlmSettings fromJson(JsonObject root) {
        PlayerLlmSettings s = PlayerLlmSettings.defaultsFromServer();
        if (root.has("providerId")) {
            s.setProviderId(root.get("providerId").getAsString());
        }
        if (root.has("model")) {
            s.setModel(root.get("model").getAsString());
        }
        if (root.has("maxToolIterations")) {
            s.setMaxToolIterations(root.get("maxToolIterations").getAsInt());
        }
        if (root.has("temperature")) {
            s.setTemperature(root.get("temperature").getAsDouble());
        }
        if (root.has("maxTokens")) {
            s.setMaxTokens(root.get("maxTokens").getAsInt());
        }
        if (root.has("verboseOverride") && !root.get("verboseOverride").isJsonNull()) {
            s.setVerboseOverride(root.get("verboseOverride").getAsBoolean());
        } else {
            s.setVerboseOverride(null);
        }
        if (root.has("urlOverrides") && root.get("urlOverrides").isJsonObject()) {
            JsonObject urls = root.getAsJsonObject("urlOverrides");
            for (Map.Entry<String, JsonElement> e : urls.entrySet()) {
                s.setUrlOverride(e.getKey(), e.getValue().getAsString());
            }
        }
        if (root.has("apiKeys") && root.get("apiKeys").isJsonObject()) {
            JsonObject keys = root.getAsJsonObject("apiKeys");
            for (Map.Entry<String, JsonElement> e : keys.entrySet()) {
                s.setApiKey(e.getKey(), decode(e.getValue().getAsString()));
            }
        }
        return s;
    }

    private static void loadLegacyKeysIfNeeded() {
        if (legacyKeysLoaded) {
            return;
        }
        legacyKeysLoaded = true;
        Path legacy = FMLPaths.CONFIGDIR.get().resolve("theseus").resolve("apikeys.json");
        if (!Files.exists(legacy)) {
            return;
        }
        try {
            String raw = Files.readString(legacy, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(raw, JsonObject.class);
            if (root != null && !root.keySet().isEmpty()) {
                legacyKeys = root;
            }
        } catch (Exception e) {
            Theseus.LOGGER.warn("Could not read legacy apikeys.json", e);
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }
}
