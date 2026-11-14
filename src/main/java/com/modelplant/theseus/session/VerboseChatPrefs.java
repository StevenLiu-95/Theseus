package com.modelplant.theseus.session;

import com.modelplant.theseus.config.PlayerConfigStore;
import com.modelplant.theseus.config.PlayerLlmSettings;
import com.modelplant.theseus.config.TheseusServerConfig;

import java.util.UUID;

/**
 * Per-player verbose chat preference, persisted in {@link PlayerConfigStore}.
 * {@code null} override means use the server default.
 */
public final class VerboseChatPrefs {
    private VerboseChatPrefs() {
    }

    public static boolean isEnabled(UUID playerId) {
        PlayerLlmSettings settings = PlayerConfigStore.getOrCreate(playerId);
        Boolean override = settings.verboseOverride();
        if (override != null) {
            return override;
        }
        return TheseusServerConfig.VERBOSE_CHAT.getAsBoolean();
    }

    public static void set(UUID playerId, boolean enabled) {
        PlayerConfigStore.update(playerId, s -> s.setVerboseOverride(enabled));
    }

    public static void clear(UUID playerId) {
        PlayerConfigStore.update(playerId, s -> s.setVerboseOverride(null));
    }

    public static void clearAll() {
        // Persisted per player — nothing to clear globally on shutdown.
    }

    /** {@code true}/{@code false}/default depending on whether an override is set. */
    public static String describe(UUID playerId) {
        PlayerLlmSettings settings = PlayerConfigStore.getOrCreate(playerId);
        Boolean override = settings.verboseOverride();
        boolean effective = isEnabled(playerId);
        if (override == null) {
            return "default(" + TheseusServerConfig.VERBOSE_CHAT.getAsBoolean() + ") -> " + effective;
        }
        return "override(" + override + ") -> " + effective;
    }
}
