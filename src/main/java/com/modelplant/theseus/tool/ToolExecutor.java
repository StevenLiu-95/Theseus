package com.modelplant.theseus.tool;

import com.modelplant.theseus.api.gate.GateContext;
import com.modelplant.theseus.api.llm.ToolCall;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.api.tool.ToolContext;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.runtime.ServerTaskQueue;
import com.modelplant.theseus.runtime.TheseusExecutors;
import com.modelplant.theseus.session.AiSession;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public final class ToolExecutor {
    private ToolExecutor() {
    }

    public static CompletableFuture<ToolResult> execute(ServerPlayer player, AiSession session, ToolCall call, GateContext gateContext) {
        Tool tool = TheseusToolRegistry.get().get(call.name()).orElse(null);
        if (tool == null) {
            return CompletableFuture.completedFuture(ToolResult.error("Unknown tool: " + call.name()));
        }

        ToolContext context = new ToolContext(
                player.getServer(),
                player,
                session.id(),
                call.arguments(),
                gateContext
        );

        if (tool.requiresMainThread()) {
            return ServerTaskQueue.get().submit(() -> invoke(tool, context));
        }
        return CompletableFuture.supplyAsync(() -> invoke(tool, context), TheseusExecutors.workers());
    }

    private static ToolResult invoke(Tool tool, ToolContext context) {
        try {
            return tool.execute(context);
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
