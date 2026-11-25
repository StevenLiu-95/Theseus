# Theseus

NeoForge 1.21.1 模组：将 LLM 与外部 Agent（MCP）接入 Minecraft，通过可扩展工具门控系统读写世界、执行指令、操控玩家/生物，并为视觉模型提供截图能力。

## 要求

- JDK 21+
- Minecraft 1.21.1
- NeoForge 21.1.x

## 构建

需要 **JDK 21**。

日常出包（增量、保留 Daemon、**不要** `clean`）：

```powershell
powershell -File scripts/fast-jar.ps1
```

等价命令：

```bash
./gradlew jar --init-script init.gradle
```

产物：`build/libs/theseus-0.1.0.jar`

推送到 `main` 后，GitHub Actions 会自动构建并把模组 jar 发布到 [Releases](https://github.com/StevenLiu-95/Theseus/releases)（标签 `latest`，始终指向最新成功构建）。

完整校验构建：

```bash
./gradlew build --init-script init.gradle
```

仅在怀疑缓存损坏时再 `./gradlew clean jar --init-script init.gradle`。

开发运行：

```bash
./gradlew runClient --init-script init.gradle
./gradlew runServer --init-script init.gradle
```

若本机无法直连 `maven.neoforged.net`，可：

1. 开启本地 HTTP 代理（默认脚本使用 `127.0.0.1:7897`）
2. 运行 `powershell -File scripts/vendor-and-build.ps1` 将依赖填充到 `local-maven/`
3. 之后用 `--init-script init.gradle` 走本地缓存构建

构建加速说明：`gradle.properties` 将 NFRT launcher meta 指向 BMCLAPI，并启用 Eclipse 并行重编译；NeoForge 相关坐标通过 `exclusiveContent` 只查 `local-maven`，避免弱网下远端仓库探测挂起。本机代理 / JDK 路径请写在 `gradle-local.properties`（见 `gradle-local.properties.example`），不要提交进仓库，以免 CI 误连 `127.0.0.1` 代理。

## 快速开始 / 验收

1. 进入世界后执行 `/theseus help`
2. 默认 provider 为离线 `mock`，可直接对话：
   - `/theseus chat hello`
   - `/theseus chat TOOL get_player_info`
   - `/theseus chat TOOL echo {"message":"hi"}`
3. 切换真实模型（**对本玩家生效**；能用 Theseus 即可配置，支持 Tab 补全）：
   - 一条指令同时配置服务商、模型、密钥：
     `/theseus config api deepseek deepseek-chat sk-...`
   - 或分步：`provider` / `model` / `key`
     - `/theseus config provider openai`
     - `/theseus config model gpt-4o-mini`
     - `/theseus config key openai sk-...`
4. 或下载并启用本地模型（需本机安装 [Ollama](https://ollama.com)）：
   - `/theseus local list`
   - `/theseus local download qwen3-8b`
   - `/theseus local use qwen3-8b`
   - `/theseus chat 你好`
5. 查看当前门控后可见工具：`/theseus tools`

**Mock 冒烟清单**：`help` → `tools` → `chat hello` → `chat TOOL get_player_info` → `chat TOOL echo {"message":"hi"}` 均应有回包。

**按玩家隔离**：provider / model / API Key / iterations / 自定义 URL 存于 `config/theseus/players/<uuid>.json`（跨存档；玩家之间互不共享）。服务端策略与新玩家默认值在 `config/theseus-common.toml`。旧版全局 `apikeys.json` 会在首次进服时迁入该玩家配置。

## 指令一览

除另有说明外，下列指令需由玩家在游戏内执行；LLM 相关配置（provider / model / key / url / iterations）按玩家 UUID 持久化，跨存档、玩家之间互不共享。

| 指令 | 功能 |
|------|------|
| `/theseus help` | 在聊天栏打印指令速查与 GUI 快捷键提示 |
| `/theseus chat <msg>` | 向当前会话发送用户消息，触发 AI 回复与工具调用循环（会占用该玩家会话忙锁） |
| `/theseus cancel` | 请求中止本玩家正在进行的 AI 回复 / 工具循环；若无进行中的回合则提示无可取消内容 |
| `/theseus session new` | 先取消进行中的回复，再为本玩家创建全新会话（新 session id，清空历史） |
| `/theseus session clear` | 先取消进行中的回复，再清空当前会话历史（保留会话，仅重置对话记忆） |
| `/theseus session status` | 显示当前会话 id、历史消息条数、是否 busy |
| `/theseus tools` | 列出对本玩家经 Tool Gate 过滤后当前可见的工具（名称 + capability） |
| `/theseus providers` | 列出已注册的 LLM Provider（id 与显示名） |
| `/theseus status` | 显示本玩家运行状态：provider、model、生效 URL、iterations、是否已设 key、主线程任务队列积压、MCP 开关、verbose、Ollama 是否可达；控制台执行时仅显示队列 / MCP / 服务端 verbose 默认值 |
| `/theseus verbose` | 查看本玩家 verbose 是否开启及来源说明（开启时聊天会输出思考 / 工具调用 / 模型 / Token） |
| `/theseus verbose on` | 为本玩家强制开启详细聊天 |
| `/theseus verbose off` | 为本玩家强制关闭详细聊天 |
| `/theseus verbose default` | 清除本玩家覆盖，改回跟随服务端 `verboseChat` 默认值 |
| `/theseus local list` | 列出内置可下载本地模型目录（及相对本玩家配置的状态） |
| `/theseus local status` | 汇总本机 Ollama / 本地模型可用状态 |
| `/theseus local download <model>` | 通过 Ollama 拉取指定模型（支持目录 id 或 Ollama 名）；进度刷在聊天栏 |
| `/theseus local use <model>` | 启用已下载模型：自动切到 `provider=ollama` 并设置对应 model |
| `/theseus config show` | 显示本玩家完整 LLM 配置（provider、model、key 是否已设、生效 URL、iterations、temperature、maxTokens、verbose、各服务商 URL 覆盖、服务端已启用 capability） |
| `/theseus config api <provider> <model> <key>` | 一条指令同时设置本玩家的服务商、模型与 API Key（支持 Tab 补全） |
| `/theseus config provider <id>` | 仅切换本玩家服务商；会提示当前 URL 与建议模型名 |
| `/theseus config model <name>` | 仅设置本玩家当前使用的模型名 |
| `/theseus config key <provider> <value>` | 仅为指定服务商写入本玩家 API Key |
| `/theseus config url <provider> <url>` | 为本玩家覆盖指定服务商的 Base URL（自定义中转等） |
| `/theseus config ollama <url>` | 为本玩家覆盖 Ollama Base URL 的快捷写法（等价于对该 provider 设 url） |
| `/theseus config iterations` | 查看本玩家单轮对话最大工具循环次数（1–256） |
| `/theseus config iterations <n>` | 设置本玩家最大工具循环次数（1–256） |
| `/theseus config verbose` | **OP（权限 2）**：查看服务端 `verboseChat` 默认值 |
| `/theseus config verbose true\|false` | **OP（权限 2）**：写入并持久化服务端 `verboseChat` 默认值（无个人覆盖的玩家会跟随） |
| `/theseus mcp start` | **OP（权限 2）**：按当前配置启动嵌入式 MCP HTTP 桥 |
| `/theseus mcp stop` | **OP（权限 2）**：停止 MCP 桥 |

辅助 GUI：`Ctrl+Alt+H`（会话界面，可快捷发送 chat / cancel / 查看 tools）

## LLM Providers

| id | 默认 Base URL | 说明 |
|----|---------------|------|
| `mock` | _(offline)_ | 离线开发（假回复） |
| `ollama` | `http://127.0.0.1:11434` | 本地 Ollama（聊天走 `{base}/v1`） |
| `openai` | `https://api.openai.com/v1` | OpenAI / 任意兼容中转 |
| `deepseek` | `https://api.deepseek.com/v1` | DeepSeek 官方 |
| `qwen` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 千问 DashScope 兼容模式 |
| `anthropic` | `https://api.anthropic.com` | Claude Messages API |
| `gemini` | `https://generativelanguage.googleapis.com/v1beta` | Google Gemini |

切换 `/theseus config provider <id>` 使用代码内置官方默认 URL。自定义中转：`/theseus config url <provider> <url>` 或 `/theseus config ollama <url>`（写入本玩家配置）。不同玩家互不影响。

## 本地模型（Ollama）

前置：安装并启动 [Ollama](https://ollama.com)（默认 `http://127.0.0.1:11434`）。

内置可下载目录：

| id | Ollama 名 | 约大小 | 说明 |
|----|-----------|--------|------|
| `qwen3-8b` | `qwen3:8b` | ~5 GB | 默认推荐，工具+中文 |
| `llama3-groq-tool-use-8b` | `llama3-groq-tool-use:8b` | ~5 GB | 工具调用专项 |
| `qwen3-4b` | `qwen3:4b` | ~2.5 GB | 小显存 |

```text
/theseus local status
/theseus local download qwen3:8b
/theseus local use qwen3-8b
/theseus chat 帮我看看附近有什么
```

下载进度会刷在聊天栏；完成后 `use` 会自动切到 `provider=ollama` 并设置对应 model。若 Ollama 不在默认端口：`/theseus config ollama http://127.0.0.1:11434`。

## MCP Bridge

启用后默认监听 `http://127.0.0.1:8765`：

- `GET /health`
- `GET /mcp/tools?player=Steve`（**`player` 必填**；返回对该玩家门控后的工具目录）
- `POST /mcp/call` `{"tool":"get_player_info","arguments":{},"player":"Steve"}`（**`player` 必填**）
- `POST /mcp` JSON-RPC：`initialize` / `tools/list` / `tools/call`（`tools/list` 与 `tools/call` 均要求 `params.player`）

安全：

- 绑定非 loopback 且 `mcpAuthToken` 为空时 **拒绝启动**
- loopback 空 token 仅打 WARN（本地开发）；生产请设置 Bearer Token：`Authorization: Bearer <token>`
- MCP 工具调用与游戏内 chat **共用会话忙锁**（互斥）
- MCP `tools/call` 写入审计日志

Cursor 等外部 Agent 可把该桥当作 MCP/HTTP 工具后端。

## 工具能力（capability）

`read` `echo` `command` `control` `world_edit` `possess` `vision` `misc`

危险能力默认需要 OP；可用服务端配置 `enabledCapabilities` / `requireOpForDangerous` 调整。

## 其它模组扩展

见 [docs/API.md](docs/API.md)。核心入口：`com.modelplant.theseus.api.TheseusAPI`。

## 多版本移植

见 [docs/PORTING.md](docs/PORTING.md)。当前仅正式支持 1.21.1。

## 已知限制

- `player_goto` / `player_move` 为简单直线导航（可选 `entity_uuid` 追随移动目标，含卡住/超时状态）；**不是**完整 A* / Baritone
- `player_engage` 可服务端自动追击并按冷却近战直至击杀 / 达次数 / 超时；`player_attack` 仍为单次挥砍（须已在触及距离内）
- 远程弓箭蓄力射击尚未工具化
- OpenAI 兼容流式仅在**无 tool schema 的纯文本轮**启用；带工具调用时走非流式
- 截图磁盘为 PNG；网络超限时按 `screenshotJpegQuality` 尝试 JPEG，仍超限则仅保留路径（vision 附件可能缺失）
- MCP SSE 仅为轻量 endpoint 通告，非完整双向 Streamable HTTP 会话
- 玩家 API Key 存于 JSON（Base64 混淆非加密）；见 docs/API.md
- 操控他人角色需要 OP（`TheseusPermissions.canControlPlayers`）

## 架构要点

- 服务端权威执行世界变更
- LLM 配置按玩家 UUID 持久化隔离；聊天只使用该玩家自己的 provider / key / URL
- LLM/MCP I/O 在工作线程，回写经 `ServerTaskQueue`
- 同一 ToolRegistry 服务游戏内对话、MCP、外部模组
- 每轮对话经 Tool Gate 过滤工具 schema
- 截图 base64 进入下一轮 multimodal 请求（OpenAI / Anthropic / Gemini）
