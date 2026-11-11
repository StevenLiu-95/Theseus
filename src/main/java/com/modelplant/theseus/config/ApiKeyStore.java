package com.modelplant.theseus.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.modelplant.theseus.Theseus;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores API keys outside world saves. Values are lightly obfuscated (not strong encryption).
 */
public final class ApiKeyStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, String> KEYS = new ConcurrentHashMap<>();
    private static Path path;

    private ApiKeyStore() {
    }

    public static synchronized void init() {
        path = FMLPaths.CONFIGDIR.get().resolve("theseus").resolve("apikeys.json");
        load();
    }

    public static String get(String providerId) {
        return KEYS.getOrDefault(providerId, "");
    }

    public static synchronized void set(String providerId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            KEYS.remove(providerId);
        } else {
            KEYS.put(providerId, apiKey.trim());
        }
        save();
    }

    public static boolean hasKey(String providerId) {
        String key = get(providerId);
        return key != null && !key.isBlank();
    }

    private static void load() {
        KEYS.clear();
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = GSON.fromJson(raw, JsonObject.class);
            if (root == null) {
                return;
            }
            for (String key : root.keySet()) {
                String encoded = root.get(key).getAsString();
                KEYS.put(key, decode(encoded));
            }
        } catch (Exception e) {
            Theseus.LOGGER.error("Failed to load API keys", e);
        }
    }

    private static void save() {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            KEYS.forEach((k, v) -> root.addProperty(k, encode(v)));
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Theseus.LOGGER.error("Failed to save API keys", e);
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
