package com.modelplant.theseus.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.config.TheseusServerConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Locale;
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

    public ToolResult action(
            UUID sessionId,
            String entityUuid,
            String action,
            double x,
            double y,
            double z,
            String targetUuid,
            ServerPlayer player
    ) {
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
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "move" -> {
                possessions.put(uuid, possession.withGoal(new Goal(GoalType.MOVE, x, y, z, null, 0)));
                yield ToolResult.ok("Action queued: move");
            }
            case "look" -> {
                living.setYRot((float) x);
                living.setXRot((float) y);
                yield ToolResult.ok("Action queued: look");
            }
            case "follow_player" -> {
                possessions.put(uuid, possession.withGoal(new Goal(GoalType.FOLLOW, 0, 0, 0, null, 0)));
                yield ToolResult.ok("Action queued: follow_player");
            }
            case "chase" -> {
                UUID chaseTarget = parseUuid(targetUuid);
                if (chaseTarget == null) {
                    yield ToolResult.error("target_uuid required for possessed chase");
                }
                possessions.put(uuid, possession.withGoal(new Goal(GoalType.CHASE, 0, 0, 0, chaseTarget, 0)));
                yield ToolResult.ok("Action queued: chase");
            }
            case "engage" -> {
                UUID engageTarget = parseUuid(targetUuid);
                if (engageTarget == null) {
                    yield ToolResult.error("target_uuid required for possessed engage");
                }
                possessions.put(uuid, possession.withGoal(
                        new Goal(GoalType.ENGAGE, 0, 0, 0, engageTarget, TheseusServerConfig.ENGAGE_MAX_TICKS.getAsInt())));
                yield ToolResult.ok("Action queued: engage (chase+attack until dead or timeout)");
            }
            case "attack" -> attackAsPossessed(living, targetUuid, player);
            default -> ToolResult.error("Unknown action: " + action);
        };
    }

    /**
     * One-shot melee from a possessed living entity toward {@code targetUuid}.
     * Uses {@link Mob#doHurtTarget} when possible; otherwise applies attribute attack damage.
     */
    private ToolResult attackAsPossessed(LivingEntity attacker, String targetUuid, ServerPlayer commander) {
        if (targetUuid == null || targetUuid.isBlank()) {
            return ToolResult.error("target_uuid required for possessed attack");
        }
        UUID targetId;
        try {
            targetId = UUID.fromString(targetUuid.trim());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid target_uuid: " + targetUuid);
        }
        if (targetId.equals(attacker.getUUID())) {
            return ToolResult.error("Possessed entity cannot attack itself");
        }

        Entity target = find(commander.serverLevel(), targetId);
        if (!(target instanceof LivingEntity livingTarget) || !livingTarget.isAlive()) {
            return ToolResult.error("Attack target not found or not a living entity");
        }
        if (!livingTarget.isAttackable() || livingTarget.skipAttackInteraction(attacker)) {
            return ToolResult.error("Attack target is not attackable");
        }

        double max = TheseusServerConfig.MAX_ACTION_RADIUS.getAsInt();
        if (attacker.distanceToSqr(livingTarget) > max * max) {
            return ToolResult.error("Attack target outside allowed action radius");
        }

        // Soft melee reach: ~attacker width + 3 blocks (mobs vary; keep AI usable).
        double meleeReach = Math.max(2.5, attacker.getBbWidth() + 3.0);
        if (attacker.distanceTo(livingTarget) > meleeReach) {
            JsonObject far = new JsonObject();
            far.addProperty("distance", attacker.distanceTo(livingTarget));
            far.addProperty("reach", meleeReach);
            far.addProperty("target_uuid", targetId.toString());
            return ToolResult.error("Target out of possessed melee reach", far);
        }

        faceToward(attacker, livingTarget);
        float healthBefore = livingTarget.getHealth();
        boolean hit;
        if (attacker instanceof Mob mob) {
            boolean wasNoAi = mob.isNoAi();
            try {
                mob.setNoAi(false);
                hit = mob.doHurtTarget(livingTarget);
            } finally {
                mob.setNoAi(wasNoAi);
            }
        } else {
            float damage = 1.0F;
            if (attacker.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) {
                damage = (float) attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
            }
            hit = livingTarget.hurt(attacker.damageSources().mobAttack(attacker), Math.max(1.0F, damage));
        }

        JsonObject data = new JsonObject();
        data.addProperty("entity_uuid", attacker.getUUID().toString());
        data.addProperty("target_uuid", targetId.toString());
        data.addProperty("target_type", BuiltInRegistries.ENTITY_TYPE.getKey(livingTarget.getType()).toString());
        data.addProperty("hit", hit);
        data.addProperty("health_before", healthBefore);
        data.addProperty("health", livingTarget.getHealth());
        data.addProperty("alive", livingTarget.isAlive());
        data.addProperty("distance", attacker.distanceTo(livingTarget));
        String msg = hit
                ? (livingTarget.isAlive() ? "Possessed entity attacked target" : "Possessed entity defeated target")
                : "Possessed entity attack did not land (doHurtTarget/hurt returned false)";
        return ToolResult.ok(msg, data);
    }

    private static void faceToward(LivingEntity living, Entity target) {
        Vec3 from = living.getEyePosition();
        Vec3 to = target instanceof LivingEntity livingTarget
                ? livingTarget.getEyePosition()
                : target.getBoundingBox().getCenter();
        Vec3 delta = to.subtract(from);
        double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) (-Math.toDegrees(Math.atan2(delta.y, Math.max(horiz, 1.0E-6))));
        living.setYRot(yaw);
        living.setXRot(pitch);
        living.yHeadRot = yaw;
        living.yBodyRot = yaw;
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
            Goal goal = possession.goal;
            if (goal.type == GoalType.MOVE) {
                navigate(living, goal.x, goal.y, goal.z);
            } else if (goal.type == GoalType.FOLLOW) {
                navigate(living, owner.getX(), owner.getY(), owner.getZ());
            } else if (goal.type == GoalType.CHASE || goal.type == GoalType.ENGAGE) {
                Entity target = find(owner.serverLevel(), goal.targetId);
                if (!(target instanceof LivingEntity livingTarget) || !livingTarget.isAlive()) {
                    possessions.put(entry.getKey(), possession.withGoal(null));
                    continue;
                }
                double meleeReach = Math.max(2.5, living.getBbWidth() + 3.0);
                double dist = living.distanceTo(livingTarget);
                if (dist > meleeReach) {
                    navigate(living, livingTarget.getX(), livingTarget.getY(), livingTarget.getZ());
                } else if (goal.type == GoalType.ENGAGE) {
                    faceToward(living, livingTarget);
                    if (living instanceof Mob mob) {
                        boolean wasNoAi = mob.isNoAi();
                        try {
                            mob.setNoAi(false);
                            mob.doHurtTarget(livingTarget);
                        } finally {
                            mob.setNoAi(wasNoAi);
                        }
                    } else {
                        float damage = 1.0F;
                        if (living.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) {
                            damage = (float) living.getAttributeValue(Attributes.ATTACK_DAMAGE);
                        }
                        livingTarget.hurt(living.damageSources().mobAttack(living), Math.max(1.0F, damage));
                    }
                    if (!livingTarget.isAlive()) {
                        possessions.put(entry.getKey(), possession.withGoal(null));
                        continue;
                    }
                }
                if (goal.type == GoalType.ENGAGE) {
                    int remaining = goal.ticksRemaining - 1;
                    if (remaining <= 0) {
                        possessions.put(entry.getKey(), possession.withGoal(null));
                    } else {
                        possessions.put(entry.getKey(), possession.withGoal(
                                new Goal(GoalType.ENGAGE, 0, 0, 0, goal.targetId, remaining)));
                    }
                }
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

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record Possession(UUID sessionId, UUID ownerId, UUID entityId, Goal goal) {
        Possession withGoal(Goal goal) {
            return new Possession(sessionId, ownerId, entityId, goal);
        }
    }

    private enum GoalType {MOVE, FOLLOW, CHASE, ENGAGE}

    private record Goal(GoalType type, double x, double y, double z, UUID targetId, int ticksRemaining) {
    }
}
