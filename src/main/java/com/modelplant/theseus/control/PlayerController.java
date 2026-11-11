package com.modelplant.theseus.control;

import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.network.PlayerControlPayload;
import com.modelplant.theseus.network.TheseusNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerController {
    private static final PlayerController INSTANCE = new PlayerController();
    private static final int MAX_GOTO_TICKS = 20 * 45;

    private final Map<UUID, MoveTask> moves = new ConcurrentHashMap<>();

    private PlayerController() {
    }

    public static PlayerController get() {
        return INSTANCE;
    }

    public ToolResult moveTo(ServerPlayer player, double x, double y, double z, boolean sprint) {
        moves.put(player.getUUID(), new MoveTask(x, y, z, sprint, false, 0));
        TheseusNetwork.sendToPlayer(player, new PlayerControlPayload(PlayerControlPayload.Action.MOVE, x, y, z, 0, 0, sprint));
        return ToolResult.ok("Moving toward " + x + "," + y + "," + z);
    }

    public ToolResult gotoPos(ServerPlayer player, double x, double y, double z, boolean sprint) {
        moves.put(player.getUUID(), new MoveTask(x, y, z, sprint, true, 0));
        TheseusNetwork.sendToPlayer(player, new PlayerControlPayload(PlayerControlPayload.Action.MOVE, x, y, z, 0, 0, sprint));
        return ToolResult.ok("Navigating toward " + x + "," + y + "," + z + " (simple goto)");
    }

    public ToolResult look(ServerPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yHeadRot = yaw;
        TheseusNetwork.sendToPlayer(player, new PlayerControlPayload(PlayerControlPayload.Action.LOOK, 0, 0, 0, yaw, pitch, false));
        return ToolResult.ok("Look set to yaw=" + yaw + " pitch=" + pitch);
    }

    public ToolResult breakBlock(ServerPlayer player, BlockPos pos) {
        boolean removed = player.gameMode.destroyBlock(pos);
        return removed ? ToolResult.ok("Broke block at " + pos.toShortString())
                : ToolResult.error("Could not break block at " + pos.toShortString());
    }

    public ToolResult placeBlock(ServerPlayer player, BlockPos placePos) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ToolResult.error("Main hand is not a block item");
        }
        Level level = player.level();
        BlockState existing = level.getBlockState(placePos);
        if (!existing.canBeReplaced()) {
            return ToolResult.error("Target position occupied: " + placePos.toShortString() + " (" + existing + ")");
        }

        for (Direction towardNeighbor : Direction.values()) {
            BlockPos against = placePos.relative(towardNeighbor);
            BlockState againstState = level.getBlockState(against);
            if (againstState.isAir() || againstState.canBeReplaced()) {
                continue;
            }
            Direction hitFace = towardNeighbor.getOpposite();
            Vec3 hit = Vec3.atCenterOf(against).add(
                    hitFace.getStepX() * 0.5,
                    hitFace.getStepY() * 0.5,
                    hitFace.getStepZ() * 0.5
            );
            BlockHitResult blockHit = new BlockHitResult(hit, hitFace, against, false);
            BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, blockHit);
            var result = blockItem.place(ctx);
            if (result.consumesAction()) {
                return ToolResult.ok("Placed block at " + placePos.toShortString() + " against " + against.toShortString());
            }
        }

        // Fallback: click the replacement cell from above (creative-like)
        BlockHitResult fallback = new BlockHitResult(Vec3.atCenterOf(placePos), Direction.UP, placePos, false);
        BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, fallback);
        var result = blockItem.place(ctx);
        return result.consumesAction()
                ? ToolResult.ok("Placed block at " + placePos.toShortString())
                : ToolResult.error("Failed to place block at " + placePos.toShortString() + " (no valid adjacent face / placement rejected)");
    }

    public ToolResult useItem(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return ToolResult.error("Main hand empty");
        }
        var result = stack.use(player.level(), player, InteractionHand.MAIN_HAND);
        return ToolResult.ok("Used item: " + result.getResult());
    }

    public void onServerTick(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, MoveTask>> it = moves.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MoveTask> entry = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            MoveTask task = entry.getValue();
            task.ticks++;
            if (task.ticks > MAX_GOTO_TICKS) {
                it.remove();
                continue;
            }

            Vec3 target = new Vec3(task.x, task.y, task.z);
            Vec3 pos = player.position();
            Vec3 delta = target.subtract(pos);
            double horiz = delta.horizontalDistance();
            double vert = Math.abs(delta.y);
            double arrive = task.navigate ? 0.75 : 0.4;
            if (horiz < arrive && (!task.navigate || vert < 1.5)) {
                it.remove();
                continue;
            }

            Vec3 flat = new Vec3(delta.x, 0, delta.z);
            if (flat.lengthSqr() < 1.0E-6) {
                flat = new Vec3(0, 0, 1);
            }
            Vec3 dir = flat.normalize().scale(task.sprint ? 0.28 : 0.18);
            player.setDeltaMovement(dir.x, player.getDeltaMovement().y, dir.z);
            player.hurtMarked = true;

            float yaw = (float) (Math.toDegrees(Math.atan2(-dir.x, dir.z)));
            float pitch = task.navigate ? (float) (-Math.toDegrees(Math.atan2(delta.y, Math.max(horiz, 0.001)))) : player.getXRot();
            pitch = Math.max(-90f, Math.min(90f, pitch));
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yHeadRot = yaw;

            if (task.navigate) {
                maybeStepOrJump(player, dir, delta.y);
            }
        }
    }

    private static void maybeStepOrJump(ServerPlayer player, Vec3 dir, double deltaY) {
        if (!player.onGround()) {
            return;
        }
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        BlockPos ahead = feet.offset(
                (int) Math.signum(dir.x == 0 ? 0 : dir.x),
                0,
                (int) Math.signum(dir.z == 0 ? 0 : dir.z)
        );
        if (ahead.equals(feet)) {
            ahead = feet.relative(player.getDirection());
        }

        boolean bodyBlocked = !level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty()
                || !level.getBlockState(ahead.above()).getCollisionShape(level, ahead.above()).isEmpty();
        boolean stepUpClear = level.getBlockState(ahead.above()).getCollisionShape(level, ahead.above()).isEmpty()
                && level.getBlockState(ahead.above(2)).getCollisionShape(level, ahead.above(2)).isEmpty();
        boolean needClimb = deltaY > 0.6;

        if ((bodyBlocked && stepUpClear) || needClimb) {
            player.jumpFromGround();
        }
    }

    private static final class MoveTask {
        private final double x;
        private final double y;
        private final double z;
        private final boolean sprint;
        private final boolean navigate;
        private int ticks;

        private MoveTask(double x, double y, double z, boolean sprint, boolean navigate, int ticks) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.sprint = sprint;
            this.navigate = navigate;
            this.ticks = ticks;
        }
    }
}
