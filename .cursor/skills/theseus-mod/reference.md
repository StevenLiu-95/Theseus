# Theseus reference

Read this when implementing extensions or debugging cross-cutting behavior.

## Package layout

```
com.modelplant.theseus
├── api/                 # Stable surface for other mods
│   ├── TheseusAPI
│   ├── tool/            # Tool, ToolContext, ToolResult, ToolSchema, ToolRegistry
│   ├── llm/             # LlmProvider, ChatRequest/Response/Message, ToolCall, ImageData
│   ├── gate/            # GateRule, GateContext, GateCapabilities
│   └── event/           # RegisterTheseusTools/Providers, TheseusGateEvent
├── bootstrap/           # Lifecycle wiring
├── session/             # AiSession, ChatOrchestrator, sanitizer, verbose
├── tool/                # Registry, executor, builtin tools
├── gate/                # ToolGateEngine + builtin rules
├── llm/                 # Provider implementations + HTTP helpers
├── mcp/                 # Embedded HTTP/JSON-RPC bridge
├── control/             # PlayerController, EntityPossessionManager
├── vision/              # ScreenshotService
├── network/             # Payloads + registrar
├── config/              # Server COMMON toml + per-player JSON store
├── runtime/             # TheseusExecutors, ServerTaskQueue
├── command/             # Brigadier /theseus …
├── permission/          # OP / control checks
└── audit/               # Tool audit log
```

## Threading contract

```
Player /theseus chat  or  MCP HTTP
        │
        ▼
TheseusExecutors.workers()     ← LLM I/O, orchestration, off-thread tools
        │
        ├─ Tool.requiresMainThread() == true
        │       → ServerTaskQueue.submit(...)   ← max ~64 tasks/tick
        │       → mutate world / player / mob
        │
        └─ requiresMainThread() == false
                → run on worker (echo, screenshot wait, pure compute)
```

Block-edit budget: `TheseusServerConfig.MAX_BLOCK_CHANGES_PER_TICK` via `ServerTaskQueue.tryConsumeBlockChangeBudget`.

## Chat loop (simplified)

1. `AiSession.tryAcquire()`
2. Append user message; load per-player LLM prefs
3. For each iteration ≤ `maxToolIterations`:
   - `ToolGateEngine.filter(GateContext)` → schemas
   - Drain pending vision images if provider supports vision
   - `provider.chat(...)` (cancellable poll)
   - On tool_calls: execute each via `ToolExecutor`, audit, append tool results; enqueue vision from screenshot data
   - Else: append assistant text, release, return
4. Cancel pads remaining tool results so history stays valid

## Gate evaluation order

Builtin priorities:

1. `capability` (10) — must be in `enabledCapabilities` (or `*`)
2. `operator` (20) — dangerous caps need OP when `requireOpForDangerous`
3. `vision` (30) — vision tools only if `GateContext.visionCapableModel()`

Then `TheseusGateEvent` may mutate or cancel the list.

## Extension recipes

### Other mod registers a tool

```java
NeoForge.EVENT_BUS.addListener((RegisterTheseusToolsEvent event) -> {
    event.register(myTool);
});
// or TheseusAPI.registerTool(myTool);
```

Implement `Tool`; return `ToolResult.ok` / `okJson` / `error`. Use a dedicated capability string if the tool is high-impact, and document that servers must enable it.

### Custom gate rule

```java
TheseusAPI.registerGateRule(new GateRule() {
    public String id() { return "my_mod_rule"; }
    public int priority() { return 40; } // after builtins
    public boolean allow(Tool tool, GateContext ctx) { ... }
});
```

Or listen to `TheseusGateEvent` to remove/add from the mutable tool list.

### Custom provider

Implement `LlmProvider` (`supportsTools` / `supportsVision`, async `chat`). Register in `RegisterTheseusProvidersEvent` or `TheseusAPI.registerProvider`. Prefer OpenAI-compatible JSON shapes when possible so tool schemas reuse `ToolSchema.toOpenAiFunction()`.

### Main-thread work from an extension

```java
TheseusAPI.taskQueue().submit(() -> {
    // safe world mutation
    return result;
});
```

## Builtin tool catalog (names)

| Name | Capability | Notes |
|------|------------|-------|
| echo | echo | Off-thread OK |
| get_player_info, get_block, list_entities, get_inventory | read | Radius on block reads |
| execute_command | command | Whitelist + permission level |
| player_move, player_goto, player_look, player_break_block, player_place_block, player_use_item, player_select_hotbar, player_open_inventory, player_drop_item, player_interact_block | control | goto = simple nav, not A* |
| world_set_block, world_fill | world_edit | Tick block budget |
| possess_entity, unpossess, possessed_action | possess | Per-session cap |
| take_screenshot | vision | Worker-only wait; client capture |

## Config surfaces

| File | Role |
|------|------|
| `config/theseus-common.toml` | Server policy, MCP, quotas, default prefs for new players |
| `config/theseus/players/<uuid>.json` | Per-player provider/model/key/URL/iterations |
| Legacy `apikeys.json` | Migrated into player JSON on login |

API keys in JSON are Base64 obfuscation only — never commit player config dirs.

## MCP endpoints

- `GET /health`
- `GET /mcp/tools?player=...` (required)
- `POST /mcp/call` JSON `{tool, arguments, player}`
- `POST /mcp` JSON-RPC: `initialize`, `tools/list`, `tools/call` (`params.player` required)
- GET `/mcp` SSE is a lightweight endpoint advertisement only

Auth: `Authorization: Bearer <mcpAuthToken>` when token configured.

## Known limits (avoid “fixing” into scope creep)

- `player_goto` is straight-line + jump/step-up, not Baritone/A*
- OpenAI-compatible streaming only for tool-free text rounds
- Screenshot may fall back to path-only if payload too large
- MCP SSE is not full Streamable HTTP session semantics

## Test touchpoints

Unit tests under `src/test/java` cover gate helpers, mock provider, MCP loopback host, tool history sanitizer. Prefer adding focused tests when changing gate/history/MCP auth behavior.
