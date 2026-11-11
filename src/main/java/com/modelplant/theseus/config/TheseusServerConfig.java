package com.modelplant.theseus.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TheseusServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> PROVIDER_ID = BUILDER
            .comment("Active LLM provider id: mock, ollama, openai, anthropic, gemini, deepseek, qwen")
            .define("providerId", "mock");

    public static final ModConfigSpec.ConfigValue<String> MODEL = BUILDER
            .comment("Model name for the active provider")
            .define("model", "mock-theseus");

    public static final ModConfigSpec.ConfigValue<String> OPENAI_BASE_URL = BUILDER
            .comment("OpenAI / OpenAI-compatible gateway base URL (provider=openai only)")
            .define("openaiBaseUrl", "https://api.openai.com/v1");

    public static final ModConfigSpec.ConfigValue<String> DEEPSEEK_BASE_URL = BUILDER
            .comment("DeepSeek OpenAI-compatible base URL (provider=deepseek)")
            .define("deepseekBaseUrl", "https://api.deepseek.com/v1");

    public static final ModConfigSpec.ConfigValue<String> QWEN_BASE_URL = BUILDER
            .comment("Qwen / DashScope OpenAI-compatible base URL (provider=qwen)")
            .define("qwenBaseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");

    public static final ModConfigSpec.ConfigValue<String> OLLAMA_BASE_URL = BUILDER
            .comment("Ollama native API base URL (OpenAI-compatible chat uses {base}/v1)")
            .define("ollamaBaseUrl", "http://127.0.0.1:11434");

    public static final ModConfigSpec.IntValue OLLAMA_PULL_TIMEOUT_HOURS = BUILDER
            .comment("Max hours to wait for a local model download (ollama pull)")
            .defineInRange("ollamaPullTimeoutHours", 6, 1, 48);

    public static final ModConfigSpec.ConfigValue<String> ANTHROPIC_BASE_URL = BUILDER
            .comment("Anthropic API base URL (provider=anthropic)")
            .define("anthropicBaseUrl", "https://api.anthropic.com");

    public static final ModConfigSpec.ConfigValue<String> GEMINI_BASE_URL = BUILDER
            .comment("Gemini API base URL (provider=gemini)")
            .define("geminiBaseUrl", "https://generativelanguage.googleapis.com/v1beta");

    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_SECONDS = BUILDER
            .comment("HTTP timeout for LLM requests")
            .defineInRange("requestTimeoutSeconds", 60, 5, 600);

    public static final ModConfigSpec.IntValue MAX_HISTORY_MESSAGES = BUILDER
            .comment("Max chat history messages retained per session")
            .defineInRange("maxHistoryMessages", 40, 4, 500);

    public static final ModConfigSpec.IntValue MAX_TOOL_ITERATIONS = BUILDER
            .comment("Max tool-call rounds per user message")
            .defineInRange("maxToolIterations", 8, 1, 32);

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
            .comment("Optional bearer token required by MCP bridge (empty = no auth)")
            .define("mcpAuthToken", "");

    public static final ModConfigSpec.BooleanValue AUDIT_LOG_ENABLED = BUILDER
            .comment("Write AI tool audit log to console / file")
            .define("auditLogEnabled", true);

    public static final ModConfigSpec.IntValue MAX_POSSESSIONS = BUILDER
            .comment("Max simultaneous possessed entities per session")
            .defineInRange("maxPossessions", 8, 1, 64);

    public static final ModConfigSpec.DoubleValue LLM_TEMPERATURE = BUILDER
            .comment("Default sampling temperature")
            .defineInRange("temperature", 0.2d, 0.0d, 2.0d);

    public static final ModConfigSpec.IntValue MAX_TOKENS = BUILDER
            .comment("Default max output tokens")
            .defineInRange("maxTokens", 2048, 64, 128000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private TheseusServerConfig() {
    }

    public static Set<String> enabledCapabilities() {
        return new HashSet<>(ENABLED_CAPABILITIES.get());
    }
}
