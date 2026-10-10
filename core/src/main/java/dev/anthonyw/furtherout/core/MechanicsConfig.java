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
        Keeper keeper,
        Noise noise,
        DarkSounds darkSounds,
        Mimic mimic,
        Pack pack
) {
    public record Elites(double eliteHealthBonus, double championHealthBonus,
                         int championLootRolls, int xpBonus, double warpedPearlChance, double pearlSwapRadius) {
    }

    /**
     * A warper recharges for {@code cooldownTicks} (and shimmers once it's
     * ready); its next charged hit warps you with {@code procChance}.
     */
    public record Warper(double procChance, int cooldownTicks, int tossWeight, int swapWeight,
                         int scatterWeight, double swapRange, double netherRiftChance,
                         List<String> netherRiftRings, int riftTicks, int tossHeight) {
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

    /** A thief takes one hotbar stack; by default that includes what's in your hand, tools and all. */
    public record Thief(double procChance, int fleeTicks, boolean takeHeldItem, boolean takeTools) {
    }

    public record Magnetic(int intervalTicks, int windupTicks, double radius, double strength, double lift,
                           boolean needsLineOfSight) {
    }

    /** {@code maxDamage} caps what the blast can do to any one target (before armor). */
    public record Volatile(int fuseTicks, double power, double maxDamage) {
    }

    /**
     * Warding only works while another player is within {@code groupRange} (0 = always).
     * Its target's hits are cut to {@code targetDamageMultiplier}, and {@code reflectFraction}
     * of what was cut stings them back.
     */
    public record Warded(double targetDamageMultiplier, double groupRange, double reflectFraction) {
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

    /** The zombie that keeps your things where you died. */
    public record Keeper(boolean enabled, boolean inSafeArea, double leashRadius, double health,
                         double attackDamage) {
    }

    /** Explosions and fights draw idle monsters nearby to come and look. */
    public record Noise(boolean enabled, double explosionRadius, double combatRadius, int combatCooldownTicks,
                        int investigateTicks) {
    }

    /** Rare sounds only a lone player in the dark hears. */
    public record DarkSounds(boolean enabled, int minTicks, int maxTicks, double aloneRange, int maxLight) {
    }

    /** Rare bait items in dark caves that turn into a monster when you reach for them. */
    public record Mimic(boolean enabled, double chance, int minDanger, double playerRange, double triggerRadius,
                       List<String> mobs, List<MimicRules.Bait> baits) {
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
                Json.integer(e, "xpBonus", 20, 0, 10000, "elites", errors),
                Json.num(e, "warpedPearlChance", 0.35, 0, 1, "elites", errors),
                Json.num(e, "pearlSwapRadius", 4, 1, 16, "elites", errors));

        JsonObject w = Json.obj(root, "warper");
        Warper warper = new Warper(
                Json.num(w, "procChance", 1.0, 0, 1, "warper", errors),
                ticks(Json.num(w, "cooldownSeconds", 6, 0, 600, "warper", errors)),
                Json.integer(w, "tossWeight", 30, 0, 1000, "warper", errors),
                Json.integer(w, "swapWeight", 40, 0, 1000, "warper", errors),
                Json.integer(w, "scatterWeight", 30, 0, 1000, "warper", errors),
                Json.num(w, "swapRange", 24, 0, 256, "warper", errors),
                Json.num(w, "netherRiftChance", 0.15, 0, 1, "warper", errors),
                ringIds(w, "netherRiftRings", List.of("level3", "level4"), errors),
                ticks(Json.num(w, "riftSeconds", 30, 3, 600, "warper", errors)),
                Json.integer(w, "tossHeight", 14, 6, 64, "warper", errors));

        JsonObject t = Json.obj(root, "thief");
        Thief thief = new Thief(
                Json.num(t, "procChance", 0.5, 0, 1, "thief", errors),
                ticks(Json.num(t, "fleeSeconds", 30, 1, 600, "thief", errors)),
                Json.bool(t, "takeHeldItem", true, "thief", errors),
                Json.bool(t, "takeTools", true, "thief", errors));

        JsonObject m = Json.obj(root, "magnetic");
        Magnetic magnetic = new Magnetic(
                ticks(Json.num(m, "intervalSeconds", 8, 1, 600, "magnetic", errors)),
                Json.integer(m, "windupTicks", 30, 0, 200, "magnetic", errors),
                Json.num(m, "radius", 14, 1, 64, "magnetic", errors),
                Json.num(m, "strength", 1.4, 0, 5, "magnetic", errors),
                Json.num(m, "lift", 0.5, 0, 3, "magnetic", errors),
                Json.bool(m, "needsLineOfSight", true, "magnetic", errors));

        JsonObject v = Json.obj(root, "volatile");
        Volatile vol = new Volatile(
                Json.integer(v, "fuseTicks", 30, 1, 200, "volatile", errors),
                Json.num(v, "power", 3.5, 0.5, 8, "volatile", errors),
                Json.num(v, "maxDamage", 12, 1, 100, "volatile", errors));

        JsonObject wd = Json.obj(root, "warded");
        Warded warded = new Warded(
                Json.num(wd, "targetDamageMultiplier", 0.1, 0, 1, "warded", errors),
                Json.num(wd, "groupRange", 24, 0, 256, "warded", errors),
                Json.num(wd, "reflectFraction", 0.3, 0, 2, "warded", errors));

        JsonObject d = Json.obj(root, "digging");
        Digging digging = new Digging(
                Json.bool(d, "enabled", true, "digging", errors),
                Json.bool(d, "elitesAlwaysDig", true, "digging", errors),
                Json.num(d, "senseRange", 16, 0, 64, "digging", errors),
                Json.num(d, "maxHardness", 3.0, 0, 1000, "digging", errors),
                Json.integer(d, "maxBlocksPerMob", 32, 0, 10000, "digging", errors),
                Json.bool(d, "dropBlocks", true, "digging", errors),
                Json.num(d, "breakSpeed", 1.5, 0.1, 20, "digging", errors),
                Json.bool(d, "protectBlockEntities", true, "digging", errors),
                Json.bool(d, "naturalBlocksOnly", false, "digging", errors),
                Json.integer(d, "baseRadius", 6, 0, 64, "digging", errors),
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
                ticks(Json.num(mk, "durationSeconds", 180, 10, 3600, "marked", errors)),
                Json.num(mk, "eliteChance", 0.3, 0, 1, "marked", errors),
                Json.bool(mk, "championAlways", true, "marked", errors),
                Json.num(mk, "lureRadius", 16, 0, 64, "marked", errors),
                ticks(Json.num(mk, "ambushEverySeconds", 40, 5, 3600, "marked", errors)),
                ticks(Json.num(mk, "firstAmbushAfterSeconds", 20, 0, 3600, "marked", errors)),
                Json.integer(mk, "ambushBaseSize", 3, 0, 20, "marked", errors),
                Json.ids(mk, "ambushMobs", DEFAULT_AMBUSH_MOBS, "marked", errors),
                ticks(Json.num(mk, "passBackImmunitySeconds", 10, 0, 600, "marked", errors)),
                Json.num(mk, "jumpRange", 48, 0, 512, "marked", errors),
                ticks(Json.num(mk, "minJumpSeconds", 60, 0, 3600, "marked", errors)),
                Json.integer(mk, "survivalXp", 100, 0, 100000, "marked", errors),
                Json.bool(mk, "doubleDrops", true, "marked", errors));

        JsonObject kp = Json.obj(root, "keeper");
        Keeper keeper = new Keeper(
                Json.bool(kp, "enabled", true, "keeper", errors),
                Json.bool(kp, "inSafeArea", false, "keeper", errors),
                Json.num(kp, "leashRadius", 8, 2, 64, "keeper", errors),
                Json.num(kp, "health", 10, 1, 200, "keeper", errors),
                Json.num(kp, "attackDamage", 1, 0, 20, "keeper", errors));

        JsonObject nz = Json.obj(root, "noise");
        Noise noise = new Noise(
                Json.bool(nz, "enabled", true, "noise", errors),
                Json.num(nz, "explosionRadius", 48, 0, 128, "noise", errors),
                Json.num(nz, "combatRadius", 16, 0, 64, "noise", errors),
                ticks(Json.num(nz, "combatCooldownSeconds", 5, 0, 600, "noise", errors)),
                ticks(Json.num(nz, "investigateSeconds", 30, 1, 600, "noise", errors)));

        JsonObject ds = Json.obj(root, "darkSounds");
        int dsMin = ticks(Json.num(ds, "minMinutes", 8, 0.05, 600, "darkSounds", errors) * 60);
        int dsMax = ticks(Json.num(ds, "maxMinutes", 20, 0.05, 600, "darkSounds", errors) * 60);
        if (dsMax < dsMin) {
            errors.add("darkSounds.maxMinutes is less than minMinutes (using minMinutes for both)");
            dsMax = dsMin;
        }
        DarkSounds darkSounds = new DarkSounds(
                Json.bool(ds, "enabled", true, "darkSounds", errors),
                dsMin, dsMax,
                Json.num(ds, "aloneRange", 48, 0, 512, "darkSounds", errors),
                Json.integer(ds, "maxLight", 3, 0, 15, "darkSounds", errors));

        JsonObject mm = Json.obj(root, "mimic");
        List<String> baitEntries = new ArrayList<>();
        if (mm.has("baits") && mm.get("baits").isJsonArray()) {
            mm.getAsJsonArray("baits").forEach(entry -> baitEntries.add(entry.getAsString()));
        } else {
            baitEntries.addAll(DEFAULT_BAITS);
        }
        Mimic mimic = new Mimic(
                Json.bool(mm, "enabled", true, "mimic", errors),
                Json.num(mm, "chance", 0.004, 0, 1, "mimic", errors),
                Json.integer(mm, "minDanger", 2, 0, 100, "mimic", errors),
                Json.num(mm, "playerRange", 32, 1, 128, "mimic", errors),
                Json.num(mm, "triggerRadius", 2.5, 0.5, 8, "mimic", errors),
                Json.ids(mm, "mobs", DEFAULT_MIMIC_MOBS, "mimic", errors),
                MimicRules.parseBaits(baitEntries, errors));

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

        return new MechanicsConfig(elites, warper, thief, magnetic, vol, warded, digging, downed, marked, keeper,
                noise, darkSounds, mimic, pack);
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

    public static final List<String> DEFAULT_MIMIC_MOBS = List.of(
            "minecraft:zombie", "minecraft:husk", "minecraft:skeleton", "minecraft:spider");

    public static final List<String> DEFAULT_BAITS = List.of(
            "minecraft:diamond", "minecraft:emerald*2", "minecraft:gold_ingot*3", "minecraft:iron_ingot*5",
            "minecraft:ender_pearl", "minecraft:lapis_lazuli*6");

    public static final String DEFAULT_JSON = """
            {
              "elites": {
                "eliteHealthBonus": 0.3,
                "championHealthBonus": 0.75,
                "championLootRolls": 2,
                "xpBonus": 20,
                "warpedPearlChance": 0.35,
                "pearlSwapRadius": 4
              },
              "warper": {
                "procChance": 1.0,
                "cooldownSeconds": 6,
                "tossWeight": 30,
                "swapWeight": 40,
                "scatterWeight": 30,
                "swapRange": 24,
                "netherRiftChance": 0.15,
                "netherRiftRings": ["level3", "level4"],
                "riftSeconds": 30,
                "tossHeight": 14
              },
              "thief": {
                "procChance": 0.5,
                "fleeSeconds": 30,
                "takeHeldItem": true,
                "takeTools": true
              },
              "magnetic": {
                "intervalSeconds": 8,
                "windupTicks": 30,
                "radius": 14,
                "strength": 1.4,
                "lift": 0.5,
                "needsLineOfSight": true
              },
              "volatile": {
                "fuseTicks": 30,
                "power": 3.5,
                "maxDamage": 12
              },
              "warded": {
                "targetDamageMultiplier": 0.1,
                "groupRange": 24,
                "reflectFraction": 0.3
              },
              "digging": {
                "enabled": true,
                "elitesAlwaysDig": true,
                "senseRange": 16,
                "maxHardness": 3.0,
                "maxBlocksPerMob": 32,
                "dropBlocks": true,
                "breakSpeed": 1.5,
                "protectBlockEntities": true,
                "naturalBlocksOnly": false,
                "baseRadius": 6,
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
                "durationSeconds": 180,
                "eliteChance": 0.3,
                "championAlways": true,
                "lureRadius": 16,
                "firstAmbushAfterSeconds": 20,
                "ambushEverySeconds": 40,
                "ambushBaseSize": 3,
                "ambushMobs": ["minecraft:zombie", "minecraft:husk", "minecraft:skeleton",
                               "minecraft:spider", "minecraft:creeper", "minecraft:vindicator"],
                "passBackImmunitySeconds": 10,
                "jumpRange": 48,
                "minJumpSeconds": 60,
                "survivalXp": 100,
                "doubleDrops": true
              },
              "keeper": {
                "enabled": true,
                "inSafeArea": false,
                "leashRadius": 8,
                "health": 10,
                "attackDamage": 1
              },
              "noise": {
                "enabled": true,
                "explosionRadius": 48,
                "combatRadius": 16,
                "combatCooldownSeconds": 5,
                "investigateSeconds": 30
              },
              "darkSounds": {
                "enabled": true,
                "minMinutes": 8,
                "maxMinutes": 20,
                "aloneRange": 48,
                "maxLight": 3
              },
              "mimic": {
                "enabled": true,
                "chance": 0.004,
                "minDanger": 2,
                "playerRange": 32,
                "triggerRadius": 2.5,
                "mobs": ["minecraft:zombie", "minecraft:husk", "minecraft:skeleton", "minecraft:spider"],
                "baits": ["minecraft:diamond", "minecraft:emerald*2", "minecraft:gold_ingot*3",
                          "minecraft:iron_ingot*5", "minecraft:ender_pearl", "minecraft:lapis_lazuli*6"]
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
