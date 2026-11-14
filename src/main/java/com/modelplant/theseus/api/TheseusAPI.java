package com.modelplant.theseus.api;

import com.modelplant.theseus.api.gate.GateRule;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.api.tool.ToolRegistry;
import com.modelplant.theseus.config.PlayerConfigStore;
import com.modelplant.theseus.config.PlayerLlmSettings;
import com.modelplant.theseus.gate.ToolGateEngine;
import com.modelplant.theseus.llm.LlmProviderRegistry;
import com.modelplant.theseus.runtime.ServerTaskQueue;
import com.modelplant.theseus.session.AiSessionManager;
import com.modelplant.theseus.tool.TheseusToolRegistry;

import java.util.UUID;

/**
 * Stable entry point for other mods integrating with Theseus.
 */
public final class TheseusAPI {
    private TheseusAPI() {
    }

    public static ToolRegistry tools() {
        return TheseusToolRegistry.get();
    }

    public static void registerTool(Tool tool) {
        TheseusToolRegistry.get().register(tool);
    }

    public static void registerProvider(LlmProvider provider) {
        LlmProviderRegistry.get().register(provider);
    }

    public static void registerGateRule(GateRule rule) {
        ToolGateEngine.get().addRule(rule);
    }

    public static AiSessionManager sessions() {
        return AiSessionManager.get();
    }

    public static ServerTaskQueue taskQueue() {
        return ServerTaskQueue.get();
    }

    /** Snapshot of a player's LLM preferences (provider, model, URLs, iterations, …). */
    public static PlayerLlmSettings playerSettings(UUID playerId) {
        return PlayerConfigStore.get(playerId);
    }

    /** Whether the player has a non-blank API key for the provider (does not return the key). */
    public static boolean playerHasApiKey(UUID playerId, String providerId) {
        return PlayerConfigStore.hasApiKey(playerId, providerId);
    }

    /**
     * Read-only access to a player's API key for trusted extension mods.
     * Prefer not to log or persist the returned value.
     */
    public static String playerApiKey(UUID playerId, String providerId) {
        return PlayerConfigStore.getApiKey(playerId, providerId);
    }
}
