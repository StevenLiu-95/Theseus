package com.modelplant.theseus.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Global COMMON policy + defaults for new players.
 * Per-player provider/model/keys/URLs live in {@link PlayerConfigStore}.
 */
public final class TheseusServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_PROVIDER_ID = BUILDER
            .comment("Default LLM provider for new players: mock, ollama, openai, anthropic, gemini, deepseek, qwen")
            .define("defaultProviderId", "mock");

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_MODEL = BUILDER
            .comment("Default model name for new players")
            .define("defaultModel", "mock-theseus");

    public static final ModConfigSpec.IntValue DEFAULT_MAX_TOOL_ITERATIONS = BUILDER
            .comment("Default max tool-call rounds per user message for new players")
            .defineInRange("defaultMaxToolIterations", 100, 1, 256);

    public static final ModConfigSpec.DoubleValue DEFAULT_TEMPERATURE = BUILDER
            .comment("Default sampling temperature for new players")
            .defineInRange("defaultTemperature", 0.2d, 0.0d, 2.0d);

    public static final ModConfigSpec.IntValue DEFAULT_MAX_TOKENS = BUILDER
            .comment("Default max output tokens for new players")
            .defineInRange("defaultMaxTokens", 2048, 64, 128000);

    public static final ModConfigSpec.BooleanValue VERBOSE_CHAT = BUILDER
            .comment("Default verbose chat when a player has no override. Players use /theseus verbose")
            .define("verboseChat", false);

    public static final ModConfigSpec.IntValue OLLAMA_PULL_TIMEOUT_HOURS = BUILDER
            .comment("Max hours to wait for a local model download (ollama pull)")
            .defineInRange("ollamaPullTimeoutHours", 6, 1, 48);

    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_SECONDS = BUILDER
            .comment("HTTP timeout for LLM requests")
            .defineInRange("requestTimeoutSeconds", 60, 5, 600);

    public static final ModConfigSpec.IntValue MAX_HISTORY_MESSAGES = BUILDER
            .comment("Max chat history messages retained per session")
            .defineInRange("maxHistoryMessages", 40, 4, 500);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENABLED_CAPABILITIES = BUILDER
            .comment("Enabled tool capability flags")
            .defineListAllowEmpty(
                    "enabledCapabilities",
                    List.of("read", "echo", "command", "control", "world_edit", "possess", "vision", "misc"),
                    () -> "",
                    o -> o instanceof String
            );

    public static final ModConfigSpec.BooleanValue REQUIRE_OP_FOR_DANGEROUS = BUILDER
            .comment("Require operator permission for command/control/world_edit/possess tools")
            .define("requireOpForDangerous", true);

    public static final ModConfigSpec.IntValue MAX_BLOCK_CHANGES_PER_TICK = BUILDER
            .comment("Budget for direct world edits per server tick")
            .defineInRange("maxBlockChangesPerTick", 256, 1, 100000);

    public static final ModConfigSpec.IntValue MAX_ACTION_RADIUS = BUILDER
            .comment("Max radius (blocks) for AI world/player actions from the owning player")
            .defineInRange("maxActionRadius", 64, 1, 512);

    public static final ModConfigSpec.IntValue COMMAND_WHITELIST_LEVEL = BUILDER
            .comment("Permission level required to run execute_command tool")
            .defineInRange("commandPermissionLevel", 2, 0, 4);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> COMMAND_WHITELIST = BUILDER
            .comment("Allowed command roots for execute_command (empty = allow all when permitted)")
            .defineListAllowEmpty("commandWhitelist", List.of("say", "tp", "give", "time", "weather", "gamemode", "effect", "summon", "fill", "setblock", "clone"), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue MCP_ENABLED = BUILDER
            .comment("Enable embedded MCP HTTP/SSE bridge")
            .define("mcpEnabled", false);

    public static final ModConfigSpec.ConfigValue<String> MCP_BIND_HOST = BUILDER
            .comment("MCP bind host (prefer localhost)")
            .define("mcpBindHost", "127.0.0.1");

    public static final ModConfigSpec.IntValue MCP_PORT = BUILDER
            .comment("MCP HTTP port")
            .defineInRange("mcpPort", 8765, 1, 65535);

    public static final ModConfigSpec.ConfigValue<String> MCP_AUTH_TOKEN = BUILDER
            .comment("Bearer token for MCP bridge. Required when mcpBindHost is not loopback; empty on 127.0.0.1 warns only")
            .define("mcpAuthToken", "");

    public static final ModConfigSpec.BooleanValue AUDIT_LOG_ENABLED = BUILDER
            .comment("Write AI tool audit log to console / file")
            .define("auditLogEnabled", true);

    public static final ModConfigSpec.IntValue MAX_POSSESSIONS = BUILDER
            .comment("Max simultaneous possessed entities per session")
            .defineInRange("maxPossessions", 8, 1, 64);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private TheseusServerConfig() {
    }

    /**
     * Persist in-memory {@link ModConfigSpec.ConfigValue#set} changes to disk.
     */
    public static void save() {
        if (SPEC.isLoaded()) {
            SPEC.save();
        }
    }

    public static Set<String> enabledCapabilities() {
        return new HashSet<>(ENABLED_CAPABILITIES.get());
    }
}
