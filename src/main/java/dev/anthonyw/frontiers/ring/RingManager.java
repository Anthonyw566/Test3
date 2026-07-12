package dev.anthonyw.frontiers.ring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.anthonyw.frontiers.DistantFrontiers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.Tags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads and owns the ring configuration. Reloadable at runtime via
 * {@code /rings reload}. Lookup is a squared-distance comparison against a
 * sorted radius list — cheap enough to call every second per player and on
 * every hostile spawn.
 */
public final class RingManager {
    private static volatile RingManager instance;

    private final boolean useWorldSpawn;
    private final double originX;
    private final double originZ;
    private final Set<ResourceLocation> dimensions;
    private final List<Ring> rings;

    private final double maxHealthMult;
    private final double maxDamageMult;
    private final double maxSpeedMult;
    private final Set<String> excludedSpawnTypes;
    private final Set<ResourceLocation> entityBlacklist;
    private final boolean skipBosses;

    private RingManager(boolean useWorldSpawn, double originX, double originZ,
                        Set<ResourceLocation> dimensions, List<Ring> rings,
                        double maxHealthMult, double maxDamageMult, double maxSpeedMult,
                        Set<String> excludedSpawnTypes, Set<ResourceLocation> entityBlacklist,
                        boolean skipBosses) {
        this.useWorldSpawn = useWorldSpawn;
        this.originX = originX;
        this.originZ = originZ;
        this.dimensions = dimensions;
        this.rings = rings;
        this.maxHealthMult = maxHealthMult;
        this.maxDamageMult = maxDamageMult;
        this.maxSpeedMult = maxSpeedMult;
        this.excludedSpawnTypes = excludedSpawnTypes;
        this.entityBlacklist = entityBlacklist;
        this.skipBosses = skipBosses;
    }

    /** May be null before the first successful load. */
    public static RingManager get() {
        return instance;
    }

    /**
     * (Re)loads config/distantfrontiers/rings.json, writing the default file
     * first if missing. Returns human-readable validation errors (empty on a
     * clean load). A failed load keeps the previous config active.
     */
    public static synchronized List<String> load() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve(DistantFrontiers.MODID);
        Path file = dir.resolve("rings.json");
        List<String> errors = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) {
                Files.writeString(file, DEFAULT_CONFIG);
                DistantFrontiers.LOGGER.info("Wrote default ring config to {}", file);
            }
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            RingManager loaded = parse(root, errors);
            if (errors.isEmpty()) {
                instance = loaded;
                DistantFrontiers.LOGGER.info("Loaded {} rings across {} dimension(s)",
                        loaded.rings.size(), loaded.dimensions.size());
            } else {
                errors.forEach(e -> DistantFrontiers.LOGGER.warn("rings.json: {}", e));
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            errors.add("could not read rings.json: " + e.getMessage());
            DistantFrontiers.LOGGER.error("Failed to load rings.json", e);
        }
        return errors;
    }

    private static RingManager parse(JsonObject root, List<String> errors) {
        boolean useWorldSpawn = true;
        double ox = 0;
        double oz = 0;
        if (root.has("origin")) {
            JsonObject origin = root.getAsJsonObject("origin");
            useWorldSpawn = !origin.has("useWorldSpawn") || origin.get("useWorldSpawn").getAsBoolean();
            ox = origin.has("x") ? origin.get("x").getAsDouble() : 0;
            oz = origin.has("z") ? origin.get("z").getAsDouble() : 0;
        }

        Set<ResourceLocation> dimensions = new HashSet<>();
        if (root.has("dimensions")) {
            for (JsonElement e : root.getAsJsonArray("dimensions")) {
                ResourceLocation rl = ResourceLocation.tryParse(e.getAsString());
                if (rl == null) {
                    errors.add("invalid dimension id \"" + e.getAsString() + "\"");
                } else {
                    dimensions.add(rl);
                }
            }
        }
        if (dimensions.isEmpty()) {
            dimensions.add(ResourceLocation.withDefaultNamespace("overworld"));
        }

        List<Ring> rings = new ArrayList<>();
        if (root.has("rings")) {
            for (JsonElement e : root.getAsJsonArray("rings")) {
                rings.add(Ring.fromJson(e.getAsJsonObject(), errors));
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
        Set<ResourceLocation> entityBlacklist = new HashSet<>();
        boolean skipBosses = true;
        if (root.has("exclusions")) {
            JsonObject ex = root.getAsJsonObject("exclusions");
            if (ex.has("spawnTypes")) {
                for (JsonElement e : ex.getAsJsonArray("spawnTypes")) {
                    excludedSpawnTypes.add(e.getAsString().toUpperCase(java.util.Locale.ROOT));
                }
            }
            if (ex.has("entityBlacklist")) {
                for (JsonElement e : ex.getAsJsonArray("entityBlacklist")) {
                    ResourceLocation rl = ResourceLocation.tryParse(e.getAsString());
                    if (rl == null) {
                        errors.add("invalid entity id \"" + e.getAsString() + "\" in entityBlacklist");
                    } else {
                        entityBlacklist.add(rl);
                    }
                }
            }
            skipBosses = !ex.has("skipBosses") || ex.get("skipBosses").getAsBoolean();
        } else {
            excludedSpawnTypes.addAll(DEFAULT_EXCLUDED_SPAWN_TYPES);
        }

        return new RingManager(useWorldSpawn, ox, oz, dimensions, List.copyOf(rings),
                maxHealth, maxDamage, maxSpeed, excludedSpawnTypes, entityBlacklist, skipBosses);
    }

    private static void validate(List<Ring> rings, List<String> errors) {
        double previous = 0;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rings.size(); i++) {
            Ring r = rings.get(i);
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
        }
        if (!rings.isEmpty() && !rings.get(rings.size() - 1).unbounded()) {
            errors.add("the outermost ring should have outerRadius -1 so the map has no uncovered edge");
        }
    }

    public boolean appliesTo(ServerLevel level) {
        return dimensions.contains(level.dimension().location());
    }

    /** The ring at the given position, or null if rings are inactive in this dimension. */
    public Ring ringAt(ServerLevel level, double x, double z) {
        if (!appliesTo(level)) {
            return null;
        }
        double d = distanceFromOrigin(level, x, z);
        for (Ring r : rings) {
            if (r.unbounded() || d <= r.outerRadius()) {
                return r;
            }
        }
        return rings.isEmpty() ? null : rings.get(rings.size() - 1);
    }

    public Ring byId(String id) {
        for (Ring r : rings) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** Resolved origin as {x, z}. World spawn is resolved lazily so config can load before worlds do. */
    public double[] origin(ServerLevel level) {
        if (useWorldSpawn) {
            BlockPos spawn = level.getServer().overworld().getSharedSpawnPos();
            return new double[]{spawn.getX(), spawn.getZ()};
        }
        return new double[]{originX, originZ};
    }

    public double distanceFromOrigin(ServerLevel level, double x, double z) {
        double[] o = origin(level);
        double dx = x - o[0];
        double dz = z - o[1];
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Blocks remaining until the current ring's outer boundary, or -1 in the unbounded ring. */
    public double blocksToNextRing(ServerLevel level, double x, double z) {
        Ring ring = ringAt(level, x, z);
        if (ring == null || ring.unbounded()) {
            return -1;
        }
        return ring.outerRadius() - distanceFromOrigin(level, x, z);
    }

    public List<Ring> rings() {
        return rings;
    }

    public double maxHealthMult() {
        return maxHealthMult;
    }

    public double maxDamageMult() {
        return maxDamageMult;
    }

    public double maxSpeedMult() {
        return maxSpeedMult;
    }

    /** Spawn types that count as "natural" for scaling, elites and safe-zone suppression. */
    public boolean isNaturalSpawnType(MobSpawnType type) {
        return !excludedSpawnTypes.contains(type.name());
    }

    /** Entities that must never be scaled or promoted: bosses and blacklisted ids. */
    public boolean isExcluded(Mob mob) {
        if (skipBosses && mob.getType().is(Tags.EntityTypes.BOSSES)) {
            return true;
        }
        return entityBlacklist.contains(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
    }

    private static final List<String> DEFAULT_EXCLUDED_SPAWN_TYPES = List.of(
            "SPAWNER", "MOB_SUMMONED", "CONVERSION", "BUCKET", "SPAWN_EGG", "COMMAND", "DISPENSER");

    private static final String DEFAULT_CONFIG = """
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
                              "modifiers": ["swift", "stonehide", "summoner", "blinkstep", "corrosive", "vengeful"] },
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
