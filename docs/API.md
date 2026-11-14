# Theseus 对外 API

其它模组可通过依赖 Theseus JAR 并使用 `com.modelplant.theseus.api` 包接入。

## 入口

```java
import com.modelplant.theseus.api.TheseusAPI;
import com.modelplant.theseus.api.event.RegisterTheseusToolsEvent;
import net.neoforged.neoforge.common.NeoForge;

NeoForge.EVENT_BUS.addListener((RegisterTheseusToolsEvent event) -> {
    event.register(myTool);
});

TheseusAPI.registerProvider(myProvider);
TheseusAPI.registerGateRule(myRule);
```

## 自定义 Tool

实现 `Tool`：

- `schema()`：名称、描述、JSON Schema 参数
- `capability()`：门控用能力标签
- `requiresMainThread()`：是否必须在服务端主线程执行
- `execute(ToolContext)`：返回 `ToolResult`

## 自定义 Provider

实现 `LlmProvider`，在 `RegisterTheseusProvidersEvent` 中注册。

## 自定义门控

实现 `GateRule`，`priority` 越小越先执行；`allow == false` 则隐藏工具。  
也可监听 `TheseusGateEvent` 修改本轮可见工具列表。

## 会话与任务队列

- `TheseusAPI.sessions()`：按玩家管理 AI 会话
- `TheseusAPI.taskQueue()`：把任务投递到服务端主线程

## 玩家 LLM 配置

- `TheseusAPI.playerSettings(uuid)`：只读快照（provider / model / iterations / URL 覆盖等）
- `TheseusAPI.playerHasApiKey(uuid, provider)`：是否已配置密钥
- `TheseusAPI.playerApiKey(uuid, provider)`：明文密钥（**仅供受信扩展模组**；勿写入日志或二次持久化）

配置文件：`config/theseus/players/<uuid>.json`。  
文件中的 API Key 为 **Base64 混淆，不是加密**；任何能读服务器配置目录的进程都可还原。不要把该目录同步到公开仓库。

## 注意事项

- 不要在工作线程直接改世界；使用 `ServerTaskQueue`
- 高危能力应使用独立 capability，并尊重 OP / 配额配置
- 玩家之间不得共享或串用对方的 API Key
