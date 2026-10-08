package dev.anthonyw.frontiers.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything in mechanics.json: how each ability, digging, Downed and the Hex
 * behave. Every value has a default, so a server owner only writes the keys
 * they want to change. All times are in seconds in the file and exposed in
 * ticks (x20) here.
 */
public record MechanicsConfig(
        Elites elites,
        Warper warper,
        Thief thief,
        Magnetic magnetic,
        Volatile volatileAbility,
        Warded warded,
        Digging digging,
        Downed downed,
        Hex hex
) {
    public record Elites(double eliteHealthBonus, double championHealthBonus,
                         int championLootRolls, int xpBonus) {
    }

    public record Warper(double procChance, int cooldownTicks, int tossWeight, int swapWeight,
                         int scatterWeight, double swapRange, double netherRiftChance,
                         List<String> netherRiftRings) {
        public enum Effect { TOSS, SWAP, SCATTER }

        public Effect roll(Rand random) {
            int total = tossWeight + swapWeight + scatterWeight;
            if (total <= 0) {
                return Effect.SCATTER;
            }
            int r = random.nextInt(total);
            if (r < tossWeight) {
                return Effect.TOSS;
            }
            return r < tossWeight + swapWeight ? Effect.SWAP : Effect.SCATTER;
        }

        public boolean rollRift(Rand random, String ringId) {
            return netherRiftRings.contains(ringId) && random.nextDouble() < netherRiftChance;
        }
    }

    public record Thief(double procChance, int fleeTicks) {
    }

    public record Magnetic(int intervalTicks, int windupTicks, double radius, double strength, double lift) {
    }

    public record Volatile(int fuseTicks, double power) {
    }

    public record Warded(double targetDamageMultiplier) {
    }

    public record Digging(boolean enabled, boolean elitesAlwaysDig, double senseRange,
                          double maxHardness, int maxBlocksPerMob, boolean dropBlocks,
                          double breakSpeed, boolean protectBlockEntities,
                          boolean respectMobGriefingRule, List<String> diggers,
                          List<String> blockBlacklist) {
    }

    public record Downed(boolean enabled, int bleedOutTicks, int reviveTicks, double reviveRadius,
                         double rescueRange, int giveUpTicks, float reviveHealth,
                         double ticksLostPerDamage, int aloneBleedMultiplier) {
    }

    public record Hex(boolean enabled, int durationTicks, double eliteChance, boolean championAlways,
                      double lureRadius, int ambushEveryTicks, int firstAmbushTicks,
                      int ambushBaseSize, List<String> ambushMobs, int passBackImmunityTicks,
                      double jumpRange, int minJumpTicks, int survivalXp, boolean doubleDrops) {
    }

    public static MechanicsConfig defaults() {
        return parse(DEFAULT_JSON, new ArrayList<>());
    }

    /** Never fails: invalid JSON or values fall back to defaults and are reported in {@code errors}. */
    public static MechanicsConfig parse(String json, List<String> errors) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            errors.add("mechanics.json is not valid JSON (using all defaults): " + e.getMessage());
            root = new JsonObject();
        }

        JsonObject e = Json.obj(root, "elites");
        Elites elites = new Elites(
                Json.num(e, "eliteHealthBonus", 0.3, 0, 10, "elites", errors),
                Json.num(e, "championHealthBonus", 0.75, 0, 10, "elites", errors),
                Json.integer(e, "championLootRolls", 2, 0, 10, "elites", errors),
                Json.integer(e, "xpBonus", 20, 0, 10000, "elites", errors));

        JsonObject w = Json.obj(root, "warper");
        Warper warper = new Warper(
                Json.num(w, "procChance", 0.3, 0, 1, "warper", errors),
                ticks(Json.num(w, "cooldownSeconds", 6, 0, 600, "warper", errors)),
                Json.integer(w, "tossWeight", 30, 0, 1000, "warper", errors),
                Json.integer(w, "swapWeight", 40, 0, 1000, "warper", errors),
                Json.integer(w, "scatterWeight", 30, 0, 1000, "warper", errors),
                Json.num(w, "swapRange", 32, 0, 256, "warper", errors),
                Json.num(w, "netherRiftChance", 0.05, 0, 1, "warper", errors),
                ringIds(w, "netherRiftRings", List.of("ashenfront"), errors));

        JsonObject t = Json.obj(root, "thief");
        Thief thief = new Thief(
                Json.num(t, "procChance", 0.35, 0, 1, "thief", errors),
                ticks(Json.num(t, "fleeSeconds", 20, 1, 600, "thief", errors)));

        JsonObject m = Json.obj(root, "magnetic");
        Magnetic magnetic = new Magnetic(
                ticks(Json.num(m, "intervalSeconds", 8, 1, 600, "magnetic", errors)),
                Json.integer(m, "windupTicks", 30, 0, 200, "magnetic", errors),
                Json.num(m, "radius", 12, 1, 64, "magnetic", errors),
                Json.num(m, "strength", 1.1, 0, 5, "magnetic", errors),
                Json.num(m, "lift", 0.35, 0, 3, "magnetic", errors));

        JsonObject v = Json.obj(root, "volatile");
        Volatile vol = new Volatile(
                Json.integer(v, "fuseTicks", 30, 1, 200, "volatile", errors),
                Json.num(v, "power", 3.0, 0.5, 8, "volatile", errors));

        JsonObject wd = Json.obj(root, "warded");
        Warded warded = new Warded(
                Json.num(wd, "targetDamageMultiplier", 0.2, 0, 1, "warded", errors));

        JsonObject d = Json.obj(root, "digging");
        Digging digging = new Digging(
                Json.bool(d, "enabled", true, "digging", errors),
                Json.bool(d, "elitesAlwaysDig", true, "digging", errors),
                Json.num(d, "senseRange", 12, 0, 64, "digging", errors),
                Json.num(d, "maxHardness", 5.0, 0, 1000, "digging", errors),
                Json.integer(d, "maxBlocksPerMob", 32, 0, 10000, "digging", errors),
                Json.bool(d, "dropBlocks", true, "digging", errors),
                Json.num(d, "breakSpeed", 1.0, 0.1, 20, "digging", errors),
                Json.bool(d, "protectBlockEntities", true, "digging", errors),
                Json.bool(d, "respectMobGriefingRule", false, "digging", errors),
                Json.ids(d, "diggers", DEFAULT_DIGGERS, "digging", errors),
                Json.ids(d, "blockBlacklist", List.of("minecraft:obsidian", "minecraft:crying_obsidian",
                        "minecraft:reinforced_deepslate", "minecraft:respawn_anchor"), "digging", errors));

        JsonObject dn = Json.obj(root, "downed");
        Downed downed = new Downed(
                Json.bool(dn, "enabled", true, "downed", errors),
                ticks(Json.num(dn, "bleedOutSeconds", 45, 5, 600, "downed", errors)),
                ticks(Json.num(dn, "reviveSeconds", 4, 0.5, 60, "downed", errors)),
                Json.num(dn, "reviveRadius", 2.5, 1, 8, "downed", errors),
                Json.num(dn, "rescueRange", 64, 4, 512, "downed", errors),
                ticks(Json.num(dn, "giveUpSeconds", 3, 0.5, 30, "downed", errors)),
                (float) Json.num(dn, "reviveHealth", 8, 1, 40, "downed", errors),
                Json.num(dn, "secondsLostPerDamage", 0.5, 0, 10, "downed", errors) * 20.0,
                Json.integer(dn, "aloneBleedMultiplier", 4, 1, 20, "downed", errors));

        JsonObject h = Json.obj(root, "hex");
        Hex hex = new Hex(
                Json.bool(h, "enabled", true, "hex", errors),
                ticks(Json.num(h, "durationSeconds", 240, 10, 3600, "hex", errors)),
                Json.num(h, "eliteChance", 0.25, 0, 1, "hex", errors),
                Json.bool(h, "championAlways", true, "hex", errors),
                Json.num(h, "lureRadius", 24, 0, 64, "hex", errors),
                ticks(Json.num(h, "ambushEverySeconds", 45, 5, 3600, "hex", errors)),
                ticks(Json.num(h, "firstAmbushAfterSeconds", 20, 0, 3600, "hex", errors)),
                Json.integer(h, "ambushBaseSize", 2, 0, 20, "hex", errors),
                Json.ids(h, "ambushMobs", DEFAULT_AMBUSH_MOBS, "hex", errors),
                ticks(Json.num(h, "passBackImmunitySeconds", 10, 0, 600, "hex", errors)),
                Json.num(h, "jumpRange", 48, 0, 512, "hex", errors),
                ticks(Json.num(h, "minJumpSeconds", 60, 0, 3600, "hex", errors)),
                Json.integer(h, "survivalXp", 150, 0, 100000, "hex", errors),
                Json.bool(h, "doubleDrops", true, "hex", errors));

        return new MechanicsConfig(elites, warper, thief, magnetic, vol, warded, digging, downed, hex);
    }

    private static int ticks(double seconds) {
        return (int) Math.round(seconds * 20);
    }

    private static List<String> ringIds(JsonObject o, String key, List<String> def, List<String> errors) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return def;
        }
        List<String> out = new ArrayList<>();
        o.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        return List.copyOf(out);
    }

    public static final List<String> DEFAULT_DIGGERS = List.of(
            "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager",
            "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:wither_skeleton",
            "minecraft:creeper", "minecraft:vindicator", "minecraft:pillager", "minecraft:piglin_brute");

    public static final List<String> DEFAULT_AMBUSH_MOBS = List.of(
            "minecraft:zombie", "minecraft:husk", "minecraft:skeleton", "minecraft:spider",
            "minecraft:creeper", "minecraft:vindicator");

    public static final String DEFAULT_JSON = """
            {
              "elites": {
                "eliteHealthBonus": 0.3,
                "championHealthBonus": 0.75,
                "championLootRolls": 2,
                "xpBonus": 20
              },
              "warper": {
                "procChance": 0.3,
                "cooldownSeconds": 6,
                "tossWeight": 30,
                "swapWeight": 40,
                "scatterWeight": 30,
                "swapRange": 32,
                "netherRiftChance": 0.05,
                "netherRiftRings": ["ashenfront"]
              },
              "thief": {
                "procChance": 0.35,
                "fleeSeconds": 20
              },
              "magnetic": {
                "intervalSeconds": 8,
                "windupTicks": 30,
                "radius": 12,
                "strength": 1.1,
                "lift": 0.35
              },
              "volatile": {
                "fuseTicks": 30,
                "power": 3.0
              },
              "warded": {
                "targetDamageMultiplier": 0.2
              },
              "digging": {
                "enabled": true,
                "elitesAlwaysDig": true,
                "senseRange": 12,
                "maxHardness": 5.0,
                "maxBlocksPerMob": 32,
                "dropBlocks": true,
                "breakSpeed": 1.0,
                "protectBlockEntities": true,
                "respectMobGriefingRule": false,
                "diggers": [
                  "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager",
                  "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:wither_skeleton",
                  "minecraft:creeper", "minecraft:vindicator", "minecraft:pillager", "minecraft:piglin_brute"
                ],
                "blockBlacklist": ["minecraft:obsidian", "minecraft:crying_obsidian",
                                   "minecraft:reinforced_deepslate", "minecraft:respawn_anchor"]
              },
              "downed": {
                "enabled": true,
                "bleedOutSeconds": 45,
                "reviveSeconds": 4,
                "reviveRadius": 2.5,
                "rescueRange": 64,
                "giveUpSeconds": 3,
                "reviveHealth": 8,
                "secondsLostPerDamage": 0.5,
                "aloneBleedMultiplier": 4
              },
              "hex": {
                "enabled": true,
                "durationSeconds": 240,
                "eliteChance": 0.25,
                "championAlways": true,
                "lureRadius": 24,
                "ambushEverySeconds": 45,
                "firstAmbushAfterSeconds": 20,
                "ambushBaseSize": 2,
                "ambushMobs": ["minecraft:zombie", "minecraft:husk", "minecraft:skeleton",
                               "minecraft:spider", "minecraft:creeper", "minecraft:vindicator"],
                "passBackImmunitySeconds": 10,
                "jumpRange": 48,
                "minJumpSeconds": 60,
                "survivalXp": 150,
                "doubleDrops": true
              }
            }
            """;
}
