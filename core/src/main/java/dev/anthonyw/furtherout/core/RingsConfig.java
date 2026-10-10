package dev.anthonyw.furtherout.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything in rings.json: where the rings are, how hard each one is, and
 * which dimensions use which ring. Parsed and validated here (unit-tested);
 * the mod layer only resolves names against the running game.
 *
 * @param radialDimensions dimensions where difficulty grows with distance from the origin
 * @param dimensionRings   other dimensions pinned to a fixed ring (e.g. the Nether = level3)
 * @param nightRadiusMultiplier at night, every ring outside the safe zone shrinks to this
 *                         fraction of its radius, so danger reaches closer to spawn
 */
public record RingsConfig(
        boolean useWorldSpawn,
        double originX,
        double originZ,
        List<String> radialDimensions,
        Map<String, String> dimensionRings,
        List<RingDef> rings,
        double maxHealthMult,
        double maxDamageMult,
        Set<String> excludedSpawnTypes,
        List<String> entityBlacklist,
        boolean skipBosses,
        double nightRadiusMultiplier
) {
    /** Minecraft 1.21.1 MobSpawnType names. */
    public static final Set<String> SPAWN_TYPES = Set.of(
            "NATURAL", "CHUNK_GENERATION", "SPAWNER", "STRUCTURE", "BREEDING", "MOB_SUMMONED",
            "JOCKEY", "EVENT", "CONVERSION", "REINFORCEMENT", "TRIGGERED", "BUCKET", "SPAWN_EGG",
            "COMMAND", "DISPENSER", "PATROL", "TRIAL_SPAWNER");

    /**
     * Spawns that never get ring treatment (scaling, elites, digging, Hearth
     * suppression): farms, eggs, commands, conversions, and raids (EVENT) -
     * cancelling raid waves inside the Hearth could leave a raid stuck.
     */
    public static final List<String> DEFAULT_EXCLUDED_SPAWN_TYPES = List.of(
            "SPAWNER", "TRIAL_SPAWNER", "MOB_SUMMONED", "CONVERSION", "BREEDING", "BUCKET",
            "SPAWN_EGG", "COMMAND", "DISPENSER", "EVENT");

    public record Result(RingsConfig config, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty() && config != null;
        }
    }

    public RingDef ringAtDistance(double distance) {
        return RingLookup.at(rings, distance);
    }

    public RingDef byId(String id) {
        return RingLookup.byId(rings, id);
    }

    public static Result parse(String json) {
        List<String> errors = new ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            errors.add("rings.json is not valid JSON: " + e.getMessage());
            return new Result(null, errors);
        }

        JsonObject origin = Json.obj(root, "origin");
        boolean useWorldSpawn = Json.bool(origin, "useWorldSpawn", true, "origin", errors);
        double originX = Json.num(origin, "x", 0, -3.0e7, 3.0e7, "origin", errors);
        double originZ = Json.num(origin, "z", 0, -3.0e7, 3.0e7, "origin", errors);

        List<String> radial = Json.ids(root, "radialDimensions", List.of("minecraft:overworld"), "", errors);

        List<RingDef> rings = new ArrayList<>();
        if (root.has("rings") && root.get("rings").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("rings")) {
                if (e.isJsonObject()) {
                    rings.add(parseRing(e.getAsJsonObject(), errors));
                } else {
                    errors.add("every entry in \"rings\" must be an object");
                }
            }
        }
        if (rings.isEmpty()) {
            errors.add("no rings defined");
        }
        rings.sort(Comparator.comparingDouble(r -> r.unbounded() ? Double.MAX_VALUE : r.outerRadius()));
        validateRings(rings, errors);

        Map<String, String> dimensionRings = new LinkedHashMap<>();
        JsonObject dims = Json.obj(root, "dimensionRings");
        for (String dim : dims.keySet()) {
            String ringId = dims.get(dim).getAsString();
            if (!Json.isId(dim)) {
                errors.add("dimensionRings: \"" + dim + "\" should look like minecraft:the_nether");
            } else if (RingLookup.byId(rings, ringId) == null) {
                errors.add("dimensionRings: " + dim + " points at unknown ring \"" + ringId + "\"");
            } else if (radial.contains(dim)) {
                errors.add("dimensionRings: " + dim + " is already a radial dimension");
            } else {
                dimensionRings.put(dim, ringId);
            }
        }

        JsonObject caps = Json.obj(root, "scalingCaps");
        double maxHealth = Json.num(caps, "maxHealthMult", 2.0, 1.0, 20.0, "scalingCaps", errors);
        double maxDamage = Json.num(caps, "maxDamageMult", 2.0, 1.0, 20.0, "scalingCaps", errors);

        JsonObject ex = Json.obj(root, "exclusions");
        Set<String> excluded = new HashSet<>();
        if (ex.has("spawnTypes") && ex.get("spawnTypes").isJsonArray()) {
            for (JsonElement e : ex.getAsJsonArray("spawnTypes")) {
                String type = e.getAsString().toUpperCase(Locale.ROOT);
                if (SPAWN_TYPES.contains(type)) {
                    excluded.add(type);
                } else {
                    errors.add("exclusions.spawnTypes: unknown spawn type \"" + type + "\"");
                }
            }
        } else {
            excluded.addAll(DEFAULT_EXCLUDED_SPAWN_TYPES);
        }
        List<String> blacklist = Json.ids(ex, "entityBlacklist", List.of(), "exclusions", errors);
        boolean skipBosses = Json.bool(ex, "skipBosses", true, "exclusions", errors);

        JsonObject night = Json.obj(root, "night");
        double nightMult = Json.num(night, "radiusMultiplier", 0.8, 0.5, 1.0, "night", errors);

        RingsConfig config = new RingsConfig(useWorldSpawn, originX, originZ, radial,
                Map.copyOf(dimensionRings), List.copyOf(rings), maxHealth, maxDamage,
                Set.copyOf(excluded), blacklist, skipBosses, nightMult);
        return new Result(config, errors);
    }

    private static RingDef parseRing(JsonObject o, List<String> errors) {
        String id = Json.str(o, "id", "");
        if (id.isBlank()) {
            errors.add("a ring is missing its \"id\"");
            id = "unnamed";
        }
        String path = "ring \"" + id + "\"";
        JsonObject mobs = Json.obj(o, "mobs");
        JsonObject elites = Json.obj(o, "elites");

        List<String> modifiers = new ArrayList<>();
        if (elites.has("modifiers") && elites.get("modifiers").isJsonArray()) {
            for (JsonElement e : elites.getAsJsonArray("modifiers")) {
                String mod = e.getAsString();
                if ("*".equals(mod) || EliteModifier.byId(mod) != null) {
                    modifiers.add(mod.toLowerCase(Locale.ROOT));
                } else {
                    errors.add(path + ": unknown ability \"" + mod + "\"");
                }
            }
        }
        String loot = Json.str(elites, "loot", "");
        if (!loot.isEmpty() && !Json.isId(loot)) {
            errors.add(path + ": elites.loot \"" + loot + "\" should look like minecraft:chests/simple_dungeon");
            loot = "";
        }

        return new RingDef(
                id,
                Json.integer(o, "danger", 0, 0, 100, path, errors),
                Json.num(o, "outerRadius", -1, -1, 3.0e7, path, errors),
                Json.bool(o, "safeZone", false, path, errors),
                Json.num(mobs, "healthMult", 1.0, 0.25, 20, path + ".mobs", errors),
                Json.num(mobs, "damageMult", 1.0, 0.25, 20, path + ".mobs", errors),
                Json.num(mobs, "digChance", 0.0, 0, 1, path + ".mobs", errors),
                Json.num(elites, "eliteChance", 0.0, 0, 1, path + ".elites", errors),
                Json.num(elites, "championChance", 0.0, 0, 1, path + ".elites", errors),
                List.copyOf(modifiers),
                loot);
    }

    private static void validateRings(List<RingDef> rings, List<String> errors) {
        double previous = 0;
        Set<String> seen = new HashSet<>();
        int unbounded = 0;
        for (RingDef r : rings) {
            if (!seen.add(r.id())) {
                errors.add("duplicate ring id \"" + r.id() + "\"");
            }
            if (r.unbounded()) {
                unbounded++;
            } else {
                if (r.outerRadius() <= previous) {
                    errors.add("ring \"" + r.id() + "\": outerRadius " + r.outerRadius()
                            + " must be larger than the ring inside it (" + previous + ")");
                }
                previous = r.outerRadius();
            }
            if (r.safeZone() && r.eliteChance() > 0) {
                errors.add("ring \"" + r.id() + "\" is a safe zone but has eliteChance > 0");
            }
        }
        if (unbounded == 0 && !rings.isEmpty()) {
            errors.add("the outermost ring needs \"outerRadius\": -1 so the world has no edge");
        }
        if (unbounded > 1) {
            errors.add("only one ring may have \"outerRadius\": -1");
        }
    }

    public static final String DEFAULT_JSON = """
            {
              "origin": { "useWorldSpawn": true, "x": 0, "z": 0 },
              "radialDimensions": ["minecraft:overworld"],
              "dimensionRings": {
                "minecraft:the_nether": "level3",
                "minecraft:the_end": "level4"
              },
              "rings": [
                { "id": "safe", "danger": 0, "outerRadius": 400, "safeZone": true },
                {
                  "id": "level1", "danger": 1, "outerRadius": 1200,
                  "mobs":   { "healthMult": 1.1, "damageMult": 1.1, "digChance": 0.2 },
                  "elites": { "eliteChance": 0.04, "championChance": 0.0,
                              "modifiers": ["warper", "thief", "volatile"],
                              "loot": "minecraft:chests/simple_dungeon" }
                },
                {
                  "id": "level2", "danger": 2, "outerRadius": 2800,
                  "mobs":   { "healthMult": 1.25, "damageMult": 1.2, "digChance": 0.35 },
                  "elites": { "eliteChance": 0.06, "championChance": 0.1, "modifiers": ["*"],
                              "loot": "minecraft:chests/abandoned_mineshaft" }
                },
                {
                  "id": "level3", "danger": 3, "outerRadius": 5600,
                  "mobs":   { "healthMult": 1.4, "damageMult": 1.3, "digChance": 0.5 },
                  "elites": { "eliteChance": 0.08, "championChance": 0.2, "modifiers": ["*"],
                              "loot": "minecraft:chests/stronghold_corridor" }
                },
                {
                  "id": "level4", "danger": 4, "outerRadius": -1,
                  "mobs":   { "healthMult": 1.6, "damageMult": 1.45, "digChance": 0.65 },
                  "elites": { "eliteChance": 0.1, "championChance": 0.3, "modifiers": ["*"],
                              "loot": "minecraft:chests/ancient_city" }
                }
              ],
              "night": { "radiusMultiplier": 0.8 },
              "scalingCaps": { "maxHealthMult": 2.0, "maxDamageMult": 2.0 },
              "exclusions": {
                "spawnTypes": ["SPAWNER", "TRIAL_SPAWNER", "MOB_SUMMONED", "CONVERSION", "BREEDING",
                               "BUCKET", "SPAWN_EGG", "COMMAND", "DISPENSER", "EVENT"],
                "entityBlacklist": [],
                "skipBosses": true
              }
            }
            """;
}
