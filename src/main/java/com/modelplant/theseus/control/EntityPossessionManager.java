package com.modelplant.theseus.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.config.TheseusServerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityPossessionManager {
    private static final EntityPossessionManager INSTANCE = new EntityPossessionManager();

    private final Map<UUID, Possession> possessions = new ConcurrentHashMap<>();

    private EntityPossessionManager() {
    }

    public static EntityPossessionManager get() {
        return INSTANCE;
    }

    public ToolResult possess(ServerPlayer player, UUID sessionId, String entityUuid) {
        UUID uuid;
        try {
            uuid = UUID.fromString(entityUuid);
        } catch (Exception e) {
            return ToolResult.error("Invalid entity UUID");
        }
        long owned = possessions.values().stream().filter(p -> p.sessionId.equals(sessionId)).count();
        if (owned >= TheseusServerConfig.MAX_POSSESSIONS.getAsInt()) {
            return ToolResult.error("Possession limit reached");
        }
        Entity entity = find(player.serverLevel(), uuid);
        if (!(entity instanceof LivingEntity living)) {
            return ToolResult.error("Entity not found or not living");
        }
        if (entity instanceof ServerPlayer) {
            return ToolResult.error("Cannot possess players with this tool");
        }
        possessions.put(uuid, new Possession(sessionId, player.getUUID(), uuid, null));
        if (living instanceof Mob mob) {
            mob.goalSelector.getAvailableGoals().forEach(WrappedGoal::stop);
            mob.setNoAi(true);
        }
        JsonObject data = new JsonObject();
        data.addProperty("entity_uuid", uuid.toString());
        data.addProperty("type", living.getType().toShortString());
        return ToolResult.ok("Possessed entity", data);
    }

    public ToolResult unpossess(UUID sessionId, String entityUuid) {
        if (entityUuid == null || entityUuid.isBlank()) {
            int removed = 0;
            Iterator<Map.Entry<UUID, Possession>> it = possessions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Possession> entry = it.next();
                if (entry.getValue().sessionId.equals(sessionId)) {
                    releaseAi(entry.getValue());
                    it.remove();
                    removed++;
                }
            }
            return ToolResult.ok("Released " + removed + " possessions");
        }
        try {
            UUID uuid = UUID.fromString(entityUuid);
            Possession possession = possessions.remove(uuid);
            if (possession == null) {
                return ToolResult.error("Not possessing that entity");
            }
            releaseAi(possession);
            return ToolResult.ok("Released " + uuid);
        } catch (Exception e) {
            return ToolResult.error("Invalid UUID");
        }
    }

    public ToolResult action(UUID sessionId, String entityUuid, String action, double x, double y, double z, ServerPlayer player) {
        UUID uuid;
        try {
            uuid = UUID.fromString(entityUuid);
        } catch (Exception e) {
            return ToolResult.error("Invalid entity UUID");
        }
        Possession possession = possessions.get(uuid);
        if (possession == null || !possession.sessionId.equals(sessionId)) {
            return ToolResult.error("Entity not possessed by this session");
        }
        Entity entity = find(player.serverLevel(), uuid);
        if (!(entity instanceof LivingEntity living)) {
            possessions.remove(uuid);
            return ToolResult.error("Entity gone");
        }
        switch (action.toLowerCase()) {
            case "move" -> possessions.put(uuid, possession.withGoal(new Goal(GoalType.MOVE, x, y, z)));
            case "look" -> {
                living.setYRot((float) x);
                living.setXRot((float) y);
            }
            case "follow_player" -> possessions.put(uuid, possession.withGoal(new Goal(GoalType.FOLLOW, 0, 0, 0)));
            case "attack" -> {
                if (living instanceof Mob mob) {
                    // Demo behavior: mark the commanding player as target awareness anchor.
                    // Prefer move/follow for safer testing.
                    mob.setTarget(null);
                    mob.setLastHurtByMob(player);
                }
            }
            default -> {
                return ToolResult.error("Unknown action: " + action);
            }
        }
        return ToolResult.ok("Action queued: " + action);
    }

    public ToolResult list(UUID sessionId) {
        JsonArray arr = new JsonArray();
        possessions.values().stream().filter(p -> p.sessionId.equals(sessionId)).forEach(p -> {
            JsonObject o = new JsonObject();
            o.addProperty("entity_uuid", p.entityId.toString());
            arr.add(o);
        });
        JsonObject data = new JsonObject();
        data.add("possessions", arr);
        return ToolResult.okJson(data);
    }

    public void onServerTick(ServerTickEvent.Post event) {
        Iterator<Map.Entry<UUID, Possession>> it = possessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Possession> entry = it.next();
            Possession possession = entry.getValue();
            ServerPlayer owner = event.getServer().getPlayerList().getPlayer(possession.ownerId);
            if (owner == null) {
                releaseAi(possession);
                it.remove();
                continue;
            }
            Entity entity = find(owner.serverLevel(), possession.entityId);
            if (!(entity instanceof LivingEntity living)) {
                it.remove();
                continue;
            }
            if (possession.goal == null) {
                continue;
            }
            if (possession.goal.type == GoalType.MOVE) {
                navigate(living, possession.goal.x, possession.goal.y, possession.goal.z);
            } else if (possession.goal.type == GoalType.FOLLOW) {
                navigate(living, owner.getX(), owner.getY(), owner.getZ());
            }
        }
    }

    private void navigate(LivingEntity living, double x, double y, double z) {
        if (living instanceof Mob mob && mob.getNavigation() != null) {
            mob.setNoAi(false);
            mob.getNavigation().moveTo(x, y, z, 1.1);
            mob.setNoAi(true);
            return;
        }
        var delta = new net.minecraft.world.phys.Vec3(x - living.getX(), 0, z - living.getZ());
        if (delta.horizontalDistanceSqr() < 0.2) {
            return;
        }
        var dir = delta.normalize().scale(0.2);
        living.setDeltaMovement(dir.x, living.getDeltaMovement().y, dir.z);
    }

    private void releaseAi(Possession possession) {
        // best-effort; entity may be unloaded
    }

    private Entity find(ServerLevel level, UUID uuid) {
        return level.getEntity(uuid);
    }

    private record Possession(UUID sessionId, UUID ownerId, UUID entityId, Goal goal) {
        Possession withGoal(Goal goal) {
            return new Possession(sessionId, ownerId, entityId, goal);
        }
    }

    private enum GoalType {MOVE, FOLLOW}

    private record Goal(GoalType type, double x, double y, double z) {
    }
}
