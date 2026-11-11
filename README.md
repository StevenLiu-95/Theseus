# Theseus

NeoForge 1.21.1 模组：将 LLM 与外部 Agent（MCP）接入 Minecraft，通过可扩展工具门控系统读写世界、执行指令、操控玩家/生物，并为视觉模型提供截图能力。

## 要求

- JDK 21+
- Minecraft 1.21.1
- NeoForge 21.1.x

## 构建

需要 **JDK 21**。

```bash
./gradlew build --init-script init.gradle
```

产物：`build/libs/theseus-0.1.0.jar`

开发运行：

```bash
./gradlew runClient --init-script init.gradle
./gradlew runServer --init-script init.gradle
```

若本机无法直连 `maven.neoforged.net`，可：

1. 开启本地 HTTP 代理（默认脚本使用 `127.0.0.1:7897`）
2. 运行 `powershell -File scripts/vendor-and-build.ps1` 将依赖填充到 `local-maven/`
3. 之后用 `--init-script init.gradle` 走本地缓存构建

## 快速开始 / 验收

1. 进入世界后执行 `/theseus help`
2. 默认 provider 为离线 `mock`，可直接对话：
   - `/theseus chat hello`
   - `/theseus chat TOOL get_player_info`
   - `/theseus chat TOOL echo {"message":"hi"}`
3. 切换真实模型（需 OP；`provider` / `model` / `key` 支持 Tab 补全）：
   - `/theseus config provider openai`（补全：mock / ollama / openai / deepseek / qwen / anthropic / gemini）
   - `/theseus config model gpt-4o-mini`（按当前 provider 补全推荐模型名）
   - `/theseus config key openai sk-...`
4. 或下载并启用本地模型（需本机安装 [Ollama](https://ollama.com)）：
   - `/theseus local list`
   - `/theseus local download qwen3-8b`
   - `/theseus local use qwen3-8b`
   - `/theseus chat 你好`
5. 查看当前门控后可见工具：`/theseus tools`

**Mock 冒烟清单**：`help` → `tools` → `chat hello` → `chat TOOL get_player_info` → `chat TOOL echo {"message":"hi"}` 均应有回包。

API Key 保存在 `config/theseus/apikeys.json`（Base64 混淆，不进世界存档）。

## 指令一览

| 指令 | 说明 |
|------|------|
| `/theseus chat\|say <msg>` | 与 AI 对话 |
| `/theseus session new\|clear\|status` | 会话管理 |
| `/theseus tools` | 列出当前可见工具 |
| `/theseus providers` | 列出 LLM Provider |
| `/theseus local list\|status\|download\|use` | 本地模型目录 / 下载 / 启用 |
| `/theseus config show\|provider\|model\|key\|url\|ollama\|iterations` | 配置（切换 provider 自动切 URL；`iterations` 设置每轮最大工具调用次数 1–32） |
| `/theseus mcp start\|stop` | MCP 桥开关 |
| `/theseus status` | 运行状态 |

辅助 GUI：`Ctrl+Alt+H`

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

切换 `/theseus config provider <id>` 会自动使用该 Provider 自己的 URL。自定义：`/theseus config url <provider> <url>`（也可用 `config/theseus-server.toml` 里的 `openaiBaseUrl` / `deepseekBaseUrl` / `qwenBaseUrl` / `anthropicBaseUrl` / `geminiBaseUrl` / `ollamaBaseUrl`）。

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
- `GET /mcp/tools?player=Steve`（`player` 可选；省略则返回未门控全量目录）
- `POST /mcp/call` `{"tool":"get_player_info","arguments":{},"player":"Steve"}`（**`player` 必填**，禁止默认落到任意在线玩家）
- `POST /mcp` JSON-RPC：`initialize` / `tools/list` / `tools/call`（`tools/call` 同样要求 `params.player`）

可选 Bearer Token：`mcpAuthToken`。MCP `tools/call` 会写入审计日志。

Cursor 等外部 Agent 可把该桥当作 MCP/HTTP 工具后端。

## 工具能力（capability）

`read` `echo` `command` `control` `world_edit` `possess` `vision` `misc`

危险能力默认需要 OP；可用服务端配置 `enabledCapabilities` / `requireOpForDangerous` 调整。

## 其它模组扩展

见 [docs/API.md](docs/API.md)。核心入口：`com.modelplant.theseus.api.TheseusAPI`。

## 多版本移植

见 [docs/PORTING.md](docs/PORTING.md)。当前仅正式支持 1.21.1。

## 已知限制

- `player_goto` 为简单直线导航 + 跳跃/上台阶，不是完整 A* / Baritone
- OpenAI 兼容流式仅在**无 tool schema 的纯文本轮**启用；带工具调用时走非流式
- 截图经网络传 PNG 字节（有大小上限）；过大时仅保留磁盘路径，vision 附件可能缺失
- MCP SSE 仅为轻量 endpoint 通告，非完整双向 Streamable HTTP 会话
- 操控他人角色需要 OP（`TheseusPermissions.canControlPlayers`）

## 架构要点

- 服务端权威执行世界变更
- LLM/MCP I/O 在工作线程，回写经 `ServerTaskQueue`
- 同一 ToolRegistry 服务游戏内对话、MCP、外部模组
- 每轮对话经 Tool Gate 过滤工具 schema
- 截图 base64 进入下一轮 multimodal 请求（OpenAI / Anthropic / Gemini）
