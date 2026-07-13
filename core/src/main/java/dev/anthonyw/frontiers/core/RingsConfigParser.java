package dev.anthonyw.frontiers.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and validates rings.json. Fully engine-free so every validation rule
 * is unit-tested; the mod layer only resolves color names to ChatFormatting
 * and entity ids to registries.
 */
public final class RingsConfigParser {
    /** Vanilla chat color names (the mod maps them to ChatFormatting). */
    public static final Set<String> VALID_COLORS = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white");

    public static final List<String> DEFAULT_EXCLUDED_SPAWN_TYPES = List.of(
            "SPAWNER", "MOB_SUMMONED", "CONVERSION", "BUCKET", "SPAWN_EGG", "COMMAND", "DISPENSER");

    public record Result(RingsConfigData data, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private RingsConfigParser() {
    }

    public static Result parse(String json) {
        List<String> errors = new ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            errors.add("not valid JSON: " + e.getMessage());
            return new Result(null, errors);
        }

        boolean useWorldSpawn = true;
        double originX = 0;
        double originZ = 0;
        if (root.has("origin")) {
            JsonObject origin = root.getAsJsonObject("origin");
            useWorldSpawn = !origin.has("useWorldSpawn") || origin.get("useWorldSpawn").getAsBoolean();
            originX = origin.has("x") ? origin.get("x").getAsDouble() : 0;
            originZ = origin.has("z") ? origin.get("z").getAsDouble() : 0;
        }

        List<String> dimensions = new ArrayList<>();
        if (root.has("dimensions")) {
            for (JsonElement e : root.getAsJsonArray("dimensions")) {
                String id = e.getAsString();
                if (!id.contains(":")) {
                    errors.add("dimension id \"" + id + "\" should look like minecraft:overworld");
                } else {
                    dimensions.add(id);
                }
            }
        }
        if (dimensions.isEmpty()) {
            dimensions.add("minecraft:overworld");
        }

        List<RingDef> rings = new ArrayList<>();
        if (root.has("rings")) {
            for (JsonElement e : root.getAsJsonArray("rings")) {
                rings.add(parseRing(e.getAsJsonObject(), errors));
            }
        }
        if (rings.isEmpty()) {
            errors.add("no rings defined");
        }
        rings.sort(Comparator.comparingDouble(r -> r.unbounded() ? Double.MAX_VALUE : r.outerRadius()));
        validate(rings, errors);

        double maxHealth = 2.0;
        double maxDamage = 2.0;
        double maxSpeed = 1.25;
        if (root.has("scalingCaps")) {
            JsonObject caps = root.getAsJsonObject("scalingCaps");
            maxHealth = caps.has("maxHealthMult") ? caps.get("maxHealthMult").getAsDouble() : maxHealth;
            maxDamage = caps.has("maxDamageMult") ? caps.get("maxDamageMult").getAsDouble() : maxDamage;
            maxSpeed = caps.has("maxSpeedMult") ? caps.get("maxSpeedMult").getAsDouble() : maxSpeed;
        }

        Set<String> excludedSpawnTypes = new HashSet<>();
        List<String> entityBlacklist = new ArrayList<>();
        boolean skipBosses = true;
        if (root.has("exclusions")) {
            JsonObject ex = root.getAsJsonObject("exclusions");
            if (ex.has("spawnTypes")) {
                for (JsonElement e : ex.getAsJsonArray("spawnTypes")) {
                    excludedSpawnTypes.add(e.getAsString().toUpperCase(Locale.ROOT));
                }
            }
            if (ex.has("entityBlacklist")) {
                for (JsonElement e : ex.getAsJsonArray("entityBlacklist")) {
                    String id = e.getAsString();
                    if (!id.contains(":")) {
                        errors.add("entity id \"" + id + "\" in entityBlacklist should look like minecraft:zombie");
                    } else {
                        entityBlacklist.add(id);
                    }
                }
            }
            skipBosses = !ex.has("skipBosses") || ex.get("skipBosses").getAsBoolean();
        } else {
            excludedSpawnTypes.addAll(DEFAULT_EXCLUDED_SPAWN_TYPES);
        }

        RingsConfigData data = new RingsConfigData(useWorldSpawn, originX, originZ,
                List.copyOf(dimensions), List.copyOf(rings), maxHealth, maxDamage, maxSpeed,
                Set.copyOf(excludedSpawnTypes), List.copyOf(entityBlacklist), skipBosses);
        return new Result(data, errors);
    }

    private static RingDef parseRing(JsonObject o, List<String> errors) {
        String id = getString(o, "id", null);
        if (id == null || id.isBlank()) {
            errors.add("ring is missing an \"id\"");
            id = "unnamed";
        }
        String colorName = getString(o, "color", "white").toLowerCase(Locale.ROOT);
        if (!VALID_COLORS.contains(colorName)) {
            errors.add("ring \"" + id + "\": unknown color \"" + colorName + "\"");
            colorName = "white";
        }
        JsonObject mobs = o.has("mobs") ? o.getAsJsonObject("mobs") : new JsonObject();
        JsonObject elites = o.has("elites") ? o.getAsJsonObject("elites") : new JsonObject();
        JsonObject heat = o.has("heat") ? o.getAsJsonObject("heat") : new JsonObject();

        List<String> modifiers = new ArrayList<>();
        if (elites.has("modifiers")) {
            JsonArray arr = elites.getAsJsonArray("modifiers");
            for (JsonElement e : arr) {
                String modifierId = e.getAsString();
                if (!"*".equals(modifierId) && EliteModifier.byId(modifierId) == null) {
                    errors.add("ring \"" + id + "\": unknown modifier \"" + modifierId + "\"");
                } else {
                    modifiers.add(modifierId);
                }
            }
        }

        return new RingDef(
                id,
                getString(o, "name", id),
                colorName,
                (int) getDouble(o, "danger", 0),
                getDouble(o, "outerRadius", -1),
                getString(o, "entryMessage", ""),
                getBool(o, "suppressHostileSpawns", false),
                getDouble(mobs, "healthMult", 1.0),
                getDouble(mobs, "damageMult", 1.0),
                getDouble(mobs, "speedMult", 1.0),
                getDouble(elites, "eliteChance", 0.0),
                getDouble(elites, "championChance", 0.0),
                List.copyOf(modifiers),
                getDouble(heat, "gainPerMinute", 0.0)
        );
    }

    private static void validate(List<RingDef> rings, List<String> errors) {
        double previous = 0;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rings.size(); i++) {
            RingDef r = rings.get(i);
            if (!seen.add(r.id())) {
                errors.add("duplicate ring id \"" + r.id() + "\"");
            }
            if (r.unbounded() && i != rings.size() - 1) {
                errors.add("ring \"" + r.id() + "\" has outerRadius -1 but is not the outermost ring");
            }
            if (!r.unbounded()) {
                if (r.outerRadius() <= previous) {
                    errors.add("ring \"" + r.id() + "\": outerRadius " + r.outerRadius()
                            + " does not increase over the previous ring (" + previous + ")");
                }
                previous = r.outerRadius();
            }
            if (r.eliteChance() < 0 || r.eliteChance() > 1 || r.championChance() < 0 || r.championChance() > 1) {
                errors.add("ring \"" + r.id() + "\": eliteChance/championChance must be between 0 and 1");
            }
            if (r.healthMult() < 0.25 || r.damageMult() < 0.25 || r.speedMult() < 0.25) {
                errors.add("ring \"" + r.id() + "\": mob multipliers below 0.25 are almost certainly a typo");
            }
            if (r.danger() < 0) {
                errors.add("ring \"" + r.id() + "\": danger must be 0 or higher");
            }
        }
        if (!rings.isEmpty() && !rings.get(rings.size() - 1).unbounded()) {
            errors.add("the outermost ring should have outerRadius -1 so the map has no uncovered edge");
        }
    }

    private static String getString(JsonObject o, String key, String def) {
        return o.has(key) ? o.get(key).getAsString() : def;
    }

    private static double getDouble(JsonObject o, String key, double def) {
        return o.has(key) ? o.get(key).getAsDouble() : def;
    }

    private static boolean getBool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    public static final String DEFAULT_JSON = """
            {
              "origin": { "useWorldSpawn": true, "x": 0, "z": 0 },
              "dimensions": ["minecraft:overworld"],
              "rings": [
                {
                  "id": "hearth",
                  "name": "The Hearth",
                  "color": "green",
                  "danger": 0,
                  "outerRadius": 400,
                  "entryMessage": "You feel the safety of home.",
                  "suppressHostileSpawns": true,
                  "mobs": { "healthMult": 1.0, "damageMult": 1.0, "speedMult": 1.0 },
                  "elites": { "eliteChance": 0.0, "championChance": 0.0, "modifiers": [] },
                  "heat": { "gainPerMinute": 0.0 }
                },
                {
                  "id": "verge",
                  "name": "The Verge",
                  "color": "yellow",
                  "danger": 1,
                  "outerRadius": 1200,
                  "entryMessage": "The lights of home fade behind you.",
                  "suppressHostileSpawns": false,
                  "mobs": { "healthMult": 1.15, "damageMult": 1.1, "speedMult": 1.0 },
                  "elites": { "eliteChance": 0.04, "championChance": 0.05,
                              "modifiers": ["swift", "stonehide", "corrosive"] },
                  "heat": { "gainPerMinute": 0.0 }
                },
                {
                  "id": "wildmarch",
                  "name": "The Wildmarch",
                  "color": "gold",
                  "danger": 2,
                  "outerRadius": 2800,
                  "entryMessage": "Something out here watches back.",
                  "suppressHostileSpawns": false,
                  "mobs": { "healthMult": 1.3, "damageMult": 1.25, "speedMult": 1.05 },
                  "elites": { "eliteChance": 0.08, "championChance": 0.15,
                              "modifiers": ["swift", "stonehide", "summoner", "blinkstep",
                                            "corrosive", "vengeful", "sieger"] },
                  "heat": { "gainPerMinute": 1.0 }
                },
                {
                  "id": "duskreach",
                  "name": "The Duskreach",
                  "color": "red",
                  "danger": 3,
                  "outerRadius": 5600,
                  "entryMessage": "The dark here has teeth.",
                  "suppressHostileSpawns": false,
                  "mobs": { "healthMult": 1.5, "damageMult": 1.45, "speedMult": 1.1 },
                  "elites": { "eliteChance": 0.12, "championChance": 0.25, "modifiers": ["*"] },
                  "heat": { "gainPerMinute": 2.0 }
                },
                {
                  "id": "ashenfront",
                  "name": "The Ashenfront",
                  "color": "dark_red",
                  "danger": 4,
                  "outerRadius": -1,
                  "entryMessage": "Turn back - or make history.",
                  "suppressHostileSpawns": false,
                  "mobs": { "healthMult": 1.75, "damageMult": 1.7, "speedMult": 1.15 },
                  "elites": { "eliteChance": 0.16, "championChance": 0.35, "modifiers": ["*"] },
                  "heat": { "gainPerMinute": 3.0 }
                }
              ],
              "scalingCaps": { "maxHealthMult": 2.0, "maxDamageMult": 2.0, "maxSpeedMult": 1.25 },
              "exclusions": {
                "spawnTypes": ["SPAWNER", "MOB_SUMMONED", "CONVERSION", "BUCKET", "SPAWN_EGG", "COMMAND", "DISPENSER"],
                "entityBlacklist": [],
                "skipBosses": true
              }
            }
            """;
}
