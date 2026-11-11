package com.modelplant.theseus.world;

import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.runtime.ServerTaskQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class WorldEditService {
    private WorldEditService() {
    }

    public static ToolResult setBlock(ServerPlayer player, BlockPos pos, String blockId) {
        if (!withinRadius(player, pos)) {
            return ToolResult.error("Outside allowed action radius");
        }
        Block block = resolve(blockId);
        if (block == null) {
            return ToolResult.error("Unknown block: " + blockId);
        }
        if (!ServerTaskQueue.get().tryConsumeBlockChangeBudget(1)) {
            return ToolResult.error("Block change budget exhausted this tick");
        }
        ServerLevel level = player.serverLevel();
        boolean ok = level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        return ok ? ToolResult.ok("Set " + blockId + " at " + pos.toShortString())
                : ToolResult.error("Failed to set block");
    }

    public static ToolResult fill(ServerPlayer player, BlockPos a, BlockPos b, String blockId) {
        Block block = resolve(blockId);
        if (block == null) {
            return ToolResult.error("Unknown block: " + blockId);
        }
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        if (!withinRadius(player, min) || !withinRadius(player, max)) {
            return ToolResult.error("Region outside allowed action radius");
        }
        int volume = (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        int budget = ServerTaskQueue.get().remainingBlockBudget();
        if (volume > budget) {
            return ToolResult.error("Fill volume " + volume + " exceeds remaining tick budget " + budget);
        }
        ServerLevel level = player.serverLevel();
        BlockState state = block.defaultBlockState();
        int changed = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!ServerTaskQueue.get().tryConsumeBlockChangeBudget(1)) {
                break;
            }
            if (level.setBlock(pos, state, Block.UPDATE_CLIENTS)) {
                changed++;
            }
        }
        return ToolResult.ok("Filled " + changed + " blocks with " + blockId);
    }

    private static Block resolve(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId.contains(":") ? blockId : "minecraft:" + blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(id);
    }

    private static boolean withinRadius(ServerPlayer player, BlockPos pos) {
        double max = TheseusServerConfig.MAX_ACTION_RADIUS.getAsInt();
        return player.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= max * max;
    }
}
