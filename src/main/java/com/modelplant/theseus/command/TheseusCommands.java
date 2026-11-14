package com.modelplant.theseus.command;

import com.modelplant.theseus.api.gate.GateContext;
import com.modelplant.theseus.api.llm.LlmProvider;
import com.modelplant.theseus.config.PlayerConfigStore;
import com.modelplant.theseus.config.PlayerLlmSettings;
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
import com.modelplant.theseus.session.VerboseChatPrefs;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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

    private static final SuggestionProvider<CommandSourceStack> CONFIG_MODEL_SUGGESTIONS = (ctx, builder) -> {
        ServerPlayer player = ctx.getSource().getPlayer();
        String provider = player != null
                ? PlayerConfigStore.getOrCreate(player.getUUID()).providerId()
                : TheseusServerConfig.DEFAULT_PROVIDER_ID.get();
        return SharedSuggestionProvider.suggest(SuggestedModels.forProvider(provider), builder);
    };

    private static final SuggestionProvider<CommandSourceStack> CONFIG_API_MODEL_SUGGESTIONS = (ctx, builder) -> {
        String provider = "mock";
        try {
            provider = StringArgumentType.getString(ctx, "provider");
        } catch (IllegalArgumentException ignored) {
        }
        return SharedSuggestionProvider.suggest(SuggestedModels.forProvider(provider), builder);
    };

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
                .then(Commands.literal("cancel").executes(ctx -> cancelChat(ctx.getSource())))
                .then(Commands.literal("stop").executes(ctx -> cancelChat(ctx.getSource())))
                .then(Commands.literal("session")
                        .then(Commands.literal("new").executes(ctx -> sessionNew(ctx.getSource())))
                        .then(Commands.literal("clear").executes(ctx -> sessionClear(ctx.getSource())))
                        .then(Commands.literal("status").executes(ctx -> sessionStatus(ctx.getSource()))))
                .then(Commands.literal("tools").executes(ctx -> listTools(ctx.getSource())))
                .then(Commands.literal("providers").executes(ctx -> listProviders(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("verbose")
                        .executes(ctx -> verboseStatus(ctx.getSource()))
                        .then(Commands.literal("on").executes(ctx -> setVerbose(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setVerbose(ctx.getSource(), false)))
                        .then(Commands.literal("default").executes(ctx -> clearVerbose(ctx.getSource()))))
                .then(Commands.literal("local")
                        .then(Commands.literal("list").executes(ctx -> localList(ctx.getSource())))
                        .then(Commands.literal("status").executes(ctx -> localStatus(ctx.getSource())))
                        .then(Commands.literal("download")
                                .then(Commands.argument("model", StringArgumentType.greedyString())
                                        .suggests(LOCAL_MODEL_SUGGESTIONS)
                                        .executes(ctx -> localDownload(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "model")))))
                        .then(Commands.literal("use")
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
                        .then(Commands.literal("api")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .suggests(PROVIDER_SUGGESTIONS)
                                        .then(Commands.argument("model_and_key", StringArgumentType.greedyString())
                                                .suggests(CONFIG_API_MODEL_SUGGESTIONS)
                                                .executes(ctx -> setApiFromArgs(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "provider"),
                                                        StringArgumentType.getString(ctx, "model_and_key"))))))
                        .then(Commands.literal("provider")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests(PROVIDER_SUGGESTIONS)
                                        .executes(ctx -> setProvider(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("model")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .suggests(CONFIG_MODEL_SUGGESTIONS)
                                        .executes(ctx -> setModel(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))
                        .then(Commands.literal("ollama")
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> setProviderUrl(ctx.getSource(), "ollama",
                                                StringArgumentType.getString(ctx, "url")))))
                        .then(Commands.literal("url")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .suggests(PROVIDER_SUGGESTIONS)
                                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                                .executes(ctx -> setProviderUrl(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "provider"),
                                                        StringArgumentType.getString(ctx, "url"))))))
                        .then(Commands.literal("key")
                                .then(Commands.argument("provider", StringArgumentType.word())
                                        .suggests(PROVIDER_SUGGESTIONS)
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> setKey(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "provider"),
                                                        StringArgumentType.getString(ctx, "value"))))))
                        .then(Commands.literal("iterations")
                                .executes(ctx -> showIterations(ctx.getSource()))
                                .then(Commands.argument("value", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> setIterations(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "value")))))
                        .then(Commands.literal("verbose")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> showVerboseDefault(ctx.getSource()))
                                .then(Commands.literal("true").executes(ctx -> setVerboseDefault(ctx.getSource(), true)))
                                .then(Commands.literal("false").executes(ctx -> setVerboseDefault(ctx.getSource(), false)))));

        dispatcher.register(root);
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("""
                Theseus commands:
                /theseus chat|say <message>
                /theseus cancel|stop
                /theseus session new|clear|status
                /theseus tools
                /theseus providers
                /theseus status
                /theseus verbose [on|off|default]
                /theseus local list|status|download|use
                /theseus config show|api|provider|model|key|url|ollama|iterations
                /theseus config api <provider> <model> <key>
                /theseus config verbose (OP: server default)
                /theseus mcp start|stop
                Config (provider/model/key/url/iterations) is per-player and persists across worlds.
                GUI: Ctrl+Alt+H
                """.trim()), false);
        return 1;
    }

    private static ServerPlayer requireTheseusPlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only"));
            return null;
        }
        if (!TheseusPermissions.canUseTheseus(player)) {
            source.sendFailure(Component.literal("No permission"));
            return null;
        }
        PlayerConfigStore.maybeMigrateLegacyKeys(player.getUUID());
        return player;
    }

    private static int chat(CommandSourceStack source, String message) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        ChatOrchestrator.handlePlayerChat(player, message);
        return 1;
    }

    private static int cancelChat(CommandSourceStack source) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        if (ChatOrchestrator.cancelPlayerChat(player)) {
            source.sendSuccess(() -> Component.literal("Cancelling current Theseus reply..."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("No active Theseus reply to cancel."), false);
        return 0;
    }

    private static int sessionNew(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        ChatOrchestrator.cancelPlayerChat(player);
        AiSession session = AiSessionManager.get().renew(player);
        source.sendSuccess(() -> Component.literal("New session " + session.id()), false);
        return 1;
    }

    private static int sessionClear(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        ChatOrchestrator.cancelPlayerChat(player);
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
        PlayerLlmSettings prefs = PlayerConfigStore.getOrCreate(player.getUUID());
        LlmProvider provider = LlmProviderRegistry.get().get(prefs.providerId()).orElse(null);
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
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            PlayerLlmSettings prefs = PlayerConfigStore.getOrCreate(player.getUUID());
            source.sendSuccess(() -> Component.literal("provider=" + prefs.providerId()
                    + " model=" + prefs.model()
                    + " url=" + ProviderEndpoints.resolveActive(prefs)
                    + " iterations=" + prefs.maxToolIterations()
                    + " keySet=" + prefs.hasApiKey(prefs.providerId())
                    + " queue=" + ServerTaskQueue.get().pending()
                    + " mcp=" + TheseusServerConfig.MCP_ENABLED.getAsBoolean()
                    + " verbose=" + VerboseChatPrefs.describe(player.getUUID())
                    + " ollama=" + (OllamaClient.isReachable(prefs) ? "online" : "offline")), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("queue=" + ServerTaskQueue.get().pending()
                + " mcp=" + TheseusServerConfig.MCP_ENABLED.getAsBoolean()
                + " verboseDefault=" + TheseusServerConfig.VERBOSE_CHAT.getAsBoolean()), false);
        return 1;
    }

    private static int verboseStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only (or use /theseus config verbose)"));
            return 0;
        }
        boolean on = VerboseChatPrefs.isEnabled(player.getUUID());
        source.sendSuccess(() -> Component.literal(
                "verbose=" + on + " (" + VerboseChatPrefs.describe(player.getUUID())
                        + "). Shows reasoning, tool calls, model and tokens when on."), false);
        return 1;
    }

    private static int setVerbose(CommandSourceStack source, boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only"));
            return 0;
        }
        VerboseChatPrefs.set(player.getUUID(), enabled);
        source.sendSuccess(() -> Component.literal("Verbose chat " + (enabled ? "ON" : "OFF")
                + " for you. Reasoning / tools / model / tokens will "
                + (enabled ? "appear" : "be hidden") + " in chat."), false);
        return 1;
    }

    private static int clearVerbose(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Players only"));
            return 0;
        }
        VerboseChatPrefs.clear(player.getUUID());
        source.sendSuccess(() -> Component.literal(
                "Verbose chat reset to server default ("
                        + TheseusServerConfig.VERBOSE_CHAT.getAsBoolean() + ")."), false);
        return 1;
    }

    private static int showVerboseDefault(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "verboseChat(server default)=" + TheseusServerConfig.VERBOSE_CHAT.getAsBoolean()
                        + " — players override with /theseus verbose on|off|default"), false);
        return 1;
    }

    private static int setVerboseDefault(CommandSourceStack source, boolean enabled) {
        TheseusServerConfig.VERBOSE_CHAT.set(enabled);
        TheseusServerConfig.save();
        source.sendSuccess(() -> Component.literal("Server verboseChat default set to " + enabled), true);
        return 1;
    }

    private static int localList(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        PlayerLlmSettings prefs = player != null ? PlayerConfigStore.get(player.getUUID()) : null;
        source.sendSuccess(() -> Component.literal(LocalModelService.catalogListing(prefs)), false);
        return 1;
    }

    private static int localStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        PlayerLlmSettings prefs = player != null ? PlayerConfigStore.get(player.getUUID()) : null;
        source.sendSuccess(() -> Component.literal(LocalModelService.statusSummary(prefs)), false);
        return 1;
    }

    private static int localDownload(CommandSourceStack source, String model) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
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
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        try {
            String msg = LocalModelService.activate(player, model);
            source.sendSuccess(() -> Component.literal(msg), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int configShow(CommandSourceStack source) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        PlayerLlmSettings prefs = PlayerConfigStore.get(player.getUUID());
        String provider = prefs.providerId();
        StringBuilder urls = new StringBuilder();
        for (String id : List.of("openai", "deepseek", "qwen", "anthropic", "gemini", "ollama")) {
            String resolved = ProviderEndpoints.resolve(id, prefs);
            String ov = prefs.urlOverride(id);
            urls.append(' ').append(id).append('=').append(resolved)
                    .append(ov != null ? "(custom)" : "(default)");
        }
        source.sendSuccess(() -> Component.literal("Your LLM config: provider=" + provider
                + " model=" + prefs.model()
                + " keySet=" + prefs.hasApiKey(provider)
                + " activeUrl=" + ProviderEndpoints.resolveActive(prefs)
                + " maxToolIterations=" + prefs.maxToolIterations()
                + " temperature=" + prefs.temperature()
                + " maxTokens=" + prefs.maxTokens()
                + " verbose=" + VerboseChatPrefs.describe(player.getUUID())
                + " urls:" + urls
                + " | server caps=" + String.join(",", TheseusServerConfig.enabledCapabilities())), false);
        return 1;
    }

    private static int showIterations(CommandSourceStack source) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        PlayerLlmSettings prefs = PlayerConfigStore.get(player.getUUID());
        source.sendSuccess(() -> Component.literal(
                "maxToolIterations=" + prefs.maxToolIterations()
                        + " (range 1-256, per-player)"), false);
        return 1;
    }

    private static int setIterations(CommandSourceStack source, int value) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        PlayerConfigStore.update(player.getUUID(), s -> s.setMaxToolIterations(value));
        source.sendSuccess(() -> Component.literal("Your maxToolIterations set to " + value), true);
        return 1;
    }

    private static int setProviderUrl(CommandSourceStack source, String provider, String url) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        String providerId = ProviderEndpoints.normalize(provider);
        if (ProviderEndpoints.configurableProvidersHint(providerId).isEmpty()) {
            source.sendFailure(Component.literal("Provider '" + providerId
                    + "' has no configurable URL (mock/unknown)."));
            return 0;
        }
        PlayerLlmSettings updated = PlayerConfigStore.update(player.getUUID(),
                s -> ProviderEndpoints.setUrl(s, providerId, url));
        String resolved = ProviderEndpoints.resolve(providerId, updated);
        source.sendSuccess(() -> Component.literal("Your URL for " + providerId + " set to " + resolved), true);
        return 1;
    }

    private static int setProvider(CommandSourceStack source, String id) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        final String providerId = id.toLowerCase(Locale.ROOT);
        if (LlmProviderRegistry.get().get(providerId).isEmpty()) {
            source.sendFailure(Component.literal("Unknown provider: " + providerId));
            return 0;
        }
        PlayerLlmSettings updated = PlayerConfigStore.update(player.getUUID(), s -> s.setProviderId(providerId));
        String url = ProviderEndpoints.resolve(providerId, updated);
        List<String> models = SuggestedModels.forProvider(providerId);
        String modelHint = models.isEmpty()
                ? ""
                : " | models: " + String.join(", ", models.stream().limit(6).toList());
        source.sendSuccess(() -> Component.literal(
                "Your provider set to " + providerId + " | url=" + url + modelHint), true);
        return 1;
    }

    private static int setModel(CommandSourceStack source, String name) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        PlayerLlmSettings updated = PlayerConfigStore.update(player.getUUID(), s -> s.setModel(name));
        source.sendSuccess(() -> Component.literal("Your model set to " + updated.model()), true);
        return 1;
    }

    private static int setApiFromArgs(CommandSourceStack source, String provider, String modelAndKey) {
        String raw = modelAndKey == null ? "" : modelAndKey.trim();
        if (raw.isBlank()) {
            source.sendFailure(Component.literal("Usage: /theseus config api <provider> <model> <key>"));
            return 0;
        }
        int split = raw.indexOf(' ');
        if (split <= 0 || split >= raw.length() - 1) {
            source.sendFailure(Component.literal("Usage: /theseus config api <provider> <model> <key>"));
            return 0;
        }
        String model = raw.substring(0, split).trim();
        String key = raw.substring(split + 1).trim();
        return setApi(source, provider, model, key);
    }

    private static int setApi(CommandSourceStack source, String provider, String model, String key) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        final String providerId = provider.toLowerCase(Locale.ROOT);
        if (LlmProviderRegistry.get().get(providerId).isEmpty()) {
            source.sendFailure(Component.literal("Unknown provider: " + providerId));
            return 0;
        }
        if (model == null || model.isBlank()) {
            source.sendFailure(Component.literal("Model name required"));
            return 0;
        }
        if (key == null || key.isBlank()) {
            source.sendFailure(Component.literal("API key required"));
            return 0;
        }

        PlayerLlmSettings updated = PlayerConfigStore.update(player.getUUID(), s -> {
            s.setProviderId(providerId);
            s.setModel(model.trim());
            s.setApiKey(providerId, key.trim());
        });

        String url = ProviderEndpoints.resolve(providerId, updated);
        source.sendSuccess(() -> Component.literal(
                "Your API configured: provider=" + providerId
                        + " model=" + updated.model()
                        + " key=set"
                        + " url=" + url), true);
        return 1;
    }

    private static int setKey(CommandSourceStack source, String provider, String value) {
        ServerPlayer player = requireTheseusPlayer(source);
        if (player == null) {
            return 0;
        }
        String providerId = provider.toLowerCase(Locale.ROOT);
        PlayerConfigStore.update(player.getUUID(), s -> s.setApiKey(providerId, value));
        source.sendSuccess(() -> Component.literal("API key stored for " + providerId + " (your config only)"), true);
        return 1;
    }

    private static int mcpStart(CommandSourceStack source) {
        TheseusServerConfig.MCP_ENABLED.set(true);
        TheseusServerConfig.save();
        McpBridgeServer.startIfEnabled(source.getServer());
        source.sendSuccess(() -> Component.literal("MCP bridge start requested on "
                + TheseusServerConfig.MCP_BIND_HOST.get() + ":" + TheseusServerConfig.MCP_PORT.getAsInt()), true);
        return 1;
    }

    private static int mcpStop(CommandSourceStack source) {
        TheseusServerConfig.MCP_ENABLED.set(false);
        TheseusServerConfig.save();
        McpBridgeServer.stop();
        source.sendSuccess(() -> Component.literal("MCP bridge stopped"), true);
        return 1;
    }
}
