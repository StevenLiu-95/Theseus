package com.modelplant.theseus.config;

import java.util.UUID;

/**
 * Legacy facade. API keys are per-player in {@link PlayerConfigStore}.
 * Kept so older call sites / docs can migrate; prefer {@link PlayerConfigStore} directly.
 */
@Deprecated
public final class ApiKeyStore {
    private ApiKeyStore() {
    }

    public static void init() {
        PlayerConfigStore.init();
    }

    public static String get(UUID playerId, String providerId) {
        return PlayerConfigStore.getApiKey(playerId, providerId);
    }

    public static boolean hasKey(UUID playerId, String providerId) {
        return PlayerConfigStore.hasApiKey(playerId, providerId);
    }

    public static void set(UUID playerId, String providerId, String apiKey) {
        PlayerConfigStore.update(playerId, s -> s.setApiKey(providerId, apiKey));
    }
}
