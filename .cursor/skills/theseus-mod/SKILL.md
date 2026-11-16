---
name: theseus-mod
description: >-
  Develop and extend the Theseus NeoForge 1.21.1 Minecraft AI foundation mod
  (LLM chat, tool gate, MCP bridge, player control, vision). Use when editing
  Theseus code, adding tools/providers/gate rules, MCP endpoints, commands,
  networking, session orchestration, or when the user mentions Theseus, MC AI
  tools, ToolGate, ServerTaskQueue, or rebuilding the theseus jar.
---

# Theseus Mod Development

NeoForge **1.21.1** / JDK **21** foundation mod: LLM + MCP agents drive Minecraft through a gated tool system. Other mods integrate via `com.modelplant.theseus.api`.

## Project principles

- Treat Theseus as a **foundation mod for Minecraft AI**: maximize compatibility so many other mods can depend on and extend it.
- Prefer encapsulation plus stable integration surfaces so third-party mods can plug into AI features without forking Theseus.
- Keep gameplay smooth; design changes so they do not hitch the client or server tick.

## Hard rules

1. **Public API first**: Prefer putting extension surfaces under `com.modelplant.theseus.api` (`TheseusAPI`, `Tool`, `LlmProvider`, `GateRule`, events). Do not force other mods to depend on internal packages.
2. **Never mutate the world off the server main thread**. Use `ServerTaskQueue` / `TheseusAPI.taskQueue()`. LLM/MCP/HTTP stay on `TheseusExecutors.workers()`.
3. **One ToolRegistry**: In-game chat, MCP, and external mods share `TheseusToolRegistry` + `ToolGateEngine`. New tools must declare a `capability()` and respect gate/OP/quota.
4. **Per-player isolation**: Provider / model / API key / URL / iterations live in `PlayerConfigStore` (`config/theseus/players/<uuid>.json`). Never share or log another player's key.
5. **Session busy lock**: Chat and MCP `tools/call` share `AiSession.tryAcquire()`. Do not bypass the lock for world-changing work.
6. **After finishing a coding task**, rebuild the jar (see Build) unless the user says not to.

## Architecture map

| Concern | Entry |
|---------|--------|
| Mod init | `Theseus` → `TheseusBootstrap` |
| Chat / tool loop | `session.ChatOrchestrator` |
| Tools | `tool.TheseusToolRegistry`, `tool.builtin.BuiltinTools`, `ToolExecutor` |
| Gate | `gate.ToolGateEngine`, `gate.BuiltinGateRules` |
| LLM | `llm.*` + `api.llm.LlmProvider` |
| MCP | `mcp.McpBridgeServer` |
| Main-thread queue | `runtime.ServerTaskQueue` |
| Control / possess | `control.PlayerController`, `EntityPossessionManager` |
| Vision | `vision.ScreenshotService` + network payloads |
| Commands | `command.TheseusCommands` |
| Stable API | `api.TheseusAPI`, `docs/API.md` |

Capabilities: `read` `echo` `command` `control` `world_edit` `possess` `vision` `misc`. Dangerous set (default OP): `command` `control` `world_edit` `possess`.

## Workflow: change code

1. Identify whether the change is **public API**, **builtin implementation**, or **server policy** (`TheseusServerConfig`).
2. Keep worker vs main-thread split; mark tools with `requiresMainThread()` correctly (`take_screenshot` must run off main thread).
3. If adding a tool/provider/rule, also wire registration (`BuiltinTools` / `TheseusBootstrap` / events) and update `docs/API.md` or README only when user-facing behavior changes.
4. Prefer small, focused diffs; match existing style (records, Gson `JsonObject`, NeoForge events).
5. Rebuild jar when the task is done.

## Workflow: add a builtin tool

1. Implement `Tool` in `BuiltinTools` (or a dedicated class registered from there).
2. Set `schema()`, `capability()`, `requiresMainThread()`, `execute(ToolContext)`.
3. Enforce radius / permissions inside execute when touching the world (`maxActionRadius`, `TheseusPermissions`).
4. Register in `BuiltinTools.registerAll()`.
5. Gate coverage is automatic via capability rules; add a new `GateRule` only if filtering needs more than capability/OP/vision.
6. Rebuild jar.

## Workflow: add LLM provider

1. Implement `api.llm.LlmProvider` under `llm/`.
2. Register in `TheseusBootstrap.initCommon()` and/or `RegisterTheseusProvidersEvent`.
3. Add endpoint defaults in `ProviderEndpoints` / `SuggestedModels` if needed.
4. Do not read global keys — always use the requesting player's `PlayerConfigStore`.
5. Rebuild jar.

## Workflow: MCP / networking

- MCP must require explicit `player` (name or UUID); never fall back to an arbitrary online player.
- Non-loopback bind requires `mcpAuthToken` or refuse start.
- Client-bound control/screenshot uses payloads in `network/` registered by `TheseusNetwork`.
- Screenshot wait must not run on the server main thread (deadlock).

## Build

Prefer incremental (no `clean`):

```powershell
powershell -File scripts/fast-jar.ps1
```

Equivalent: `./gradlew jar --init-script init.gradle`  
Output: `build/libs/theseus-*.jar`  
Use `clean` only if caches look corrupt. Dev runs: `runClient` / `runServer` with `--init-script init.gradle`.

## Do / Don't

| Do | Don't |
|----|--------|
| Expose hooks via `TheseusAPI` + events | Reach into internals from “example” extension code |
| Queue world edits on `ServerTaskQueue` | Call level/entity mutators on worker threads |
| Filter tools through `ToolGateEngine` | Hand the full registry to the model/MCP |
| Audit dangerous tool calls | Skip `AuditLog` for MCP or high-impact tools |
| Keep history tool_calls paired (`ToolHistorySanitizer`) | Trim history in ways that orphan `role=tool` |

## Additional resources

- Extension recipes and package layout: [reference.md](reference.md)
- Public API doc in repo: `docs/API.md`
- Perf / multiplayer notes: `docs/PERFORMANCE.md`
- Porting: `docs/PORTING.md`
