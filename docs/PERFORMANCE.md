# 性能与多人注意事项

## 线程模型

- LLM / MCP HTTP：`theseus-worker-*` 线程池
- 世界写入 / 玩家与生物操控：服务端主线程 `ServerTaskQueue`
- 每 tick 处理有限队列任务，避免尖刺卡顿

## 配额

- `maxBlockChangesPerTick`：直接改方块预算
- `maxActionRadius`：操作半径
- `maxPossessions`：附身数量
- `maxToolIterations`：单轮对话工具循环上限

## 多人

- 默认危险工具需 OP（`requireOpForDangerous`）
- 指令工具受 `commandWhitelist` + 权限等级约束
- 审计日志：`logs/theseus/audit.log`
- MCP 默认绑定 `127.0.0.1`，生产环境务必设置 `mcpAuthToken`

## 排查

- `/theseus status` 查看队列积压
- 日志搜索 `[audit]` / `Theseus`
