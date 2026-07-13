package dev.anthonyw.frontiers.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Config for the two "the world fights back" abilities.
 *
 * WARPER: elite hits can teleport the PLAYER - a toss into the air, a
 * position swap, a scatter... or (rarely, deep rings only) a rift straight
 * into the Nether. Always telegraphed, always on cooldown.
 *
 * SIEGER: elites that mine through walls to reach a hiding target. Hard
 * capped: never blocks with block entities (chests/machines are safe), never
 * above the hardness cap, never inside the safe zone, and each sieger has a
 * lifetime block budget - they breach bunkers, they don't delete bases.
 */
public record AbilitiesConfig(
        double warperProcChance,
        int warperCooldownTicks,
        int tossWeight,
        int swapWeight,
        int scatterWeight,
        double netherRiftChance,
        List<String> netherRiftRings,
        boolean siegerEnabled,
        double siegerMaxHardness,
        int siegerMaxBlocksPerMob,
        boolean siegerDropsBlocks,
        double siegerBreakSpeed,
        boolean siegerBreaksBlockEntities,
        List<String> siegerBlockBlacklist,
        boolean hunterAlwaysSieger
) {
    public static AbilitiesConfig defaults() {
        return parse(DEFAULT_JSON, new ArrayList<>());
    }

    public static AbilitiesConfig parse(String json, List<String> errors) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            errors.add("abilities.json is not valid JSON: " + e.getMessage() + " (using defaults)");
            root = JsonParser.parseString(DEFAULT_JSON).getAsJsonObject();
        }
        JsonObject warper = root.has("warper") ? root.getAsJsonObject("warper") : new JsonObject();
        JsonObject sieger = root.has("sieger") ? root.getAsJsonObject("sieger") : new JsonObject();

        List<String> riftRings = new ArrayList<>();
        if (warper.has("netherRiftRings")) {
            for (JsonElement e : warper.getAsJsonArray("netherRiftRings")) {
                riftRings.add(e.getAsString());
            }
        } else {
            riftRings.add("ashenfront");
        }
        List<String> blacklist = new ArrayList<>();
        if (sieger.has("blockBlacklist")) {
            for (JsonElement e : sieger.getAsJsonArray("blockBlacklist")) {
                blacklist.add(e.getAsString());
            }
        }

        double procChance = getDouble(warper, "procChance", 0.25);
        if (procChance < 0 || procChance > 1) {
            errors.add("warper.procChance must be between 0 and 1");
            procChance = 0.25;
        }
        double riftChance = getDouble(warper, "netherRiftChance", 0.05);
        if (riftChance < 0 || riftChance > 1) {
            errors.add("warper.netherRiftChance must be between 0 and 1");
            riftChance = 0.05;
        }

        return new AbilitiesConfig(
                procChance,
                getInt(warper, "cooldownTicks", 160),
                Math.max(0, getInt(warper, "tossWeight", 40)),
                Math.max(0, getInt(warper, "swapWeight", 30)),
                Math.max(0, getInt(warper, "scatterWeight", 30)),
                riftChance,
                List.copyOf(riftRings),
                getBool(sieger, "enabled", true),
                getDouble(sieger, "maxHardness", 5.0),
                getInt(sieger, "maxBlocksPerMob", 32),
                getBool(sieger, "dropBrokenBlocks", true),
                getDouble(sieger, "breakSpeed", 1.0),
                getBool(sieger, "breakBlockEntities", false),
                List.copyOf(blacklist),
                getBool(sieger, "hunterAlwaysSieger", true)
        );
    }

    /** Weighted warp effect roll: 0=toss, 1=swap, 2=scatter. */
    public int rollWarpEffect(Rand random) {
        int total = tossWeight + swapWeight + scatterWeight;
        if (total <= 0) {
            return 0;
        }
        int roll = random.nextInt(total);
        if (roll < tossWeight) {
            return 0;
        }
        return roll < tossWeight + swapWeight ? 1 : 2;
    }

    private static double getDouble(JsonObject o, String key, double def) {
        return o.has(key) ? o.get(key).getAsDouble() : def;
    }

    private static int getInt(JsonObject o, String key, int def) {
        return o.has(key) ? o.get(key).getAsInt() : def;
    }

    private static boolean getBool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    public static final String DEFAULT_JSON = """
            {
              "warper": {
                "procChance": 0.25,
                "cooldownTicks": 160,
                "tossWeight": 40,
                "swapWeight": 30,
                "scatterWeight": 30,
                "netherRiftChance": 0.05,
                "netherRiftRings": ["ashenfront"]
              },
              "sieger": {
                "enabled": true,
                "maxHardness": 5.0,
                "maxBlocksPerMob": 32,
                "dropBrokenBlocks": true,
                "breakSpeed": 1.0,
                "breakBlockEntities": false,
                "blockBlacklist": ["minecraft:obsidian", "minecraft:crying_obsidian",
                                   "minecraft:reinforced_deepslate", "minecraft:bedrock"],
                "hunterAlwaysSieger": true
              }
            }
            """;
}
