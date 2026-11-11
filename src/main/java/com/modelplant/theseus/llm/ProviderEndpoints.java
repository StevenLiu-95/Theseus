package com.modelplant.theseus.llm;

import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.local.OllamaClient;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Locale;
import java.util.Optional;

/**
 * Per-provider API base URLs. Switching provider selects that provider's configured endpoint.
 */
public final class ProviderEndpoints {
    private ProviderEndpoints() {
    }

    public static String resolve(String providerId) {
        String id = normalize(providerId);
        return switch (id) {
            case "openai" -> normalizeUrl(TheseusServerConfig.OPENAI_BASE_URL.get());
            case "deepseek" -> normalizeUrl(TheseusServerConfig.DEEPSEEK_BASE_URL.get());
            case "qwen" -> normalizeUrl(TheseusServerConfig.QWEN_BASE_URL.get());
            case "anthropic" -> normalizeUrl(TheseusServerConfig.ANTHROPIC_BASE_URL.get());
            case "gemini" -> normalizeUrl(TheseusServerConfig.GEMINI_BASE_URL.get());
            case "ollama" -> OllamaClient.openAiBase();
            case "mock" -> "(offline)";
            default -> "(unknown)";
        };
    }

    public static String resolveActive() {
        return resolve(TheseusServerConfig.PROVIDER_ID.get());
    }

    public static Optional<ModConfigSpec.ConfigValue<String>> urlConfig(String providerId) {
        return switch (normalize(providerId)) {
            case "openai" -> Optional.of(TheseusServerConfig.OPENAI_BASE_URL);
            case "deepseek" -> Optional.of(TheseusServerConfig.DEEPSEEK_BASE_URL);
            case "qwen" -> Optional.of(TheseusServerConfig.QWEN_BASE_URL);
            case "anthropic" -> Optional.of(TheseusServerConfig.ANTHROPIC_BASE_URL);
            case "gemini" -> Optional.of(TheseusServerConfig.GEMINI_BASE_URL);
            case "ollama" -> Optional.of(TheseusServerConfig.OLLAMA_BASE_URL);
            default -> Optional.empty();
        };
    }

    public static boolean setUrl(String providerId, String url) {
        Optional<ModConfigSpec.ConfigValue<String>> config = urlConfig(providerId);
        if (config.isEmpty()) {
            return false;
        }
        config.get().set(normalizeUrl(url));
        return true;
    }

    public static String defaultUrl(String providerId) {
        return switch (normalize(providerId)) {
            case "openai" -> "https://api.openai.com/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "anthropic" -> "https://api.anthropic.com";
            case "gemini" -> "https://generativelanguage.googleapis.com/v1beta";
            case "ollama" -> "http://127.0.0.1:11434";
            case "mock" -> "(offline)";
            default -> "";
        };
    }

    public static String normalize(String providerId) {
        return providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
