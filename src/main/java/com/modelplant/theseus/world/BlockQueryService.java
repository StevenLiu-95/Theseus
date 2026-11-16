package com.modelplant.theseus.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.modelplant.theseus.api.tool.ToolResult;
import com.modelplant.theseus.config.TheseusServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only region block queries for AI tools.
 */
public final class BlockQueryService {
    /** Hard cap on voxels scanned in one call (keeps server tick smooth). */
    public static final int MAX_SCAN_VOLUME = 4096;
    /** Hard cap on returned block entries. */
    public static final int MAX_RESULTS = 512;

    private BlockQueryService() {
    }

    public static ToolResult listBlocks(
            ServerPlayer player,
            BlockPos a,
            BlockPos b,
            List<String> propertyNames,
            Set<String> blockFilter,
            boolean includeAir,
            int limit
    ) {
        BlockPos min = new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ())
        );
        if (!withinRadius(player, min) || !withinRadius(player, max)) {
            return ToolResult.error("Region outside allowed action radius");
        }

        long volume = volume(min, max);
        if (volume > MAX_SCAN_VOLUME) {
            return ToolResult.error("Region volume " + volume + " exceeds max scan " + MAX_SCAN_VOLUME
                    + "; shrink the box");
        }

        int resultLimit = Math.min(Math.max(1, limit), MAX_RESULTS);
        List<String> props = normalizePropertyNames(propertyNames);
        ServerLevel level = player.serverLevel();

        JsonArray blocks = new JsonArray();
        int scanned = 0;
        boolean truncated = false;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        outer:
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    scanned++;
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (!includeAir && state.isAir()) {
                        continue;
                    }
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!blockFilter.isEmpty() && !blockFilter.contains(blockId)
                            && !blockFilter.contains(stripNamespace(blockId))) {
                        continue;
                    }

                    if (blocks.size() >= resultLimit) {
                        truncated = true;
                        break outer;
                    }

                    JsonObject entry = new JsonObject();
                    entry.addProperty("x", x);
                    entry.addProperty("y", y);
                    entry.addProperty("z", z);
                    entry.addProperty("block", blockId);
                    if (!props.isEmpty()) {
                        JsonObject propertyValues = new JsonObject();
                        for (String name : props) {
                            String value = readProperty(state, name);
                            if (value != null) {
                                propertyValues.addProperty(name, value);
                            }
                        }
                        entry.add("properties", propertyValues);
                    }
                    blocks.add(entry);
                }
            }
        }

        JsonObject data = new JsonObject();
        data.add("blocks", blocks);
        data.addProperty("count", blocks.size());
        data.addProperty("scanned", scanned);
        data.addProperty("volume", volume);
        data.addProperty("truncated", truncated);
        JsonObject region = new JsonObject();
        region.addProperty("x1", min.getX());
        region.addProperty("y1", min.getY());
        region.addProperty("z1", min.getZ());
        region.addProperty("x2", max.getX());
        region.addProperty("y2", max.getY());
        region.addProperty("z2", max.getZ());
        data.add("region", region);
        if (!props.isEmpty()) {
            JsonArray requested = new JsonArray();
            props.forEach(requested::add);
            data.add("requested_properties", requested);
        }
        return ToolResult.okJson(data);
    }

    public static List<String> parsePropertyNames(JsonObject arguments) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (arguments == null) {
            return List.of();
        }
        addPropertyTokens(names, arguments.get("property"));
        addPropertyTokens(names, arguments.get("properties"));
        return List.copyOf(names);
    }

    public static Set<String> parseBlockFilter(JsonObject arguments) {
        LinkedHashSet<String> filter = new LinkedHashSet<>();
        if (arguments == null) {
            return Set.of();
        }
        addBlockTokens(filter, arguments.get("block"));
        addBlockTokens(filter, arguments.get("blocks"));
        return filter;
    }

    private static void addPropertyTokens(Set<String> out, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    splitComma(out, item.getAsString());
                }
            }
            return;
        }
        if (element.isJsonPrimitive()) {
            splitComma(out, element.getAsString());
        }
    }

    private static void addBlockTokens(Set<String> out, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    String id = item.getAsString().trim().toLowerCase(Locale.ROOT);
                    if (!id.isEmpty()) {
                        out.add(id);
                    }
                }
            }
            return;
        }
        if (element.isJsonPrimitive()) {
            for (String part : element.getAsString().split(",")) {
                String id = part.trim().toLowerCase(Locale.ROOT);
                if (!id.isEmpty()) {
                    out.add(id);
                }
            }
        }
    }

    private static void splitComma(Set<String> out, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
    }

    private static List<String> normalizePropertyNames(List<String> propertyNames) {
        if (propertyNames == null || propertyNames.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : propertyNames) {
            if (name != null && !name.isBlank()) {
                names.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new ArrayList<>(names);
    }

    private static String readProperty(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return getValueName(state, property);
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> String getValueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static String stripNamespace(String id) {
        int slash = id.indexOf(':');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static long volume(BlockPos min, BlockPos max) {
        return (long) (max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
    }

    private static boolean withinRadius(ServerPlayer player, BlockPos pos) {
        double max = TheseusServerConfig.MAX_ACTION_RADIUS.getAsInt();
        return player.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= max * max;
    }
}
