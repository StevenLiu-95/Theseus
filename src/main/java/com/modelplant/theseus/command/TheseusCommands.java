package com.modelplant.theseus.command;

import com.modelplant.theseus.api.gate.GateContext;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.config.ApiKeyStore;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.gate.ToolGateEngine;
import com.modelplant.theseus.llm.LlmProviderRegistry;
import com.modelplant.theseus.llm.ProviderEndpoints;
import com.modelplant.theseus.llm.SuggestedModels;
import com.modelplant.theseus.local.LocalModelCatalog;
import com.modelplant.theseus.local.LocalModelService;
import com.modelplant.theseus.local.LocalModelSpec;
import com.modelplant.theseus.local.OllamaClient;
import com.modelplant.theseus.mcp.McpBridgeServer;
import com.modelplant.theseus.permission.TheseusPermissions;
import com.modelplant.theseus.runtime.ServerTaskQueue;
import com.modelplant.theseus.session.AiSession;
import com.modelplant.theseus.session.AiSessionManager;
import com.modelplant.theseus.session.ChatOrchestrator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class TheseusCommands {
    private static final SuggestionProvider<CommandSourceStack> LOCAL_MODEL_SUGGESTIONS = (ctx, builder) -> {
        List<String> names = new ArrayList<>();
        for (LocalModelSpec spec : LocalModelCatalog.all()) {
            names.add(spec.id());
            names.add(spec.ollamaName());
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> PROVIDER_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    LlmProviderRegistry.get().all().stream().map(LlmProvider::id).sorted().toList(),
                    builder
            );

    private static final SuggestionProvider<CommandSourceStack> CONFIG_MODEL_SUGGESTIONS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(SuggestedModels.forActiveProvider(), builder);

    private TheseusCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("theseus")
                .requires(src -> src.hasPermission(0))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("chat")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> chat(ctx.getSource(), StringArgumentType.getString(ctx, "message")))))
                .then(Commands.literal("say")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> chat(ctx.getSource(), StringArgumentType.getString(ctx, "message")))))
                .then(Commands.literal("session")
                        .then(Commands.literal("new").executes(ctx -> sessionNew(ctx.getSource())))
                        .then(Commands.literal("clear").executes(ctx -> sessionClear(ctx.getSource())))
                        .then(Commands.literal("status").executes(ctx -> sessionStatus(ctx.getSource()))))
                .then(Commands.literal("tools").executes(ctx -> listTools(ctx.getSource())))
                .then(Commands.literal("providers").executes(ctx -> listProviders(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("local")
                        .then(Commands.literal("list").executes(ctx -> localList(ctx.getSource())))
                        .then(Commands.literal("status").executes(ctx -> localStatus(ctx.getSource())))
                        .then(Commands.literal("download")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("model", StringArgumentType.greedyString())
                                        .suggests(LOCAL_MODEL_SUGGESTIONS)
                                        .executes(ctx -> localDownload(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "model")))))
                        .then(Commands.literal("use")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("model", StringArgumentType.greedyString())
                                        .suggests(LOCAL_MODEL_SUGGESTIONS)
                                        .executes(ctx -> localUse(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "model"))))))
                .then(Commands.literal("mcp")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("start").executes(ctx -> mcpStart(ctx.getSource())))
                        .then(Commands.literal("stop").executes(ctx -> mcpStop(ctx.getSource()))))
                .then(Commands.literal("config")
                        .then(Commands.literal("show").executes(ctx -> configShow(ctx.getSource())))
                        .then(Commands.literal("provider")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(PROVIDER_SUGGESTIONS)
                                        .executes(ctx -> setProvider(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("model")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .suggests(CONFIG_MODEL_SUGGESTIONS)
                                        .executes(ctx -> {
                                            TheseusServerConfig.MODEL.set(StringArgumentType.getString(ctx, "name"));
                                            ctx.getSource().sendSuccess(() -> Component.literal("Model set to " + TheseusServerConfig.MODEL.get()), true);
                                            return 1;
                                        })))
                        .then(Commands.literal("ollama")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> setProviderUrl(ctx.getSource(), "ollama",
                                                StringArgumentType.getString(ctx, "url")))))
                        .then(Commands.literal("url")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .suggests(PROVIDER_SUGGESTIONS)
                                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                                .executes(ctx -> setProviderUrl(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "provider"),
                                                        StringArgumentType.getString(ctx, "url"))))))
                        .then(Commands.literal("key")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .suggests(PROVIDER_SUGGESTIONS)
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> setKey(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "provider"),
                                                        StringArgumentType.getString(ctx, "value")))))));

        dispatcher.register(root);
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("""
                Theseus commands:
                /theseus chat|say <message>
                /theseus session new|clear|status
                /theseus tools
                /theseus providers
                /theseus status
                /theseus local list|status|download|use
                /theseus config show|provider|model|key|url|ollama
                /theseus mcp start|stop
                GUI: Ctrl+Alt+H
                """.trim()), false);
        return 1;
    }

    private static int chat(CommandSourceStack source, String message) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only"));
            return 0;
        }
        if (!TheseusPermissions.canUseTheseus(player)) {
            source.sendFailure(Component.literal("No permission"));
            return 0;
        }
        ChatOrchestrator.handlePlayerChat(player, message);
        return 1;
    }

    private static int sessionNew(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        AiSession session = AiSessionManager.get().renew(player);
        source.sendSuccess(() -> Component.literal("New session " + session.id()), false);
        return 1;
    }

    private static int sessionClear(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        AiSessionManager.get().clear(player.getUUID());
        source.sendSuccess(() -> Component.literal("Session history cleared"), false);
        return 1;
    }

    private static int sessionStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        AiSession session = AiSessionManager.get().getOrCreate(player);
        source.sendSuccess(() -> Component.literal("Session " + session.id() + " messages=" + session.historyView().size()
                + " busy=" + session.busy()), false);
        return 1;
    }

    private static int listTools(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only"));
            return 0;
        }
        AiSession session = AiSessionManager.get().getOrCreate(player);
        LlmProvider provider = LlmProviderRegistry.get().get(TheseusServerConfig.PROVIDER_ID.get()).orElse(null);
        GateContext gate = new GateContext(
                player,
                session.id(),
                TheseusServerConfig.enabledCapabilities(),
                TheseusPermissions.isOperator(player),
                provider != null && provider.supportsVision(),
                player.level().dimension(),
                player.gameMode.getGameModeForPlayer()
        );
        String list = ToolGateEngine.get().filter(gate).stream()
                .map(t -> t.name() + " [" + t.capability() + "]")
                .sorted()
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Visible tools: " + (list.isBlank() ? "(none)" : list)), false);
        return 1;
    }

    private static int listProviders(CommandSourceStack source) {
        String list = LlmProviderRegistry.get().all().stream()
                .map(p -> p.id() + "=" + p.displayName())
                .sorted()
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("Providers: " + list), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("provider=" + TheseusServerConfig.PROVIDER_ID.get()
                + " model=" + TheseusServerConfig.MODEL.get()
                + " url=" + ProviderEndpoints.resolveActive()
                + " queue=" + ServerTaskQueue.get().pending()
                + " mcp=" + TheseusServerConfig.MCP_ENABLED.getAsBoolean()
                + " ollama=" + (OllamaClient.isReachable() ? "online" : "offline")), false);
        return 1;
    }

    private static int localList(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(LocalModelService.catalogListing()), false);
        return 1;
    }

    private static int localStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(LocalModelService.statusSummary()), false);
        return 1;
    }

    private static int localDownload(CommandSourceStack source, String model) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only"));
            return 0;
        }
        try {
            LocalModelService.startDownload(source.getServer(), player, model);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int localUse(CommandSourceStack source, String model) {
        try {
            String msg = LocalModelService.activate(model);
            source.sendSuccess(() -> Component.literal(msg), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int configShow(CommandSourceStack source) {
        String provider = TheseusServerConfig.PROVIDER_ID.get();
        source.sendSuccess(() -> Component.literal("provider=" + provider
                + " model=" + TheseusServerConfig.MODEL.get()
                + " keySet=" + ApiKeyStore.hasKey(provider)
                + " activeUrl=" + ProviderEndpoints.resolveActive()
                + " openai=" + TheseusServerConfig.OPENAI_BASE_URL.get()
                + " deepseek=" + TheseusServerConfig.DEEPSEEK_BASE_URL.get()
                + " qwen=" + TheseusServerConfig.QWEN_BASE_URL.get()
                + " anthropic=" + TheseusServerConfig.ANTHROPIC_BASE_URL.get()
                + " gemini=" + TheseusServerConfig.GEMINI_BASE_URL.get()
                + " ollama=" + TheseusServerConfig.OLLAMA_BASE_URL.get()
                + " caps=" + String.join(",", TheseusServerConfig.enabledCapabilities())), false);
        return 1;
    }

    private static int setProviderUrl(CommandSourceStack source, String provider, String url) {
        String providerId = ProviderEndpoints.normalize(provider);
        if (!ProviderEndpoints.setUrl(providerId, url)) {
            source.sendFailure(Component.literal("Provider '" + providerId
                    + "' has no configurable URL (mock/unknown)."));
            return 0;
        }
        String resolved = ProviderEndpoints.resolve(providerId);
        source.sendSuccess(() -> Component.literal("URL for " + providerId + " set to " + resolved), true);
        return 1;
    }

    private static int setProvider(CommandSourceStack source, String id) {
        final String providerId = id.toLowerCase(Locale.ROOT);
        if (LlmProviderRegistry.get().get(providerId).isEmpty()) {
            source.sendFailure(Component.literal("Unknown provider: " + providerId));
            return 0;
        }
        TheseusServerConfig.PROVIDER_ID.set(providerId);
        String url = ProviderEndpoints.resolve(providerId);
        List<String> models = SuggestedModels.forProvider(providerId);
        String modelHint = models.isEmpty()
                ? ""
                : " | models: " + String.join(", ", models.stream().limit(6).toList());
        source.sendSuccess(() -> Component.literal(
                "Provider set to " + providerId + " | url=" + url + modelHint), true);
        return 1;
    }

    private static int setKey(CommandSourceStack source, String provider, String value) {
        ApiKeyStore.set(provider.toLowerCase(Locale.ROOT), value);
        source.sendSuccess(() -> Component.literal("API key stored for " + provider), true);
        return 1;
    }

    private static int mcpStart(CommandSourceStack source) {
        TheseusServerConfig.MCP_ENABLED.set(true);
        McpBridgeServer.startIfEnabled(source.getServer());
        source.sendSuccess(() -> Component.literal("MCP bridge start requested on "
                + TheseusServerConfig.MCP_BIND_HOST.get() + ":" + TheseusServerConfig.MCP_PORT.getAsInt()), true);
        return 1;
    }

    private static int mcpStop(CommandSourceStack source) {
        TheseusServerConfig.MCP_ENABLED.set(false);
        McpBridgeServer.stop();
        source.sendSuccess(() -> Component.literal("MCP bridge stopped"), true);
        return 1;
    }
}
