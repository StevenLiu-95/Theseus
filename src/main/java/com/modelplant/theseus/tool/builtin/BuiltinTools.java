package com.modelplant.theseus.tool.builtin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.modelplant.theseus.api.tool.Tool;
import com.modelplant.theseus.api.tool.ToolContext;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.api.tool.ToolSchema;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.control.EntityPossessionManager;
import com.modelplant.theseus.control.PlayerController;
import com.modelplant.theseus.permission.TheseusPermissions;
import com.modelplant.theseus.tool.TheseusToolRegistry;
import com.modelplant.theseus.vision.ScreenshotService;
import com.modelplant.theseus.world.WorldEditService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Locale;

public final class BuiltinTools {
    private BuiltinTools() {
    }

    public static void registerAll() {
        TheseusToolRegistry registry = TheseusToolRegistry.get();
        registry.register(echo());
        registry.register(getPlayerInfo());
        registry.register(getBlock());
        registry.register(listEntities());
        registry.register(executeCommand());
        registry.register(playerMove());
        registry.register(playerGoto());
        registry.register(playerLook());
        registry.register(playerBreakBlock());
        registry.register(playerPlaceBlock());
        registry.register(playerUseItem());
        registry.register(worldSetBlock());
        registry.register(worldFill());
        registry.register(possessEntity());
        registry.register(unpossess());
        registry.register(possessedAction());
        registry.register(takeScreenshot());
    }

    private static Tool echo() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("message", stringProp("Text to echo"));
                return new ToolSchema("echo", "Echo a message back (debug)", objectSchema(props, "message"));
            }

            @Override
            public String capability() {
                return "echo";
            }

            @Override
            public boolean requiresMainThread() {
                return false;
            }

            @Override
            public ToolResult execute(ToolContext context) {
                return ToolResult.ok(context.argString("message", ""));
            }
        };
    }

    private static Tool getPlayerInfo() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                return new ToolSchema("get_player_info", "Read the controlling player's position and state", objectSchema(new JsonObject()));
            }

            @Override
            public String capability() {
                return "read";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                ServerPlayer player = context.player();
                JsonObject data = new JsonObject();
                data.addProperty("name", player.getGameProfile().getName());
                data.addProperty("uuid", player.getUUID().toString());
                data.addProperty("x", player.getX());
                data.addProperty("y", player.getY());
                data.addProperty("z", player.getZ());
                data.addProperty("yaw", player.getYRot());
                data.addProperty("pitch", player.getXRot());
                data.addProperty("dimension", player.level().dimension().location().toString());
                data.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getName());
                data.addProperty("health", player.getHealth());
                data.addProperty("food", player.getFoodData().getFoodLevel());
                data.addProperty("level", player.experienceLevel);
                data.addProperty("selectedSlot", player.getInventory().selected);
                return ToolResult.okJson(data);
            }
        };
    }

    private static Tool getBlock() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("x", numberProp("Block X"));
                props.add("y", numberProp("Block Y"));
                props.add("z", numberProp("Block Z"));
                return new ToolSchema("get_block", "Read a block at coordinates", objectSchema(props, "x", "y", "z"));
            }

            @Override
            public String capability() {
                return "read";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                BlockPos pos = pos(context);
                if (!withinRadius(context.player(), pos)) {
                    return ToolResult.error("Target outside allowed action radius");
                }
                BlockState state = context.player().serverLevel().getBlockState(pos);
                JsonObject data = new JsonObject();
                data.addProperty("x", pos.getX());
                data.addProperty("y", pos.getY());
                data.addProperty("z", pos.getZ());
                data.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                data.addProperty("state", state.toString());
                return ToolResult.okJson(data);
            }
        };
    }

    private static Tool listEntities() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("radius", numberProp("Search radius"));
                props.add("limit", numberProp("Max entities"));
                return new ToolSchema("list_entities", "List nearby entities", objectSchema(props));
            }

            @Override
            public String capability() {
                return "read";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                double radius = Math.min(context.argDouble("radius", 16), TheseusServerConfig.MAX_ACTION_RADIUS.getAsInt());
                int limit = Math.min(context.argInt("limit", 32), 100);
                ServerPlayer player = context.player();
                AABB box = player.getBoundingBox().inflate(radius);
                List<Entity> entities = player.serverLevel().getEntities(player, box, e -> true);
                JsonArray arr = new JsonArray();
                int count = 0;
                for (Entity entity : entities) {
                    if (count++ >= limit) {
                        break;
                    }
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", entity.getId());
                    obj.addProperty("uuid", entity.getUUID().toString());
                    obj.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                    obj.addProperty("name", entity.getName().getString());
                    obj.addProperty("x", entity.getX());
                    obj.addProperty("y", entity.getY());
                    obj.addProperty("z", entity.getZ());
                    arr.add(obj);
                }
                JsonObject data = new JsonObject();
                data.add("entities", arr);
                data.addProperty("count", arr.size());
                return ToolResult.okJson(data);
            }
        };
    }

    private static Tool executeCommand() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("command", stringProp("Command without leading slash"));
                return new ToolSchema("execute_command", "Run a whitelisted Minecraft command as the player", objectSchema(props, "command"));
            }

            @Override
            public String capability() {
                return "command";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                String command = context.argString("command", "").trim();
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                if (command.isBlank()) {
                    return ToolResult.error("command required");
                }
                String root = command.split("\\s+")[0].toLowerCase(Locale.ROOT);
                List<? extends String> whitelist = TheseusServerConfig.COMMAND_WHITELIST.get();
                if (!whitelist.isEmpty() && whitelist.stream().noneMatch(root::equalsIgnoreCase)) {
                    return ToolResult.error("Command not whitelisted: " + root);
                }
                if (!TheseusPermissions.canRunCommands(context.player())) {
                    return ToolResult.error("Insufficient permission");
                }
                CommandSourceStack source = context.player().createCommandSourceStack()
                        .withPermission(TheseusServerConfig.COMMAND_WHITELIST_LEVEL.getAsInt());
                context.server().getCommands().performPrefixedCommand(source, command);
                return ToolResult.ok("Command executed: /" + command);
            }
        };
    }

    private static Tool playerMove() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("x", numberProp("Target X"));
                props.add("y", numberProp("Target Y"));
                props.add("z", numberProp("Target Z"));
                props.add("sprint", boolProp("Sprint"));
                props.add("target_player", stringProp("Optional player name/UUID to control (default: self; OP can control others)"));
                return new ToolSchema("player_move", "Move / path the player toward a position", objectSchema(props, "x", "y", "z"));
            }

            @Override
            public String capability() {
                return "control";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                ServerPlayer target = resolveControlTarget(context);
                if (target == null) {
                    return ToolResult.error("Cannot control target player");
                }
                return PlayerController.get().moveTo(target,
                        context.argDouble("x", target.getX()),
                        context.argDouble("y", target.getY()),
                        context.argDouble("z", target.getZ()),
                        context.argBool("sprint", false));
            }
        };
    }

    private static Tool playerGoto() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("x", numberProp("Target X"));
                props.add("y", numberProp("Target Y"));
                props.add("z", numberProp("Target Z"));
                props.add("sprint", boolProp("Sprint"));
                props.add("target_player", stringProp("Optional player name/UUID to control (default: self; OP can control others)"));
                return new ToolSchema("player_goto", "Navigate player toward a position with simple step-up/jump", objectSchema(props, "x", "y", "z"));
            }

            @Override
            public String capability() {
                return "control";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                ServerPlayer target = resolveControlTarget(context);
                if (target == null) {
                    return ToolResult.error("Cannot control target player");
                }
                return PlayerController.get().gotoPos(target,
                        context.argDouble("x", target.getX()),
                        context.argDouble("y", target.getY()),
                        context.argDouble("z", target.getZ()),
                        context.argBool("sprint", false));
            }
        };
    }

    private static Tool playerLook() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("yaw", numberProp("Yaw"));
                props.add("pitch", numberProp("Pitch"));
                props.add("target_player", stringProp("Optional player name/UUID to control"));
                return new ToolSchema("player_look", "Set player look direction", objectSchema(props, "yaw", "pitch"));
            }

            @Override
            public String capability() {
                return "control";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                ServerPlayer target = resolveControlTarget(context);
                if (target == null) {
                    return ToolResult.error("Cannot control target player");
                }
                return PlayerController.get().look(target, (float) context.argDouble("yaw", target.getYRot()),
                        (float) context.argDouble("pitch", target.getXRot()));
            }
        };
    }

    private static Tool playerBreakBlock() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("x", numberProp("X"));
                props.add("y", numberProp("Y"));
                props.add("z", numberProp("Z"));
                props.add("target_player", stringProp("Optional player name/UUID to control"));
                return new ToolSchema("player_break_block", "Simulate player mining a block", objectSchema(props, "x", "y", "z"));
            }

            @Override
            public String capability() {
                return "control";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                ServerPlayer target = resolveControlTarget(context);
                if (target == null) {
                    return ToolResult.error("Cannot control target player");
                }
                BlockPos pos = pos(context);
                if (!withinRadius(target, pos)) {
                    return ToolResult.error("Outside radius");
                }
                return PlayerController.get().breakBlock(target, pos);
            }
        };
    }

    private static Tool playerPlaceBlock() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("x", numberProp("X"));
                props.add("y", numberProp("Y"));
                props.add("z", numberProp("Z"));
                props.add("target_player", stringProp("Optional player name/UUID to control"));
                return new ToolSchema("player_place_block", "Simulate player placing the held block", objectSchema(props, "x", "y", "z"));
            }

            @Override
            public String capability() {
                return "control";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                ServerPlayer target = resolveControlTarget(context);
                if (target == null) {
                    return ToolResult.error("Cannot control target player");
                }
                BlockPos pos = pos(context);
                if (!withinRadius(target, pos)) {
                    return ToolResult.error("Outside radius");
                }
                return PlayerController.get().placeBlock(target, pos);
            }
        };
    }

    private static Tool playerUseItem() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("target_player", stringProp("Optional player name/UUID to control"));
                return new ToolSchema("player_use_item", "Use the currently held item", objectSchema(props));
            }

            @Override
            public String capability() {
                return "control";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                ServerPlayer target = resolveControlTarget(context);
                if (target == null) {
                    return ToolResult.error("Cannot control target player");
                }
                return PlayerController.get().useItem(target);
            }
        };
    }

    private static Tool worldSetBlock() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("x", numberProp("X"));
                props.add("y", numberProp("Y"));
                props.add("z", numberProp("Z"));
                props.add("block", stringProp("Block id, e.g. minecraft:stone"));
                return new ToolSchema("world_set_block", "Directly set a block in the world", objectSchema(props, "x", "y", "z", "block"));
            }

            @Override
            public String capability() {
                return "world_edit";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                return WorldEditService.setBlock(context.player(), pos(context), context.argString("block", "minecraft:stone"));
            }
        };
    }

    private static Tool worldFill() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("x1", numberProp("X1"));
                props.add("y1", numberProp("Y1"));
                props.add("z1", numberProp("Z1"));
                props.add("x2", numberProp("X2"));
                props.add("y2", numberProp("Y2"));
                props.add("z2", numberProp("Z2"));
                props.add("block", stringProp("Block id"));
                return new ToolSchema("world_fill", "Fill a region by directly writing block data", objectSchema(props, "x1", "y1", "z1", "x2", "y2", "z2", "block"));
            }

            @Override
            public String capability() {
                return "world_edit";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                BlockPos a = new BlockPos(context.argInt("x1", 0), context.argInt("y1", 0), context.argInt("z1", 0));
                BlockPos b = new BlockPos(context.argInt("x2", 0), context.argInt("y2", 0), context.argInt("z2", 0));
                return WorldEditService.fill(context.player(), a, b, context.argString("block", "minecraft:stone"));
            }
        };
    }

    private static Tool possessEntity() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("entity_uuid", stringProp("Target entity UUID"));
                return new ToolSchema("possess_entity", "Possess a living entity for AI control", objectSchema(props, "entity_uuid"));
            }

            @Override
            public String capability() {
                return "possess";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                return EntityPossessionManager.get().possess(context.player(), context.sessionId(), context.argString("entity_uuid", ""));
            }
        };
    }

    private static Tool unpossess() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("entity_uuid", stringProp("Entity UUID (optional = all for session)"));
                return new ToolSchema("unpossess", "Release possessed entities", objectSchema(props));
            }

            @Override
            public String capability() {
                return "possess";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                return EntityPossessionManager.get().unpossess(context.sessionId(), context.argString("entity_uuid", null));
            }
        };
    }

    private static Tool possessedAction() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                JsonObject props = objectProps();
                props.add("entity_uuid", stringProp("Possessed entity UUID"));
                props.add("action", stringProp("move|look|attack|follow_player"));
                props.add("x", numberProp("X"));
                props.add("y", numberProp("Y"));
                props.add("z", numberProp("Z"));
                return new ToolSchema("possessed_action", "Command a possessed entity", objectSchema(props, "entity_uuid", "action"));
            }

            @Override
            public String capability() {
                return "possess";
            }

            @Override
            public ToolResult execute(ToolContext context) {
                return EntityPossessionManager.get().action(
                        context.sessionId(),
                        context.argString("entity_uuid", ""),
                        context.argString("action", "move"),
                        context.argDouble("x", 0),
                        context.argDouble("y", 0),
                        context.argDouble("z", 0),
                        context.player()
                );
            }
        };
    }

    private static Tool takeScreenshot() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                return new ToolSchema("take_screenshot", "Capture the player's client view for vision models", objectSchema(new JsonObject()));
            }

            @Override
            public String capability() {
                return "vision";
            }

            @Override
            public boolean requiresMainThread() {
                return false;
            }

            @Override
            public ToolResult execute(ToolContext context) {
                return ScreenshotService.request(context.player());
            }
        };
    }

    private static ServerPlayer resolveControlTarget(ToolContext context) {
        ServerPlayer actor = context.player();
        String ref = context.argString("target_player", null);
        ServerPlayer target = actor;
        if (ref != null && !ref.isBlank() && actor.getServer() != null) {
            target = null;
            for (ServerPlayer online : actor.getServer().getPlayerList().getPlayers()) {
                if (online.getGameProfile().getName().equalsIgnoreCase(ref)
                        || online.getUUID().toString().equalsIgnoreCase(ref)) {
                    target = online;
                    break;
                }
            }
            if (target == null) {
                return null;
            }
        }
        if (!TheseusPermissions.canControlPlayers(actor, target)) {
            return null;
        }
        return target;
    }

    private static BlockPos pos(ToolContext context) {
        return new BlockPos(context.argInt("x", 0), context.argInt("y", 0), context.argInt("z", 0));
    }

    private static boolean withinRadius(ServerPlayer player, BlockPos pos) {
        double max = TheseusServerConfig.MAX_ACTION_RADIUS.getAsInt();
        return player.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= max * max;
    }

    private static JsonObject objectProps() {
        return new JsonObject();
    }

    private static JsonObject objectSchema(JsonObject properties, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (required.length > 0) {
            JsonArray req = new JsonArray();
            for (String r : required) {
                req.add(r);
            }
            schema.add("required", req);
        }
        return schema;
    }

    private static JsonObject stringProp(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject numberProp(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "number");
        o.addProperty("description", description);
        return o;
    }

    private static JsonObject boolProp(String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "boolean");
        o.addProperty("description", description);
        return o;
    }
}
