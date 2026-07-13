package dev.anthonyw.frontiers.ring;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.core.RingDef;
import dev.anthonyw.frontiers.core.RingsConfigData;
import dev.anthonyw.frontiers.core.RingsConfigParser;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads and owns the ring configuration. Parsing/validation is delegated to
 * the unit-tested core module; this class resolves colors, dimensions and
 * entity ids against the running game and answers lookups. Reloadable at
 * runtime via {@code /rings reload}; a failed load keeps the previous config.
 */
public final class RingManager {
    private static volatile RingManager instance;

    private final RingsConfigData data;
    private final List<Ring> rings;
    private final Set<ResourceLocation> dimensions;
    private final Set<ResourceLocation> entityBlacklist;

    private RingManager(RingsConfigData data) {
        this.data = data;
        this.rings = data.rings().stream().map(Ring::of).toList();
        this.dimensions = new HashSet<>();
        for (String id : data.dimensions()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                dimensions.add(rl);
            }
        }
        this.entityBlacklist = new HashSet<>();
        for (String id : data.entityBlacklist()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                DistantFrontiers.LOGGER.warn("rings.json: unparseable entity id {}", id);
            } else {
                entityBlacklist.add(rl);
            }
        }
    }

    /** May be null before the first successful load. */
    public static RingManager get() {
        return instance;
    }

    public static synchronized List<String> load() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve(DistantFrontiers.MODID);
        Path file = dir.resolve("rings.json");
        List<String> errors = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            if (!Files.exists(file)) {
                Files.writeString(file, RingsConfigParser.DEFAULT_JSON);
                DistantFrontiers.LOGGER.info("Wrote default ring config to {}", file);
            }
            RingsConfigParser.Result result = RingsConfigParser.parse(Files.readString(file));
            errors.addAll(result.errors());
            if (result.ok()) {
                instance = new RingManager(result.data());
                DistantFrontiers.LOGGER.info("Loaded {} rings across {} dimension(s)",
                        result.data().rings().size(), result.data().dimensions().size());
            } else {
                errors.forEach(e -> DistantFrontiers.LOGGER.warn("rings.json: {}", e));
            }
        } catch (IOException e) {
            errors.add("could not read rings.json: " + e.getMessage());
            DistantFrontiers.LOGGER.error("Failed to load rings.json", e);
        }
        return errors;
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
        if (data.useWorldSpawn()) {
            BlockPos spawn = level.getServer().overworld().getSharedSpawnPos();
            return new double[]{spawn.getX(), spawn.getZ()};
        }
        return new double[]{data.originX(), data.originZ()};
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

    /** Inner radius of a ring = the previous bounded ring's outer radius. */
    public double innerRadius(String ringId) {
        double inner = 0;
        for (Ring ring : rings) {
            if (ring.id().equals(ringId)) {
                return inner;
            }
            if (!ring.unbounded()) {
                inner = ring.outerRadius();
            }
        }
        return inner;
    }

    public List<Ring> rings() {
        return rings;
    }

    public double maxHealthMult() {
        return data.maxHealthMult();
    }

    public double maxDamageMult() {
        return data.maxDamageMult();
    }

    public double maxSpeedMult() {
        return data.maxSpeedMult();
    }

    /** Spawn types that count as "natural" for scaling, elites and safe-zone suppression. */
    public boolean isNaturalSpawnType(MobSpawnType type) {
        return !data.excludedSpawnTypes().contains(type.name());
    }

    /** Entities that must never be scaled or promoted: bosses and blacklisted ids. */
    public boolean isExcluded(Mob mob) {
        if (data.skipBosses() && mob.getType().is(Tags.EntityTypes.BOSSES)) {
            return true;
        }
        return entityBlacklist.contains(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
    }
}
