package com.modelplant.theseus.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Per-player LLM preferences (provider, model, keys, URLs, sampling). Persisted outside world saves.
 */
public final class PlayerLlmSettings {
    private String providerId;
    private String model;
    private int maxToolIterations;
    private double temperature;
    private int maxTokens;
    /** null = follow server verboseChat default */
    private Boolean verboseOverride;
    private final Map<String, String> urlOverrides = new LinkedHashMap<>();
    private final Map<String, String> apiKeys = new LinkedHashMap<>();

    public PlayerLlmSettings() {
    }

    public static PlayerLlmSettings defaultsFromServer() {
        PlayerLlmSettings s = new PlayerLlmSettings();
        s.verboseOverride = null;
        if (TheseusServerConfig.SPEC.isLoaded()) {
            s.providerId = TheseusServerConfig.DEFAULT_PROVIDER_ID.get();
            s.model = TheseusServerConfig.DEFAULT_MODEL.get();
            s.maxToolIterations = TheseusServerConfig.DEFAULT_MAX_TOOL_ITERATIONS.getAsInt();
            s.temperature = TheseusServerConfig.DEFAULT_TEMPERATURE.get();
            s.maxTokens = TheseusServerConfig.DEFAULT_MAX_TOKENS.getAsInt();
        } else {
            s.providerId = "mock";
            s.model = "mock-theseus";
            s.maxToolIterations = 100;
            s.temperature = 0.2d;
            s.maxTokens = 2048;
        }
        return s;
    }

    public PlayerLlmSettings copy() {
        PlayerLlmSettings c = new PlayerLlmSettings();
        c.providerId = providerId;
        c.model = model;
        c.maxToolIterations = maxToolIterations;
        c.temperature = temperature;
        c.maxTokens = maxTokens;
        c.verboseOverride = verboseOverride;
        c.urlOverrides.putAll(urlOverrides);
        c.apiKeys.putAll(apiKeys);
        return c;
    }

    public String providerId() {
        return providerId == null || providerId.isBlank() ? "mock" : providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId == null ? "mock" : providerId.trim().toLowerCase(Locale.ROOT);
    }

    public String model() {
        return model == null || model.isBlank() ? "mock-theseus" : model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.trim();
    }

    public int maxToolIterations() {
        return Math.clamp(maxToolIterations, 1, 256);
    }

    public void setMaxToolIterations(int maxToolIterations) {
        this.maxToolIterations = Math.clamp(maxToolIterations, 1, 256);
    }

    public double temperature() {
        return Math.clamp(temperature, 0.0d, 2.0d);
    }

    public void setTemperature(double temperature) {
        this.temperature = Math.clamp(temperature, 0.0d, 2.0d);
    }

    public int maxTokens() {
        return Math.clamp(maxTokens, 64, 128000);
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.clamp(maxTokens, 64, 128000);
    }

    public Boolean verboseOverride() {
        return verboseOverride;
    }

    public void setVerboseOverride(Boolean verboseOverride) {
        this.verboseOverride = verboseOverride;
    }

    public Map<String, String> urlOverridesView() {
        return Collections.unmodifiableMap(urlOverrides);
    }

    public String urlOverride(String providerId) {
        if (providerId == null) {
            return null;
        }
        String v = urlOverrides.get(providerId.trim().toLowerCase(Locale.ROOT));
        return v == null || v.isBlank() ? null : v;
    }

    public void setUrlOverride(String providerId, String url) {
        String id = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return;
        }
        if (url == null || url.isBlank()) {
            urlOverrides.remove(id);
        } else {
            urlOverrides.put(id, url.trim());
        }
    }

    public String apiKey(String providerId) {
        if (providerId == null) {
            return "";
        }
        return apiKeys.getOrDefault(providerId.trim().toLowerCase(Locale.ROOT), "");
    }

    public boolean hasApiKey(String providerId) {
        String key = apiKey(providerId);
        return key != null && !key.isBlank();
    }

    public void setApiKey(String providerId, String apiKey) {
        String id = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKeys.remove(id);
        } else {
            apiKeys.put(id, apiKey.trim());
        }
    }

    Map<String, String> mutableUrlOverrides() {
        return urlOverrides;
    }

    Map<String, String> mutableApiKeys() {
        return apiKeys;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlayerLlmSettings that)) {
            return false;
        }
        return maxToolIterations == that.maxToolIterations
                && Double.compare(that.temperature, temperature) == 0
                && maxTokens == that.maxTokens
                && Objects.equals(providerId, that.providerId)
                && Objects.equals(model, that.model)
                && Objects.equals(verboseOverride, that.verboseOverride)
                && Objects.equals(urlOverrides, that.urlOverrides)
                && Objects.equals(apiKeys, that.apiKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, model, maxToolIterations, temperature, maxTokens,
                verboseOverride, urlOverrides, apiKeys);
    }
}
