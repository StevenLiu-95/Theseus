package com.modelplant.theseus.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modelplant.theseus.Theseus;
import com.modelplant.theseus.api.gate.GateContext;
import com.modelplant.theseus.api.llm.ToolCall;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.audit.AuditLog;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.gate.ToolGateEngine;
import com.modelplant.theseus.permission.TheseusPermissions;
import com.modelplant.theseus.runtime.TheseusExecutors;
import com.modelplant.theseus.session.AiSession;
import com.modelplant.theseus.session.AiSessionManager;
import com.modelplant.theseus.tool.ToolExecutor;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal MCP-compatible HTTP bridge exposing the same tool catalog as in-game AI.
 * Endpoints:
 * - GET  /health
 * - GET  /mcp/tools?player=&lt;name|uuid&gt;   (player required; gated catalog)
 * - POST /mcp/call   {"tool":"...", "arguments":{...}, "player":"&lt;name|uuid&gt;"}  // player required
 * - POST /mcp        JSON-RPC style initialize / tools/list / tools/call
 */
public final class McpBridgeServer {
    private static final Gson GSON = new Gson();
    private static final AtomicReference<McpBridgeServer> INSTANCE = new AtomicReference<>();

    private final HttpServer httpServer;
    private final MinecraftServer gameServer;

    private McpBridgeServer(HttpServer httpServer, MinecraftServer gameServer) {
        this.httpServer = httpServer;
        this.gameServer = gameServer;
    }

    public static synchronized void startIfEnabled(MinecraftServer server) {
        stop();
        if (!TheseusServerConfig.MCP_ENABLED.getAsBoolean()) {
            Theseus.LOGGER.info("MCP bridge disabled");
            return;
        }
        String host = TheseusServerConfig.MCP_BIND_HOST.get();
        String token = TheseusServerConfig.MCP_AUTH_TOKEN.get();
        boolean tokenBlank = token == null || token.isBlank();
        boolean loopback = McpBindHosts.isLoopback(host);
        if (tokenBlank && !loopback) {
            Theseus.LOGGER.error(
                    "MCP bridge refused to start: bind host '{}' is not loopback and mcpAuthToken is empty. "
                            + "Set mcpAuthToken or bind to 127.0.0.1.",
                    host);
            return;
        }
        if (tokenBlank) {
            Theseus.LOGGER.warn(
                    "MCP bridge starting on loopback without mcpAuthToken (dev only). "
                            + "Set mcpAuthToken before exposing beyond this machine.");
        }
        try {
            int port = TheseusServerConfig.MCP_PORT.getAsInt();
            HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);
            McpBridgeServer bridge = new McpBridgeServer(http, server);
            http.createContext("/health", bridge::health);
            http.createContext("/mcp/tools", bridge::toolsRest);
            http.createContext("/mcp/call", bridge::callRest);
            http.createContext("/mcp", bridge::jsonRpc);
            http.setExecutor(TheseusExecutors.workers());
            http.start();
            INSTANCE.set(bridge);
            Theseus.LOGGER.info("MCP bridge listening on http://{}:{}/mcp", host, port);
        } catch (IOException e) {
            Theseus.LOGGER.error("Failed to start MCP bridge", e);
        }
    }

    public static synchronized void stop() {
        McpBridgeServer bridge = INSTANCE.getAndSet(null);
        if (bridge != null) {
            bridge.httpServer.stop(0);
            Theseus.LOGGER.info("MCP bridge stopped");
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        writeJson(exchange, 200, obj("status", "ok"));
    }

    private void toolsRest(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, obj("error", "GET only"));
            return;
        }
        if (!authorize(exchange)) {
            return;
        }
        String playerRef = queryParam(exchange, "player");
        if (playerRef == null || playerRef.isBlank()) {
            writeJson(exchange, 400, obj("error", "Missing required query parameter 'player' (name or UUID)"));
            return;
        }
        ServerPlayer player = resolvePlayerExact(playerRef);
        if (player == null) {
            writeJson(exchange, 404, obj("error", "Player not found or offline: " + playerRef));
            return;
        }
        JsonObject root = new JsonObject();
        root.add("tools", toolsFor(player));
        writeJson(exchange, 200, root);
    }

    private void callRest(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, obj("error", "POST only"));
            return;
        }
        if (!authorize(exchange)) {
            return;
        }
        JsonObject body = readJson(exchange);
        String tool = body.has("tool") ? body.get("tool").getAsString() : "";
        JsonObject args = body.has("arguments") && body.get("arguments").isJsonObject()
                ? body.getAsJsonObject("arguments") : new JsonObject();
        String playerRef = body.has("player") ? body.get("player").getAsString() : null;
        ToolResult result = invoke(tool, args, playerRef);
        writeJson(exchange, result.success() ? 200 : 400, result.toJson());
    }

    private void jsonRpc(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (!authorize(exchange)) {
                return;
            }
            Headers headers = exchange.getResponseHeaders();
            headers.add("Content-Type", "text/event-stream");
            headers.add("Cache-Control", "no-cache");
            headers.add("Connection", "keep-alive");
            String payload = "event: endpoint\ndata: /mcp\n\n";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, obj("error", "POST/GET only"));
            return;
        }
        if (!authorize(exchange)) {
            return;
        }
        JsonObject req = readJson(exchange);
        String method = req.has("method") ? req.get("method").getAsString() : "";
        JsonElement id = req.get("id");
        JsonObject response = new JsonObject();
        if (id != null) {
            response.add("id", id);
        }
        response.addProperty("jsonrpc", "2.0");
        switch (method) {
            case "initialize" -> {
                JsonObject result = new JsonObject();
                result.addProperty("protocolVersion", "2024-11-05");
                JsonObject caps = new JsonObject();
                caps.add("tools", new JsonObject());
                result.add("capabilities", caps);
                JsonObject serverInfo = new JsonObject();
                serverInfo.addProperty("name", "theseus-mc");
                serverInfo.addProperty("version", "0.1.0");
                result.add("serverInfo", serverInfo);
                response.add("result", result);
            }
            case "tools/list" -> {
                JsonObject params = req.has("params") && req.get("params").isJsonObject()
                        ? req.getAsJsonObject("params") : new JsonObject();
                String playerRef = params.has("player") ? params.get("player").getAsString() : null;
                if (playerRef == null || playerRef.isBlank()) {
                    JsonObject error = new JsonObject();
                    error.addProperty("code", -32602);
                    error.addProperty("message", "Missing required params.player (name or UUID)");
                    response.add("error", error);
                    writeJson(exchange, 200, response);
                    return;
                }
                ServerPlayer player = resolvePlayerExact(playerRef);
                if (player == null) {
                    JsonObject error = new JsonObject();
                    error.addProperty("code", -32001);
                    error.addProperty("message", "Player not found or offline: " + playerRef);
                    response.add("error", error);
                    writeJson(exchange, 200, response);
                    return;
                }
                JsonObject result = new JsonObject();
                result.add("tools", toolsFor(player));
                response.add("result", result);
            }
            case "tools/call" -> {
                JsonObject params = req.has("params") && req.get("params").isJsonObject()
                        ? req.getAsJsonObject("params") : new JsonObject();
                String name = params.has("name") ? params.get("name").getAsString() : "";
                JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                        ? params.getAsJsonObject("arguments") : new JsonObject();
                String playerRef = params.has("player") ? params.get("player").getAsString() : null;
                ToolResult toolResult = invoke(name, args, playerRef);
                JsonObject result = new JsonObject();
                JsonArray content = new JsonArray();
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", toolResult.toText());
                content.add(text);
                result.add("content", content);
                result.addProperty("isError", !toolResult.success());
                response.add("result", result);
            }
            case "notifications/initialized", "ping" -> response.add("result", new JsonObject());
            default -> {
                JsonObject error = new JsonObject();
                error.addProperty("code", -32601);
                error.addProperty("message", "Method not found: " + method);
                response.add("error", error);
            }
        }
        writeJson(exchange, 200, response);
    }

    private ToolResult invoke(String toolName, JsonObject args, String playerRef) {
        if (playerRef == null || playerRef.isBlank()) {
            return ToolResult.error("Missing required 'player' (name or UUID). Refusing default online-player fallback.");
        }
        ServerPlayer player = resolvePlayerExact(playerRef);
        if (player == null) {
            return ToolResult.error("Player not found or offline: " + playerRef);
        }
        AiSession session = AiSessionManager.get().getOrCreate(player);
        if (!session.tryAcquire()) {
            return ToolResult.error("Session busy (in-game chat or another MCP call in progress). Retry later or cancel chat.");
        }
        try {
            GateContext gate = new GateContext(
                    player,
                    session.id(),
                    TheseusServerConfig.enabledCapabilities(),
                    TheseusPermissions.isOperator(player),
                    true,
                    player.level().dimension(),
                    player.gameMode.getGameModeForPlayer()
            );
            boolean allowed = ToolGateEngine.get().filter(gate).stream().anyMatch(t -> t.name().equals(toolName));
            if (!allowed) {
                ToolResult denied = ToolResult.error("Tool gated/unavailable: " + toolName);
                AuditLog.record(player, "mcp:" + toolName, args.toString(), denied);
                return denied;
            }
            ToolCall call = new ToolCall("mcp_" + UUID.randomUUID(), toolName, args.toString());
            try {
                ToolResult result = ToolExecutor.execute(player, session, call, gate).join();
                AuditLog.record(player, "mcp:" + toolName, args.toString(), result);
                return result;
            } catch (Exception e) {
                ToolResult err = ToolResult.error(e.getMessage());
                AuditLog.record(player, "mcp:" + toolName, args.toString(), err);
                return err;
            }
        } finally {
            session.release();
        }
    }

    private JsonArray toolsFor(ServerPlayer player) {
        JsonArray arr = new JsonArray();
        GateContext gate = new GateContext(
                player,
                UUID.randomUUID(),
                TheseusServerConfig.enabledCapabilities(),
                TheseusPermissions.isOperator(player),
                true,
                player.level().dimension(),
                player.gameMode.getGameModeForPlayer()
        );
        for (Tool tool : ToolGateEngine.get().filter(gate)) {
            arr.add(tool.schema().toMcpTool());
        }
        return arr;
    }

    /** Exact match only — never falls back to an arbitrary online player. */
    private ServerPlayer resolvePlayerExact(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        List<ServerPlayer> players = gameServer.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            if (player.getGameProfile().getName().equalsIgnoreCase(ref) || player.getUUID().toString().equalsIgnoreCase(ref)) {
                return player;
            }
        }
        return null;
    }

    private boolean authorize(HttpExchange exchange) throws IOException {
        String expected = TheseusServerConfig.MCP_AUTH_TOKEN.get();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.equals("Bearer " + expected)) {
            return true;
        }
        writeJson(exchange, 401, obj("error", "unauthorized"));
        return false;
    }

    private static String queryParam(HttpExchange exchange, String key) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = part.substring(0, eq);
            if (k.equals(key)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static JsonObject readJson(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return new JsonObject();
            }
            return JsonParser.parseString(raw).getAsJsonObject();
        }
    }

    private static void writeJson(HttpExchange exchange, int code, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static JsonObject obj(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }
}
