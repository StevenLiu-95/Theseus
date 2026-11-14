package com.modelplant.theseus.bootstrap;

import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.api.event.RegisterTheseusProvidersEvent;
import com.modelplant.theseus.api.event.RegisterTheseusToolsEvent;
import com.modelplant.theseus.command.TheseusCommands;
import com.modelplant.theseus.config.PlayerConfigStore;
import com.modelplant.theseus.control.EntityPossessionManager;
import com.modelplant.theseus.control.PlayerController;
import com.modelplant.theseus.gate.BuiltinGateRules;
import com.modelplant.theseus.llm.AnthropicProvider;
import com.modelplant.theseus.llm.GeminiProvider;
import com.modelplant.theseus.llm.LlmProviderRegistry;
import com.modelplant.theseus.llm.MockLlmProvider;
import com.modelplant.theseus.llm.OpenAiCompatibleProvider;
import com.modelplant.theseus.mcp.McpBridgeServer;
import com.modelplant.theseus.runtime.ServerTaskQueue;
import com.modelplant.theseus.runtime.TheseusExecutors;
import com.modelplant.theseus.session.AiSessionManager;
import com.modelplant.theseus.session.ThinkingIndicator;
import com.modelplant.theseus.session.VerboseChatPrefs;
import com.modelplant.theseus.tool.TheseusToolRegistry;
import com.modelplant.theseus.tool.builtin.BuiltinTools;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class TheseusBootstrap {
    private static boolean initialized;

    private TheseusBootstrap() {
    }

    public static void initCommon() {
        if (initialized) {
            return;
        }
        initialized = true;
        PlayerConfigStore.init();
        TheseusExecutors.init();
        BuiltinGateRules.registerDefaults();

        TheseusToolRegistry.get().clear();
        BuiltinTools.registerAll();
        NeoForge.EVENT_BUS.post(new RegisterTheseusToolsEvent(TheseusToolRegistry.get()));

        LlmProviderRegistry registry = LlmProviderRegistry.get();
        registry.register(new MockLlmProvider());
        registry.register(new OpenAiCompatibleProvider("ollama", "Ollama (local)", false));
        registry.register(new OpenAiCompatibleProvider("openai", "OpenAI Compatible", true));
        registry.register(new OpenAiCompatibleProvider("deepseek", "DeepSeek", false));
        registry.register(new OpenAiCompatibleProvider("qwen", "Qwen / DashScope", true));
        registry.register(new AnthropicProvider());
        registry.register(new GeminiProvider());
        NeoForge.EVENT_BUS.post(new RegisterTheseusProvidersEvent(registry));

        Theseus.LOGGER.info("Theseus initialized (tools={}, providers={})",
                TheseusToolRegistry.get().all().size(),
                registry.all().size());
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        TheseusCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        ServerTaskQueue.get().bind(event.getServer());
        McpBridgeServer.startIfEnabled(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        McpBridgeServer.stop();
        ServerTaskQueue.get().unbind();
        ThinkingIndicator.clearAll();
        VerboseChatPrefs.clearAll();
        AiSessionManager.get().clearAll();
        TheseusExecutors.shutdown();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerTaskQueue.get().onServerTick(event);
        PlayerController.get().onServerTick(event);
        EntityPossessionManager.get().onServerTick(event);
        ThinkingIndicator.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() != null) {
            PlayerConfigStore.maybeMigrateLegacyKeys(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            ThinkingIndicator.clear(event.getEntity().getUUID());
            AiSessionManager.get().remove(event.getEntity().getUUID());
        }
    }
}
