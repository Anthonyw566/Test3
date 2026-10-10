package dev.anthonyw.furtherout.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything in mechanics.json: how each ability, digging, Downed and the
 * mark behave. Every value has a default, so a server owner only writes the
 * keys they want to change. Times are in seconds in the file and exposed in
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
        Marked marked,
        Pack pack
) {
    public record Elites(double eliteHealthBonus, double championHealthBonus,
                         int championLootRolls, int xpBonus) {
    }

    /**
     * A warper recharges for {@code cooldownTicks} (and shimmers once it's
     * ready); its next charged hit warps you with {@code procChance}.
     */
    public record Warper(double procChance, int cooldownTicks, int tossWeight, int swapWeight,
                         int scatterWeight, double swapRange, double netherRiftChance,
                         List<String> netherRiftRings, int riftTicks) {
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

    public record Magnetic(int intervalTicks, int windupTicks, double radius, double strength, double lift,
                           boolean needsLineOfSight) {
    }

    /** {@code maxDamage} caps what the blast can do to any one target (before armor). */
    public record Volatile(int fuseTicks, double power, double maxDamage) {
    }

    /** Warding only works while another player is within {@code groupRange} (0 = always). */
    public record Warded(double targetDamageMultiplier, double groupRange) {
    }

    public record Digging(boolean enabled, boolean elitesAlwaysDig, double senseRange,
                          double maxHardness, int maxBlocksPerMob, boolean dropBlocks,
                          double breakSpeed, boolean protectBlockEntities, boolean naturalBlocksOnly,
                          int baseRadius, boolean respectMobGriefingRule, List<String> diggers,
                          List<String> blockBlacklist) {
    }

    public record Downed(boolean enabled, int bleedOutTicks, int reviveTicks, double reviveRadius,
                         double rescueRange, int giveUpTicks, float reviveHealth,
                         double ticksLostPerDamage, int aloneBleedMultiplier) {
    }

    public record Marked(boolean enabled, int durationTicks, double eliteChance, boolean championAlways,
                         double lureRadius, int ambushEveryTicks, int firstAmbushTicks,
                         int ambushBaseSize, List<String> ambushMobs, int passBackImmunityTicks,
                         double jumpRange, int minJumpTicks, int survivalXp, boolean doubleDrops) {
    }

    /**
     * The optional sound pack. Offered to players on join; anyone who accepts
     * gets a few extra sounds, anyone who declines keeps vanilla ones.
     * Empty url/sha1 = the build's own pack.
     */
    public record Pack(boolean enabled, boolean required, String url, String sha1) {
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
                Json.num(w, "procChance", 1.0, 0, 1, "warper", errors),
                ticks(Json.num(w, "cooldownSeconds", 12, 0, 600, "warper", errors)),
                Json.integer(w, "tossWeight", 30, 0, 1000, "warper", errors),
                Json.integer(w, "swapWeight", 40, 0, 1000, "warper", errors),
                Json.integer(w, "scatterWeight", 30, 0, 1000, "warper", errors),
                Json.num(w, "swapRange", 24, 0, 256, "warper", errors),
                Json.num(w, "netherRiftChance", 0.1, 0, 1, "warper", errors),
                ringIds(w, "netherRiftRings", List.of("level4"), errors),
                ticks(Json.num(w, "riftSeconds", 20, 3, 600, "warper", errors)));

        JsonObject t = Json.obj(root, "thief");
        Thief thief = new Thief(
                Json.num(t, "procChance", 0.35, 0, 1, "thief", errors),
                ticks(Json.num(t, "fleeSeconds", 20, 1, 600, "thief", errors)));

        JsonObject m = Json.obj(root, "magnetic");
        Magnetic magnetic = new Magnetic(
                ticks(Json.num(m, "intervalSeconds", 12, 1, 600, "magnetic", errors)),
                Json.integer(m, "windupTicks", 30, 0, 200, "magnetic", errors),
                Json.num(m, "radius", 10, 1, 64, "magnetic", errors),
                Json.num(m, "strength", 1.0, 0, 5, "magnetic", errors),
                Json.num(m, "lift", 0.35, 0, 3, "magnetic", errors),
                Json.bool(m, "needsLineOfSight", true, "magnetic", errors));

        JsonObject v = Json.obj(root, "volatile");
        Volatile vol = new Volatile(
                Json.integer(v, "fuseTicks", 40, 1, 200, "volatile", errors),
                Json.num(v, "power", 2.5, 0.5, 8, "volatile", errors),
                Json.num(v, "maxDamage", 8, 1, 100, "volatile", errors));

        JsonObject wd = Json.obj(root, "warded");
        Warded warded = new Warded(
                Json.num(wd, "targetDamageMultiplier", 0.25, 0, 1, "warded", errors),
                Json.num(wd, "groupRange", 24, 0, 256, "warded", errors));

        JsonObject d = Json.obj(root, "digging");
        Digging digging = new Digging(
                Json.bool(d, "enabled", true, "digging", errors),
                Json.bool(d, "elitesAlwaysDig", true, "digging", errors),
                Json.num(d, "senseRange", 10, 0, 64, "digging", errors),
                Json.num(d, "maxHardness", 5.0, 0, 1000, "digging", errors),
                Json.integer(d, "maxBlocksPerMob", 12, 0, 10000, "digging", errors),
                Json.bool(d, "dropBlocks", true, "digging", errors),
                Json.num(d, "breakSpeed", 1.0, 0.1, 20, "digging", errors),
                Json.bool(d, "protectBlockEntities", true, "digging", errors),
                Json.bool(d, "naturalBlocksOnly", true, "digging", errors),
                Json.integer(d, "baseRadius", 8, 0, 64, "digging", errors),
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

        JsonObject mk = Json.obj(root, "marked");
        Marked marked = new Marked(
                Json.bool(mk, "enabled", true, "marked", errors),
                ticks(Json.num(mk, "durationSeconds", 150, 10, 3600, "marked", errors)),
                Json.num(mk, "eliteChance", 0.15, 0, 1, "marked", errors),
                Json.bool(mk, "championAlways", true, "marked", errors),
                Json.num(mk, "lureRadius", 16, 0, 64, "marked", errors),
                ticks(Json.num(mk, "ambushEverySeconds", 60, 5, 3600, "marked", errors)),
                ticks(Json.num(mk, "firstAmbushAfterSeconds", 30, 0, 3600, "marked", errors)),
                Json.integer(mk, "ambushBaseSize", 2, 0, 20, "marked", errors),
                Json.ids(mk, "ambushMobs", DEFAULT_AMBUSH_MOBS, "marked", errors),
                ticks(Json.num(mk, "passBackImmunitySeconds", 10, 0, 600, "marked", errors)),
                Json.num(mk, "jumpRange", 48, 0, 512, "marked", errors),
                ticks(Json.num(mk, "minJumpSeconds", 45, 0, 3600, "marked", errors)),
                Json.integer(mk, "survivalXp", 100, 0, 100000, "marked", errors),
                Json.bool(mk, "doubleDrops", true, "marked", errors));

        JsonObject pk = Json.obj(root, "resourcePack");
        String sha1 = Json.str(pk, "sha1", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!sha1.isEmpty() && !sha1.matches("[0-9a-f]{40}")) {
            errors.add("resourcePack.sha1 should be 40 hex characters (leave it empty to use the built-in pack)");
            sha1 = "";
        }
        String url = Json.str(pk, "url", "").trim();
        if (!url.isEmpty() && !url.startsWith("https://") && !url.startsWith("http://")) {
            errors.add("resourcePack.url should start with https:// (leave it empty to use the built-in pack)");
            url = "";
        }
        if (url.isEmpty() != sha1.isEmpty()) {
            errors.add("resourcePack.url and resourcePack.sha1 must be set together (using the built-in pack)");
            url = "";
            sha1 = "";
        }
        Pack pack = new Pack(
                Json.bool(pk, "enabled", true, "resourcePack", errors),
                Json.bool(pk, "required", false, "resourcePack", errors),
                url, sha1);

        return new MechanicsConfig(elites, warper, thief, magnetic, vol, warded, digging, downed, marked, pack);
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
                "procChance": 1.0,
                "cooldownSeconds": 12,
                "tossWeight": 30,
                "swapWeight": 40,
                "scatterWeight": 30,
                "swapRange": 24,
                "netherRiftChance": 0.1,
                "netherRiftRings": ["level4"],
                "riftSeconds": 20
              },
              "thief": {
                "procChance": 0.35,
                "fleeSeconds": 20
              },
              "magnetic": {
                "intervalSeconds": 12,
                "windupTicks": 30,
                "radius": 10,
                "strength": 1.0,
                "lift": 0.35,
                "needsLineOfSight": true
              },
              "volatile": {
                "fuseTicks": 40,
                "power": 2.5,
                "maxDamage": 8
              },
              "warded": {
                "targetDamageMultiplier": 0.25,
                "groupRange": 24
              },
              "digging": {
                "enabled": true,
                "elitesAlwaysDig": true,
                "senseRange": 10,
                "maxHardness": 5.0,
                "maxBlocksPerMob": 12,
                "dropBlocks": true,
                "breakSpeed": 1.0,
                "protectBlockEntities": true,
                "naturalBlocksOnly": true,
                "baseRadius": 8,
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
              "marked": {
                "enabled": true,
                "durationSeconds": 150,
                "eliteChance": 0.15,
                "championAlways": true,
                "lureRadius": 16,
                "firstAmbushAfterSeconds": 30,
                "ambushEverySeconds": 60,
                "ambushBaseSize": 2,
                "ambushMobs": ["minecraft:zombie", "minecraft:husk", "minecraft:skeleton",
                               "minecraft:spider", "minecraft:creeper", "minecraft:vindicator"],
                "passBackImmunitySeconds": 10,
                "jumpRange": 48,
                "minJumpSeconds": 45,
                "survivalXp": 100,
                "doubleDrops": true
              },
              "resourcePack": {
                "enabled": true,
                "required": false,
                "url": "",
                "sha1": ""
              }
            }
            """;
}
