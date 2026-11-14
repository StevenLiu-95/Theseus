package com.modelplant.theseus.llm;

import com.modelplant.theseus.config.PlayerLlmSettings;

import java.util.Locale;
import java.util.Optional;

/**
 * Per-provider API base URLs. Built-in official defaults; players may override per provider.
 */
public final class ProviderEndpoints {
    private ProviderEndpoints() {
    }

    public static String resolve(String providerId) {
        return resolve(providerId, null);
    }

    public static String resolve(String providerId, PlayerLlmSettings settings) {
        String id = normalize(providerId);
        if (settings != null) {
            String override = settings.urlOverride(id);
            if (override != null) {
                return normalizeUrl(override);
            }
        }
        return defaultUrl(id);
    }

    public static String resolveActive(PlayerLlmSettings settings) {
        if (settings == null) {
            return defaultUrl("mock");
        }
        return resolve(settings.providerId(), settings);
    }

    public static boolean setUrl(PlayerLlmSettings settings, String providerId, String url) {
        String id = normalize(providerId);
        return switch (id) {
            case "openai", "deepseek", "qwen", "anthropic", "gemini", "ollama" -> {
                settings.setUrlOverride(id, url == null || url.isBlank() ? null : normalizeUrl(url));
                yield true;
            }
            default -> false;
        };
    }

    public static Optional<String> configurableProvidersHint(String providerId) {
        String id = normalize(providerId);
        return switch (id) {
            case "openai", "deepseek", "qwen", "anthropic", "gemini", "ollama" -> Optional.of(defaultUrl(id));
            default -> Optional.empty();
        };
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

    /** OpenAI-compatible chat base for ollama = native api + /v1 */
    public static String openAiCompatibleBase(String providerId, PlayerLlmSettings settings) {
        String id = normalize(providerId);
        String base = resolve(id, settings);
        if ("ollama".equals(id)) {
            if (base.endsWith("/v1")) {
                return base;
            }
            return base + "/v1";
        }
        return base;
    }

    public static String ollamaNativeBase(PlayerLlmSettings settings) {
        String base = resolve("ollama", settings);
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
        }
        return base;
    }
}
