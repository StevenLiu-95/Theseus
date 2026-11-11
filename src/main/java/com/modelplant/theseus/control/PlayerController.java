package com.modelplant.theseus.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.network.PlayerControlPayload;
import com.modelplant.theseus.network.TheseusNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    public ToolResult selectHotbar(ServerPlayer player, Integer slot, String itemId) {
        Inventory inventory = player.getInventory();
        if (itemId != null && !itemId.isBlank()) {
            Optional<Item> item = resolveItem(itemId);
            if (item.isEmpty()) {
                return ToolResult.error("Unknown item: " + itemId);
            }
            int found = findSlotMatchingItem(inventory, item.get());
            if (found < 0) {
                return ToolResult.error("Item not in inventory: " + itemId);
            }
            if (Inventory.isHotbarSlot(found)) {
                return setSelectedSlot(player, found);
            }
            inventory.pickSlot(found);
            player.inventoryMenu.broadcastChanges();
            syncSelectedSlot(player);
            JsonObject data = selectedSlotData(player);
            return ToolResult.ok("Picked " + itemId + " into hotbar slot " + inventory.selected, data);
        }
        if (slot == null) {
            return ToolResult.error("Provide slot (0-8) or item id");
        }
        if (!Inventory.isHotbarSlot(slot)) {
            return ToolResult.error("Hotbar slot must be 0-8");
        }
        return setSelectedSlot(player, slot);
    }

    public ToolResult openInventory(ServerPlayer player) {
        TheseusNetwork.sendToPlayer(player,
                new PlayerControlPayload(PlayerControlPayload.Action.OPEN_INVENTORY, 0, 0, 0, 0, 0, false));
        return ToolResult.ok("Requested client inventory screen");
    }

    public ToolResult dropItem(ServerPlayer player, Integer slot, int count, boolean all) {
        Inventory inventory = player.getInventory();
        int targetSlot = slot == null ? inventory.selected : slot;
        if (targetSlot < 0 || targetSlot >= inventory.getContainerSize()) {
            return ToolResult.error("Invalid inventory slot: " + targetSlot);
        }
        ItemStack stack = inventory.getItem(targetSlot);
        if (stack.isEmpty()) {
            return ToolResult.error("Slot " + targetSlot + " is empty");
        }

        int removeCount;
        if (all || count <= 0) {
            removeCount = stack.getCount();
        } else {
            removeCount = Math.min(count, stack.getCount());
        }

        ItemStack dropped;
        if (slot == null && (all || count <= 0 || count >= stack.getCount())) {
            // Prefer vanilla selected-slot drop path when dropping the whole selected stack.
            boolean ok = player.drop(true);
            if (!ok) {
                return ToolResult.error("Drop cancelled");
            }
            JsonObject data = new JsonObject();
            data.addProperty("slot", inventory.selected);
            data.addProperty("dropped", removeCount);
            return ToolResult.ok("Dropped selected stack (" + removeCount + ")", data);
        }

        if (slot == null && removeCount == 1) {
            boolean ok = player.drop(false);
            if (!ok) {
                return ToolResult.error("Drop cancelled");
            }
            JsonObject data = new JsonObject();
            data.addProperty("slot", inventory.selected);
            data.addProperty("dropped", 1);
            return ToolResult.ok("Dropped 1 item from selected slot", data);
        }

        dropped = inventory.removeItem(targetSlot, removeCount);
        if (dropped.isEmpty()) {
            return ToolResult.error("Nothing removed from slot " + targetSlot);
        }
        String itemKey = BuiltInRegistries.ITEM.getKey(dropped.getItem()).toString();
        int droppedCount = dropped.getCount();
        var entity = player.drop(dropped, false);
        if (entity == null) {
            // Toss cancelled: put the stack back if still present.
            if (!dropped.isEmpty()) {
                ItemStack existing = inventory.getItem(targetSlot);
                if (existing.isEmpty()) {
                    inventory.setItem(targetSlot, dropped);
                } else {
                    inventory.add(dropped);
                }
            }
            player.inventoryMenu.broadcastChanges();
            return ToolResult.error("Drop cancelled");
        }
        player.inventoryMenu.broadcastChanges();
        JsonObject data = new JsonObject();
        data.addProperty("slot", targetSlot);
        data.addProperty("item", itemKey);
        data.addProperty("dropped", droppedCount);
        return ToolResult.ok("Dropped " + droppedCount + "x " + itemKey, data);
    }

    public ToolResult interactBlock(ServerPlayer player, BlockPos pos, Direction face, InteractionHand hand, boolean sneak) {
        Level level = player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return ToolResult.error("No block at " + pos.toShortString());
        }

        Direction hitFace = face == null ? Direction.UP : face;
        Vec3 hit = Vec3.atCenterOf(pos).add(
                hitFace.getStepX() * 0.5,
                hitFace.getStepY() * 0.5,
                hitFace.getStepZ() * 0.5
        );
        BlockHitResult blockHit = new BlockHitResult(hit, hitFace, pos, false);
        ItemStack stack = player.getItemInHand(hand);

        boolean wasShift = player.isShiftKeyDown();
        try {
            player.setShiftKeyDown(sneak);
            InteractionResult result = player.gameMode.useItemOn(player, level, stack, hand, blockHit);
            JsonObject data = new JsonObject();
            data.addProperty("x", pos.getX());
            data.addProperty("y", pos.getY());
            data.addProperty("z", pos.getZ());
            data.addProperty("face", hitFace.getSerializedName());
            data.addProperty("hand", hand.name().toLowerCase(Locale.ROOT));
            data.addProperty("sneak", sneak);
            data.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            data.addProperty("result", result.toString());
            if (result.consumesAction()) {
                return ToolResult.ok("Interacted with block: " + result, data);
            }
            return ToolResult.ok("Interact returned " + result + " (may be PASS/FAIL)", data);
        } finally {
            player.setShiftKeyDown(wasShift);
        }
    }

    public ToolResult inventorySnapshot(ServerPlayer player, boolean hotbarOnly, boolean includeEmpty) {
        Inventory inventory = player.getInventory();
        JsonArray slots = new JsonArray();
        int limit = hotbarOnly ? 9 : inventory.items.size();
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!includeEmpty && stack.isEmpty()) {
                continue;
            }
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.addProperty("hotbar", Inventory.isHotbarSlot(i));
            slot.addProperty("selected", i == inventory.selected);
            if (stack.isEmpty()) {
                slot.addProperty("empty", true);
            } else {
                slot.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                slot.addProperty("count", stack.getCount());
                slot.addProperty("name", stack.getHoverName().getString());
            }
            slots.add(slot);
        }

        ItemStack offhand = inventory.offhand.get(0);
        JsonObject data = new JsonObject();
        data.addProperty("selectedSlot", inventory.selected);
        data.add("slots", slots);
        if (!offhand.isEmpty() || includeEmpty) {
            JsonObject off = new JsonObject();
            off.addProperty("slot", 40);
            off.addProperty("empty", offhand.isEmpty());
            if (!offhand.isEmpty()) {
                off.addProperty("item", BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString());
                off.addProperty("count", offhand.getCount());
                off.addProperty("name", offhand.getHoverName().getString());
            }
            data.add("offhand", off);
        }
        return ToolResult.okJson(data);
    }

    private static ToolResult setSelectedSlot(ServerPlayer player, int slot) {
        player.getInventory().selected = slot;
        syncSelectedSlot(player);
        TheseusNetwork.sendToPlayer(player,
                new PlayerControlPayload(PlayerControlPayload.Action.SELECT, slot, 0, 0, 0, 0, false));
        return ToolResult.ok("Selected hotbar slot " + slot, selectedSlotData(player));
    }

    private static void syncSelectedSlot(ServerPlayer player) {
        player.connection.send(new ClientboundSetCarriedItemPacket(player.getInventory().selected));
    }

    private static JsonObject selectedSlotData(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        ItemStack stack = inventory.getSelected();
        JsonObject data = new JsonObject();
        data.addProperty("selectedSlot", inventory.selected);
        if (!stack.isEmpty()) {
            data.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            data.addProperty("count", stack.getCount());
            data.addProperty("name", stack.getHoverName().getString());
        } else {
            data.addProperty("empty", true);
        }
        return data;
    }

    private static Optional<Item> resolveItem(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return Optional.empty();
        }
        return Optional.of(BuiltInRegistries.ITEM.get(id));
    }

    private static int findSlotMatchingItem(Inventory inventory, Item item) {
        for (int i = 0; i < inventory.items.size(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        return -1;
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
