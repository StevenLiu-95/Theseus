package com.modelplant.theseus.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.config.TheseusServerConfig;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
    private static final double STUCK_EPSILON_SQR = 0.04; // ~0.2 blocks of progress

    private final Map<UUID, MoveTask> moves = new ConcurrentHashMap<>();
    private final Map<UUID, FocusLock> focuses = new ConcurrentHashMap<>();
    private final Map<UUID, CombatTask> engages = new ConcurrentHashMap<>();
    private final Map<UUID, NavStatus> lastNavStatus = new ConcurrentHashMap<>();
    private final Map<UUID, CombatStatus> lastCombatStatus = new ConcurrentHashMap<>();

    private PlayerController() {
    }

    public static PlayerController get() {
        return INSTANCE;
    }

    public ToolResult moveTo(ServerPlayer player, double x, double y, double z, boolean sprint) {
        return startPointMove(player, x, y, z, sprint, false, 0.4);
    }

    public ToolResult gotoPos(ServerPlayer player, double x, double y, double z, boolean sprint) {
        return startPointMove(player, x, y, z, sprint, true, 0.75);
    }

    /**
     * Follow a living (or any) entity until within {@code standoff} horizontal blocks.
     * {@code standoff <= 0} uses a melee-friendly default from the player's interaction range.
     */
    public ToolResult followEntity(ServerPlayer player, UUID entityId, boolean sprint, boolean navigate, double standoff) {
        Entity entity = resolveEntity(player, entityId);
        if (entity == null) {
            return ToolResult.error("Entity not found: " + entityId);
        }
        if (!entity.isAlive()) {
            return ToolResult.error("Entity is not alive");
        }
        Vec3 pos = entity.position();
        if (!withinActionRadius(player, pos.x, pos.y, pos.z)) {
            return ToolResult.error("Entity outside allowed action radius");
        }
        // Engaging owns movement; standalone follow cancels engage.
        clearEngage(player.getUUID(), EndReason.CANCELLED);
        double stopAt = standoff > 0 ? standoff : defaultMeleeStandoff(player);
        MoveTask task = MoveTask.follow(entityId, pos.x, pos.y, pos.z, sprint, navigate, stopAt, player.position());
        moves.put(player.getUUID(), task);
        TheseusNetwork.sendToPlayer(player,
                new PlayerControlPayload(PlayerControlPayload.Action.MOVE, pos.x, pos.y, pos.z, 0, 0, sprint));
        JsonObject data = new JsonObject();
        data.addProperty("mode", "follow");
        data.addProperty("entity_uuid", entityId.toString());
        data.addProperty("standoff", stopAt);
        data.addProperty("sprint", sprint);
        data.addProperty("navigate", navigate);
        lastNavStatus.put(player.getUUID(), NavStatus.active(task));
        return ToolResult.ok("Following entity " + entity.getName().getString()
                + " (standoff=" + String.format(Locale.ROOT, "%.2f", stopAt) + ")", data);
    }

    private ToolResult startPointMove(
            ServerPlayer player,
            double x,
            double y,
            double z,
            boolean sprint,
            boolean navigate,
            double standoff
    ) {
        clearEngage(player.getUUID(), EndReason.CANCELLED);
        MoveTask task = MoveTask.point(x, y, z, sprint, navigate, standoff, player.position());
        moves.put(player.getUUID(), task);
        TheseusNetwork.sendToPlayer(player,
                new PlayerControlPayload(PlayerControlPayload.Action.MOVE, x, y, z, 0, 0, sprint));
        lastNavStatus.put(player.getUUID(), NavStatus.active(task));
        String kind = navigate ? "Navigating toward " : "Moving toward ";
        String note = navigate ? " (simple goto, not A*)" : " (direct velocity, no obstacle avoidance)";
        return ToolResult.ok(kind + x + "," + y + "," + z + note);
    }

    /** Cancel active navigation and combat engage for this player. */
    public ToolResult stopAll(ServerPlayer player) {
        UUID id = player.getUUID();
        boolean hadMove = moves.remove(id) != null;
        boolean hadEngage = clearEngage(id, EndReason.CANCELLED);
        player.setSprinting(false);
        if (hadMove) {
            lastNavStatus.put(id, NavStatus.finished(EndReason.CANCELLED, 0, null, 0, 0, 0));
        }
        JsonObject data = new JsonObject();
        data.addProperty("stopped_move", hadMove);
        data.addProperty("stopped_engage", hadEngage);
        if (!hadMove && !hadEngage) {
            return ToolResult.ok("No active navigation or engage", data);
        }
        return ToolResult.ok("Stopped active control tasks", data);
    }

    public void clearControl(UUID playerId) {
        moves.remove(playerId);
        clearEngage(playerId, EndReason.CANCELLED);
        clearFocus(playerId);
        lastNavStatus.remove(playerId);
        lastCombatStatus.remove(playerId);
    }

    public ToolResult navStatus(ServerPlayer player) {
        UUID id = player.getUUID();
        MoveTask move = moves.get(id);
        CombatTask combat = engages.get(id);
        JsonObject data = new JsonObject();
        data.addProperty("move_busy", move != null);
        data.addProperty("engage_busy", combat != null);
        NavStatus nav = lastNavStatus.get(id);
        if (move != null) {
            data.add("move", move.toJson(true));
        } else if (nav != null) {
            data.add("last_move", nav.toJson());
        }
        CombatStatus combatStatus = lastCombatStatus.get(id);
        if (combat != null) {
            data.add("engage", combat.toJson(true));
        } else if (combatStatus != null) {
            data.add("last_engage", combatStatus.toJson());
        }
        data.addProperty("focus_locked", focuses.containsKey(id));
        return ToolResult.okJson(data);
    }

    /**
     * Server-tick melee engage: chase target, wait for attack cooldown, swing until dead / max hits / timeout.
     */
    public ToolResult engageEntity(
            ServerPlayer player,
            UUID entityId,
            boolean untilDead,
            int maxHits,
            boolean sprint,
            int timeoutTicks
    ) {
        Entity entity = resolveEntity(player, entityId);
        if (entity == null) {
            return ToolResult.error("Entity not found: " + entityId);
        }
        if (entity == player) {
            return ToolResult.error("Cannot engage self");
        }
        if (!entity.isAlive()) {
            return ToolResult.error("Entity is not alive");
        }
        if (!entity.isAttackable() || entity.skipAttackInteraction(player)) {
            return ToolResult.error("Entity is not attackable");
        }
        Vec3 aim = aimPoint(entity);
        if (!withinActionRadius(player, aim.x, aim.y, aim.z)) {
            return ToolResult.error("Entity outside allowed action radius");
        }

        int cappedHits = maxHits <= 0 ? Integer.MAX_VALUE : Math.max(1, maxHits);
        int maxTicks = timeoutTicks > 0
                ? timeoutTicks
                : TheseusServerConfig.ENGAGE_MAX_TICKS.getAsInt();
        CombatTask task = new CombatTask(entityId, untilDead, cappedHits, sprint, maxTicks);
        engages.put(player.getUUID(), task);
        // Seed follow + focus immediately.
        double standoff = defaultMeleeStandoff(player);
        moves.put(player.getUUID(), MoveTask.follow(
                entityId, entity.getX(), entity.getY(), entity.getZ(), sprint, true, standoff, player.position()));
        focuses.put(player.getUUID(), FocusLock.entity(entityId));
        lastCombatStatus.put(player.getUUID(), CombatStatus.active(task));
        lastNavStatus.put(player.getUUID(), NavStatus.active(moves.get(player.getUUID())));

        JsonObject data = new JsonObject();
        data.addProperty("entity_uuid", entityId.toString());
        data.addProperty("until_dead", untilDead);
        data.addProperty("max_hits", cappedHits == Integer.MAX_VALUE ? -1 : cappedHits);
        data.addProperty("timeout_ticks", maxTicks);
        data.addProperty("sprint", sprint);
        data.addProperty("standoff", standoff);
        return ToolResult.ok("Engaging " + entity.getName().getString()
                + (untilDead ? " until defeated" : " (max_hits=" + cappedHits + ")"), data);
    }

    /** Absolute yaw/pitch look. Clears any active focus lock. */
    public ToolResult look(ServerPlayer player, float yaw, float pitch) {
        clearFocus(player.getUUID());
        float clampedPitch = clampPitch(pitch);
        applyLook(player, yaw, clampedPitch, true);
        return ToolResult.ok("Look set to yaw=" + yaw + " pitch=" + clampedPitch);
    }

    /** One-shot look toward a world point. Clears focus. */
    public ToolResult lookAtPoint(ServerPlayer player, double x, double y, double z) {
        if (!withinActionRadius(player, x, y, z)) {
            return ToolResult.error("Target outside allowed action radius");
        }
        clearFocus(player.getUUID());
        float[] yp = yawPitchToward(player, new Vec3(x, y, z));
        applyLook(player, yp[0], yp[1], true);
        JsonObject data = lookData("point", yp[0], yp[1]);
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        return ToolResult.ok("Looking at point " + x + "," + y + "," + z, data);
    }

    /** One-shot look toward an entity. Clears focus. */
    public ToolResult lookAtEntity(ServerPlayer player, UUID entityId) {
        Entity entity = resolveEntity(player, entityId);
        if (entity == null) {
            return ToolResult.error("Entity not found: " + entityId);
        }
        Vec3 aim = aimPoint(entity);
        if (!withinActionRadius(player, aim.x, aim.y, aim.z)) {
            return ToolResult.error("Entity outside allowed action radius");
        }
        clearFocus(player.getUUID());
        float[] yp = yawPitchToward(player, aim);
        applyLook(player, yp[0], yp[1], true);
        JsonObject data = lookData("entity", yp[0], yp[1]);
        data.addProperty("entity_uuid", entityId.toString());
        data.addProperty("entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        return ToolResult.ok("Looking at entity " + entity.getName().getString(), data);
    }

    /** Lock view onto a target each tick. Pass unlock=true via {@link #clearFocus(ServerPlayer)}. */
    public ToolResult focusAngles(ServerPlayer player, float yaw, float pitch) {
        float clampedPitch = clampPitch(pitch);
        focuses.put(player.getUUID(), FocusLock.angles(yaw, clampedPitch));
        applyLook(player, yaw, clampedPitch, true);
        JsonObject data = lookData("angles", yaw, clampedPitch);
        data.addProperty("locked", true);
        return ToolResult.ok("Focus locked to yaw=" + yaw + " pitch=" + clampedPitch, data);
    }

    public ToolResult focusPoint(ServerPlayer player, double x, double y, double z) {
        if (!withinActionRadius(player, x, y, z)) {
            return ToolResult.error("Target outside allowed action radius");
        }
        focuses.put(player.getUUID(), FocusLock.point(x, y, z));
        float[] yp = yawPitchToward(player, new Vec3(x, y, z));
        applyLook(player, yp[0], yp[1], true);
        JsonObject data = lookData("point", yp[0], yp[1]);
        data.addProperty("x", x);
        data.addProperty("y", y);
        data.addProperty("z", z);
        data.addProperty("locked", true);
        return ToolResult.ok("Focus locked on point " + x + "," + y + "," + z, data);
    }

    public ToolResult focusEntity(ServerPlayer player, UUID entityId) {
        Entity entity = resolveEntity(player, entityId);
        if (entity == null) {
            return ToolResult.error("Entity not found: " + entityId);
        }
        Vec3 aim = aimPoint(entity);
        if (!withinActionRadius(player, aim.x, aim.y, aim.z)) {
            return ToolResult.error("Entity outside allowed action radius");
        }
        focuses.put(player.getUUID(), FocusLock.entity(entityId));
        float[] yp = yawPitchToward(player, aim);
        applyLook(player, yp[0], yp[1], true);
        JsonObject data = lookData("entity", yp[0], yp[1]);
        data.addProperty("entity_uuid", entityId.toString());
        data.addProperty("locked", true);
        return ToolResult.ok("Focus locked on entity " + entity.getName().getString(), data);
    }

    public ToolResult clearFocus(ServerPlayer player) {
        boolean had = focuses.remove(player.getUUID()) != null;
        JsonObject data = new JsonObject();
        data.addProperty("locked", false);
        data.addProperty("was_locked", had);
        return ToolResult.ok(had ? "Focus unlocked" : "No active focus lock", data);
    }

    public void clearFocus(UUID playerId) {
        focuses.remove(playerId);
    }

    public boolean hasFocus(UUID playerId) {
        return focuses.containsKey(playerId);
    }

    public ToolResult breakBlock(ServerPlayer player, BlockPos pos) {
        boolean removed = player.gameMode.destroyBlock(pos);
        return removed ? ToolResult.ok("Broke block at " + pos.toShortString())
                : ToolResult.error("Could not break block at " + pos.toShortString());
    }

    /**
     * Melee-attack an entity with the player's current held item (vanilla attack path).
     *
     * @param lookAt when true, face the target before swinging
     * @param force  when true, attack even if the attack-strength cooldown is not ready
     */
    public ToolResult attackEntity(ServerPlayer player, UUID entityId, boolean lookAt, boolean force) {
        Entity entity = resolveEntity(player, entityId);
        if (entity == null) {
            return ToolResult.error("Entity not found: " + entityId);
        }
        if (entity == player) {
            return ToolResult.error("Cannot attack self");
        }
        if (!entity.isAlive()) {
            return ToolResult.error("Entity is not alive");
        }
        if (!entity.isAttackable() || entity.skipAttackInteraction(player)) {
            return ToolResult.error("Entity is not attackable");
        }

        Vec3 aim = aimPoint(entity);
        if (!withinActionRadius(player, aim.x, aim.y, aim.z)) {
            return ToolResult.error("Entity outside allowed action radius");
        }

        double reach = player.entityInteractionRange();
        // Padding mirrors vanilla interact tolerance for large hitboxes / edge grazes.
        if (!player.canInteractWithEntity(entity, 1.0D)) {
            JsonObject far = new JsonObject();
            far.addProperty("entity_uuid", entityId.toString());
            far.addProperty("distance", player.distanceTo(entity));
            far.addProperty("reach", reach);
            far.addProperty("in_reach", false);
            far.addProperty("x", entity.getX());
            far.addProperty("y", entity.getY());
            far.addProperty("z", entity.getZ());
            far.addProperty("suggestion",
                    "Use player_goto with entity_uuid, or player_engage, to close distance before attacking");
            return ToolResult.error("Target out of melee reach (distance="
                    + String.format(Locale.ROOT, "%.2f", player.distanceTo(entity))
                    + ", reach=" + String.format(Locale.ROOT, "%.2f", reach) + ")", far);
        }

        float strength = player.getAttackStrengthScale(0.5F);
        if (!force && strength < 0.9F) {
            JsonObject cool = new JsonObject();
            cool.addProperty("entity_uuid", entityId.toString());
            cool.addProperty("attack_strength", strength);
            cool.addProperty("cooldown_ready", false);
            return ToolResult.error("Attack cooldown not ready (strength="
                    + String.format(Locale.ROOT, "%.2f", strength)
                    + "). Wait a moment, or pass force=true.", cool);
        }

        if (lookAt) {
            float[] yp = yawPitchToward(player, aim);
            applyLook(player, yp[0], yp[1], true);
        }

        float healthBefore = entity instanceof LivingEntity living ? living.getHealth() : -1F;
        player.attack(entity);
        player.swing(InteractionHand.MAIN_HAND, true);

        JsonObject data = new JsonObject();
        data.addProperty("entity_uuid", entityId.toString());
        data.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        data.addProperty("name", entity.getName().getString());
        data.addProperty("distance", player.distanceTo(entity));
        data.addProperty("reach", reach);
        data.addProperty("attack_strength", strength);
        data.addProperty("look_at", lookAt);
        data.addProperty("force", force);
        data.addProperty("alive", entity.isAlive());
        if (entity instanceof LivingEntity livingAfter) {
            data.addProperty("health_before", healthBefore);
            data.addProperty("health", livingAfter.getHealth());
            data.addProperty("max_health", livingAfter.getMaxHealth());
        }
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty()) {
            data.addProperty("held_item", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        }

        String msg = entity.isAlive()
                ? "Attacked " + entity.getName().getString()
                : "Attacked and defeated " + entity.getName().getString();
        return ToolResult.ok(msg, data);
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
        tickEngages(event);
        tickMoves(event);
        tickFocuses(event);
    }

    private void tickEngages(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, CombatTask>> it = engages.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, CombatTask> entry = it.next();
            UUID playerId = entry.getKey();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
            CombatTask task = entry.getValue();
            if (player == null) {
                it.remove();
                moves.remove(playerId);
                focuses.remove(playerId);
                continue;
            }
            task.ticks++;
            if (task.ticks > task.maxTicks) {
                finishEngage(playerId, task, EndReason.TIMEOUT, null);
                it.remove();
                moves.remove(playerId);
                player.setSprinting(false);
                continue;
            }

            Entity target = resolveEntity(player, task.targetId);
            if (target == null) {
                finishEngage(playerId, task, EndReason.TARGET_LOST, null);
                it.remove();
                moves.remove(playerId);
                player.setSprinting(false);
                continue;
            }
            if (!target.isAlive()) {
                finishEngage(playerId, task, EndReason.DEFEATED, target);
                it.remove();
                moves.remove(playerId);
                player.setSprinting(false);
                continue;
            }

            focuses.put(playerId, FocusLock.entity(task.targetId));

            boolean inReach = player.canInteractWithEntity(target, 1.0D);
            if (!inReach) {
                // Keep / refresh chase while out of reach.
                double standoff = defaultMeleeStandoff(player);
                MoveTask existing = moves.get(playerId);
                if (existing == null || existing.followEntity == null
                        || !task.targetId.equals(existing.followEntity)) {
                    moves.put(playerId, MoveTask.follow(
                            task.targetId,
                            target.getX(),
                            target.getY(),
                            target.getZ(),
                            task.sprint,
                            true,
                            standoff,
                            player.position()));
                }
                lastCombatStatus.put(playerId, CombatStatus.active(task));
                continue;
            }

            // In reach: pause chase so we don't push through the target.
            moves.remove(playerId);

            float strength = player.getAttackStrengthScale(0.5F);
            if (strength < 0.9F) {
                lastCombatStatus.put(playerId, CombatStatus.active(task));
                continue;
            }

            float healthBefore = target instanceof LivingEntity living ? living.getHealth() : -1F;
            ToolResult hit = attackEntity(player, task.targetId, true, false);
            if (hit.success()) {
                task.hits++;
                if (healthBefore >= 0 && target instanceof LivingEntity livingAfter) {
                    task.damageDealt += Math.max(0F, healthBefore - livingAfter.getHealth());
                }
            }

            if (!target.isAlive()) {
                finishEngage(playerId, task, EndReason.DEFEATED, target);
                it.remove();
                player.setSprinting(false);
                continue;
            }
            if (!task.untilDead && task.hits >= task.maxHits) {
                finishEngage(playerId, task, EndReason.MAX_HITS, target);
                it.remove();
                player.setSprinting(false);
                continue;
            }
            lastCombatStatus.put(playerId, CombatStatus.active(task));
        }
    }

    private void tickMoves(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, MoveTask>> it = moves.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MoveTask> entry = it.next();
            UUID playerId = entry.getKey();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(playerId);
            if (player == null) {
                it.remove();
                focuses.remove(playerId);
                continue;
            }
            boolean focusLocked = focuses.containsKey(playerId);
            MoveTask task = entry.getValue();
            task.ticks++;

            int maxTicks = TheseusServerConfig.MAX_GOTO_TICKS.getAsInt();
            if (task.ticks > maxTicks) {
                finishMove(playerId, task, EndReason.TIMEOUT);
                it.remove();
                player.setSprinting(false);
                continue;
            }

            if (task.followEntity != null) {
                Entity follow = resolveEntity(player, task.followEntity);
                if (follow == null) {
                    finishMove(playerId, task, EndReason.TARGET_LOST);
                    it.remove();
                    player.setSprinting(false);
                    continue;
                }
                if (!follow.isAlive()) {
                    finishMove(playerId, task, EndReason.TARGET_DEAD);
                    it.remove();
                    player.setSprinting(false);
                    continue;
                }
                task.x = follow.getX();
                task.y = follow.getY();
                task.z = follow.getZ();
            }

            Vec3 target = new Vec3(task.x, task.y, task.z);
            Vec3 pos = player.position();
            Vec3 delta = target.subtract(pos);
            double horiz = delta.horizontalDistance();
            double vert = Math.abs(delta.y);
            double arrive = task.standoff;
            boolean arrived = horiz < arrive && (!task.navigate || task.followEntity != null || vert < 1.5);
            if (arrived) {
                // For entity follow used by engage, leave the move in place only if engage still needs chase;
                // point/follow completion removes the task.
                if (task.followEntity != null && engages.containsKey(playerId)) {
                    // Engage owns completion; stay parked near target without finishing as ARRIVED.
                    player.setSprinting(false);
                    lastNavStatus.put(playerId, NavStatus.active(task));
                    continue;
                }
                finishMove(playerId, task, EndReason.ARRIVED);
                it.remove();
                player.setSprinting(false);
                continue;
            }

            // Stuck detection: little horizontal progress while still far from goal.
            double progressSqr = pos.multiply(1, 0, 1).distanceToSqr(task.lastProgressPos.multiply(1, 0, 1));
            if (progressSqr < STUCK_EPSILON_SQR) {
                task.stuckTicks++;
            } else {
                task.stuckTicks = 0;
                task.lastProgressPos = pos;
            }
            if (task.stuckTicks >= TheseusServerConfig.NAV_STUCK_TICKS.getAsInt()) {
                finishMove(playerId, task, EndReason.STUCK);
                it.remove();
                player.setSprinting(false);
                // If engage was chasing and got stuck, end engage too.
                if (engages.containsKey(playerId)) {
                    CombatTask combat = engages.remove(playerId);
                    if (combat != null) {
                        finishEngage(playerId, combat, EndReason.STUCK, null);
                    }
                }
                continue;
            }

            Vec3 flat = new Vec3(delta.x, 0, delta.z);
            if (flat.lengthSqr() < 1.0E-6) {
                flat = new Vec3(0, 0, 1);
            }
            double speed = task.sprint
                    ? TheseusServerConfig.NAV_SPRINT_SPEED.getAsDouble()
                    : TheseusServerConfig.NAV_WALK_SPEED.getAsDouble();
            Vec3 dir = flat.normalize().scale(speed);
            player.setSprinting(task.sprint);
            player.setDeltaMovement(dir.x, player.getDeltaMovement().y, dir.z);
            player.hurtMarked = true;

            if (!focusLocked) {
                float yaw = (float) (Math.toDegrees(Math.atan2(-dir.x, dir.z)));
                float pitch = task.navigate
                        ? (float) (-Math.toDegrees(Math.atan2(delta.y, Math.max(horiz, 0.001))))
                        : player.getXRot();
                pitch = clampPitch(pitch);
                player.setYRot(yaw);
                player.setXRot(pitch);
                player.yHeadRot = yaw;
            }

            if (task.navigate) {
                maybeStepOrJump(player, dir);
            }
            lastNavStatus.put(playerId, NavStatus.active(task));
        }
    }

    private void tickFocuses(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, FocusLock>> it = focuses.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, FocusLock> entry = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            FocusLock lock = entry.getValue();
            Optional<float[]> look = resolveFocusLook(player, lock);
            if (look.isEmpty()) {
                it.remove();
                continue;
            }
            float[] yp = look.get();
            applyLook(player, yp[0], yp[1], true);
        }
    }

    private void finishMove(UUID playerId, MoveTask task, EndReason reason) {
        lastNavStatus.put(playerId, NavStatus.finished(reason, task.ticks, task.followEntity, task.x, task.y, task.z));
    }

    private void finishEngage(UUID playerId, CombatTask task, EndReason reason, Entity target) {
        lastCombatStatus.put(playerId, CombatStatus.finished(task, reason, target));
    }

    private boolean clearEngage(UUID playerId, EndReason reason) {
        CombatTask task = engages.remove(playerId);
        if (task == null) {
            return false;
        }
        lastCombatStatus.put(playerId, CombatStatus.finished(task, reason, null));
        return true;
    }

    private static double defaultMeleeStandoff(ServerPlayer player) {
        // Stop slightly inside interaction range so the next swing succeeds.
        return Math.max(1.5, player.entityInteractionRange() - 0.75);
    }

    private static Optional<float[]> resolveFocusLook(ServerPlayer player, FocusLock lock) {
        return switch (lock.kind) {
            case ANGLES -> Optional.of(new float[]{lock.yaw, lock.pitch});
            case POINT -> {
                if (!withinActionRadius(player, lock.x, lock.y, lock.z)) {
                    yield Optional.empty();
                }
                yield Optional.of(yawPitchToward(player, new Vec3(lock.x, lock.y, lock.z)));
            }
            case ENTITY -> {
                Entity entity = resolveEntity(player, lock.entityId);
                if (entity == null) {
                    yield Optional.empty();
                }
                Vec3 aim = aimPoint(entity);
                if (!withinActionRadius(player, aim.x, aim.y, aim.z)) {
                    yield Optional.empty();
                }
                yield Optional.of(yawPitchToward(player, aim));
            }
        };
    }

    private static void applyLook(ServerPlayer player, float yaw, float pitch, boolean syncClient) {
        float clampedPitch = clampPitch(pitch);
        player.setYRot(yaw);
        player.setXRot(clampedPitch);
        player.yHeadRot = yaw;
        if (syncClient) {
            TheseusNetwork.sendToPlayer(player,
                    new PlayerControlPayload(PlayerControlPayload.Action.LOOK, 0, 0, 0, yaw, clampedPitch, false));
        }
    }

    private static float[] yawPitchToward(ServerPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        Vec3 delta = target.subtract(eye);
        double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) (-Math.toDegrees(Math.atan2(delta.y, Math.max(horiz, 1.0E-6))));
        return new float[]{yaw, clampPitch(pitch)};
    }

    private static Vec3 aimPoint(Entity entity) {
        if (entity instanceof LivingEntity living) {
            return living.getEyePosition();
        }
        return entity.getBoundingBox().getCenter();
    }

    private static Entity resolveEntity(ServerPlayer player, UUID entityId) {
        if (entityId == null) {
            return null;
        }
        return player.serverLevel().getEntity(entityId);
    }

    private static boolean withinActionRadius(ServerPlayer player, double x, double y, double z) {
        double max = TheseusServerConfig.MAX_ACTION_RADIUS.getAsInt();
        return player.position().distanceToSqr(x, y, z) <= max * max;
    }

    private static float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }

    private static JsonObject lookData(String mode, float yaw, float pitch) {
        JsonObject data = new JsonObject();
        data.addProperty("mode", mode);
        data.addProperty("yaw", yaw);
        data.addProperty("pitch", pitch);
        return data;
    }

    /**
     * Jump only when the primary axis ahead is blocked and headroom allows a 1-block step-up.
     * Avoids blind jumps on {@code needClimb} without clearance.
     */
    private static void maybeStepOrJump(ServerPlayer player, Vec3 dir) {
        if (!player.onGround()) {
            return;
        }
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        int sx = Math.abs(dir.x) >= Math.abs(dir.z) ? (dir.x < 0 ? -1 : 1) : 0;
        int sz = Math.abs(dir.z) > Math.abs(dir.x) ? (dir.z < 0 ? -1 : 1) : 0;
        if (sx == 0 && sz == 0) {
            Direction facing = player.getDirection();
            sx = facing.getStepX();
            sz = facing.getStepZ();
        }
        BlockPos ahead = feet.offset(sx, 0, sz);
        if (ahead.equals(feet)) {
            return;
        }

        boolean feetBlocked = !level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty();
        boolean bodyClear = level.getBlockState(ahead.above()).getCollisionShape(level, ahead.above()).isEmpty();
        boolean headClear = level.getBlockState(ahead.above(2)).getCollisionShape(level, ahead.above(2)).isEmpty();
        // 1-high step only: solid at feet, clear at body + head. No blind climb jumps.
        if (feetBlocked && bodyClear && headClear) {
            player.jumpFromGround();
        }
    }

    enum EndReason {
        ARRIVED, TIMEOUT, STUCK, CANCELLED, TARGET_LOST, TARGET_DEAD, DEFEATED, MAX_HITS
    }

    private static final class MoveTask {
        private double x;
        private double y;
        private double z;
        private final UUID followEntity;
        private final boolean sprint;
        private final boolean navigate;
        private final double standoff;
        private int ticks;
        private int stuckTicks;
        private Vec3 lastProgressPos;

        private MoveTask(
                double x,
                double y,
                double z,
                UUID followEntity,
                boolean sprint,
                boolean navigate,
                double standoff,
                Vec3 lastProgressPos
        ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.followEntity = followEntity;
            this.sprint = sprint;
            this.navigate = navigate;
            this.standoff = standoff;
            this.ticks = 0;
            this.stuckTicks = 0;
            this.lastProgressPos = lastProgressPos;
        }

        private static MoveTask point(
                double x, double y, double z, boolean sprint, boolean navigate, double standoff, Vec3 from
        ) {
            return new MoveTask(x, y, z, null, sprint, navigate, standoff, from);
        }

        private static MoveTask follow(
                UUID entityId, double x, double y, double z, boolean sprint, boolean navigate, double standoff, Vec3 from
        ) {
            return new MoveTask(x, y, z, entityId, sprint, navigate, standoff, from);
        }

        private JsonObject toJson(boolean active) {
            JsonObject o = new JsonObject();
            o.addProperty("active", active);
            o.addProperty("mode", followEntity != null ? "follow" : "point");
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("standoff", standoff);
            o.addProperty("sprint", sprint);
            o.addProperty("navigate", navigate);
            o.addProperty("ticks", ticks);
            o.addProperty("stuck_ticks", stuckTicks);
            if (followEntity != null) {
                o.addProperty("entity_uuid", followEntity.toString());
            }
            return o;
        }
    }

    private static final class CombatTask {
        private final UUID targetId;
        private final boolean untilDead;
        private final int maxHits;
        private final boolean sprint;
        private final int maxTicks;
        private int ticks;
        private int hits;
        private float damageDealt;

        private CombatTask(UUID targetId, boolean untilDead, int maxHits, boolean sprint, int maxTicks) {
            this.targetId = targetId;
            this.untilDead = untilDead;
            this.maxHits = maxHits;
            this.sprint = sprint;
            this.maxTicks = maxTicks;
        }

        private JsonObject toJson(boolean active) {
            JsonObject o = new JsonObject();
            o.addProperty("active", active);
            o.addProperty("entity_uuid", targetId.toString());
            o.addProperty("until_dead", untilDead);
            o.addProperty("max_hits", maxHits == Integer.MAX_VALUE ? -1 : maxHits);
            o.addProperty("hits", hits);
            o.addProperty("damage_dealt", damageDealt);
            o.addProperty("ticks", ticks);
            o.addProperty("max_ticks", maxTicks);
            o.addProperty("sprint", sprint);
            return o;
        }
    }

    private record NavStatus(
            boolean active,
            EndReason reason,
            int ticks,
            String mode,
            UUID entityId,
            double x,
            double y,
            double z
    ) {
        private static NavStatus active(MoveTask task) {
            return new NavStatus(
                    true, null, task.ticks,
                    task.followEntity != null ? "follow" : "point",
                    task.followEntity, task.x, task.y, task.z);
        }

        private static NavStatus finished(
                EndReason reason, int ticks, UUID entityId, double x, double y, double z
        ) {
            return new NavStatus(
                    false, reason, ticks,
                    entityId != null ? "follow" : "point",
                    entityId, x, y, z);
        }

        private JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("active", active);
            if (reason != null) {
                o.addProperty("end_reason", reason.name());
            }
            o.addProperty("ticks", ticks);
            o.addProperty("mode", mode);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            if (entityId != null) {
                o.addProperty("entity_uuid", entityId.toString());
            }
            return o;
        }
    }

    private record CombatStatus(
            boolean active,
            EndReason reason,
            UUID targetId,
            int hits,
            float damageDealt,
            int ticks,
            Boolean targetAlive
    ) {
        private static CombatStatus active(CombatTask task) {
            return new CombatStatus(true, null, task.targetId, task.hits, task.damageDealt, task.ticks, true);
        }

        private static CombatStatus finished(CombatTask task, EndReason reason, Entity target) {
            Boolean alive = target == null ? null : target.isAlive();
            return new CombatStatus(false, reason, task.targetId, task.hits, task.damageDealt, task.ticks, alive);
        }

        private JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("active", active);
            if (reason != null) {
                o.addProperty("end_reason", reason.name());
            }
            o.addProperty("entity_uuid", targetId.toString());
            o.addProperty("hits", hits);
            o.addProperty("damage_dealt", damageDealt);
            o.addProperty("ticks", ticks);
            if (targetAlive != null) {
                o.addProperty("target_alive", targetAlive);
            }
            return o;
        }
    }

    private static final class FocusLock {
        private enum Kind { ANGLES, POINT, ENTITY }

        private final Kind kind;
        private final float yaw;
        private final float pitch;
        private final double x;
        private final double y;
        private final double z;
        private final UUID entityId;

        private FocusLock(
                Kind kind,
                float yaw,
                float pitch,
                double x,
                double y,
                double z,
                UUID entityId
        ) {
            this.kind = kind;
            this.yaw = yaw;
            this.pitch = pitch;
            this.x = x;
            this.y = y;
            this.z = z;
            this.entityId = entityId;
        }

        private static FocusLock angles(float yaw, float pitch) {
            return new FocusLock(Kind.ANGLES, yaw, pitch, 0, 0, 0, null);
        }

        private static FocusLock point(double x, double y, double z) {
            return new FocusLock(Kind.POINT, 0, 0, x, y, z, null);
        }

        private static FocusLock entity(UUID entityId) {
            return new FocusLock(Kind.ENTITY, 0, 0, 0, 0, 0, entityId);
        }
    }
}
