package com.modelplant.theseus.local;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modelplant.theseus.config.PlayerLlmSettings;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.llm.HttpClients;
import com.modelplant.theseus.llm.ProviderEndpoints;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Thin client for Ollama native HTTP API + OpenAI-compatible base derivation.
 */
public final class OllamaClient {
    private OllamaClient() {
    }

    public static String apiBase() {
        return apiBase(null);
    }

    public static String apiBase(PlayerLlmSettings settings) {
        return ProviderEndpoints.ollamaNativeBase(settings);
    }

    public static String openAiBase() {
        return openAiBase(null);
    }

    public static String openAiBase(PlayerLlmSettings settings) {
        return ProviderEndpoints.openAiCompatibleBase("ollama", settings);
    }

    public static boolean isReachable() {
        return isReachable(null);
    }

    public static boolean isReachable(PlayerLlmSettings settings) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase(settings) + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClients.get().send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<String> listInstalledModelNames() throws Exception {
        return listInstalledModelNames(null);
    }

    public static List<String> listInstalledModelNames(PlayerLlmSettings settings) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase(settings) + "/api/tags"))
                .timeout(Duration.ofSeconds(TheseusServerConfig.REQUEST_TIMEOUT_SECONDS.getAsInt()))
                .GET()
                .build();
        HttpResponse<String> response = HttpClients.get().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Ollama /api/tags HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray models = root.has("models") ? root.getAsJsonArray("models") : new JsonArray();
        List<String> names = new ArrayList<>();
        for (JsonElement el : models) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("name")) {
                names.add(obj.get("name").getAsString());
            }
        }
        return names;
    }

    public static boolean isInstalled(String ollamaName) throws Exception {
        return isInstalled(ollamaName, null);
    }

    public static boolean isInstalled(String ollamaName, PlayerLlmSettings settings) throws Exception {
        String target = ollamaName.toLowerCase(Locale.ROOT);
        for (String name : listInstalledModelNames(settings)) {
            if (name.equalsIgnoreCase(ollamaName) || name.toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pull a model; streams NDJSON progress lines from Ollama.
     */
    public static void pull(String ollamaName, Consumer<PullProgress> onProgress) throws Exception {
        pull(ollamaName, null, onProgress);
    }

    public static void pull(String ollamaName, PlayerLlmSettings settings, Consumer<PullProgress> onProgress) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("name", ollamaName);
        body.addProperty("stream", true);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase(settings) + "/api/pull"))
                .timeout(Duration.ofHours(TheseusServerConfig.OLLAMA_PULL_TIMEOUT_HOURS.getAsInt()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<InputStream> response = HttpClients.get().send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("Ollama /api/pull HTTP " + response.statusCode() + ": " + truncate(err));
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
                if (obj.has("error")) {
                    throw new IllegalStateException("Ollama pull error: " + obj.get("error").getAsString());
                }
                String status = obj.has("status") ? obj.get("status").getAsString() : "";
                long total = obj.has("total") ? obj.get("total").getAsLong() : 0L;
                long completed = obj.has("completed") ? obj.get("completed").getAsLong() : 0L;
                onProgress.accept(new PullProgress(status, completed, total));
                if ("success".equalsIgnoreCase(status)) {
                    break;
                }
            }
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }

    public record PullProgress(String status, long completed, long total) {
        public int percent() {
            if (total <= 0) {
                return -1;
            }
            return (int) Math.min(100, Math.round(completed * 100.0 / total));
        }
    }
}
