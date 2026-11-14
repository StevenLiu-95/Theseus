# 多版本移植笔记

当前主线：**Minecraft 1.21.1 / NeoForge 21.1.x / Java 21**。

后续移植到其它 1.21.x 或更新大版本时，优先检查：

1. **Gradle / MDK**：更换对应 `MDK-*-ModDevGradle`，更新 `minecraft_version`、`neo_version`、Parchment。
2. **网络 Payload**：`CustomPacketPayload` / `PayloadRegistrar` / `StreamCodec` API 是否变更。
3. **指令**：Brigadier 注册事件与权限 API。
4. **玩家操控**：`ServerPlayerGameMode.destroyBlock`、`BlockItem.place`、移动同步。
5. **截图**：`Screenshot.takeScreenshot`、`NativeImage` 写文件 API。
6. **实体查询**：`ServerLevel.getEntity(UUID)` 是否仍可用。
7. **配置**：`ModConfigSpec` / `ModConfig.Type.COMMON|CLIENT`；玩家 LLM 偏好在 `PlayerConfigStore`（非世界存档）。

建议策略：保持 `api/` 包稳定，把版本差异隔离在 `control/`、`vision/`、`network/`、`world/`。
