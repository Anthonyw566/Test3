package dev.anthonyw.furtherout.ring;

import dev.anthonyw.furtherout.core.RingLookup;
import dev.anthonyw.furtherout.core.RingsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.common.Tags;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers "which ring is this position in?". Radial dimensions (the overworld)
 * get harder with distance from the origin; pinned dimensions (Nether, End)
 * use one fixed ring everywhere; any other dimension has no rings.
 */
public final class RingManager {
    private static volatile RingManager instance;

    private final RingsConfig config;
    private final List<Ring> rings;
    private final Set<ResourceLocation> radial = new HashSet<>();
    private final Map<ResourceLocation, Ring> pinned = new HashMap<>();
    private final Set<ResourceLocation> entityBlacklist = new HashSet<>();

    private RingManager(RingsConfig config) {
        this.config = config;
        this.rings = config.rings().stream().map(Ring::of).toList();
        config.radialDimensions().forEach(id -> radial.add(ResourceLocation.parse(id)));
        config.dimensionRings().forEach((dim, ringId) -> pinned.put(ResourceLocation.parse(dim), byId(ringId)));
        config.entityBlacklist().forEach(id -> entityBlacklist.add(ResourceLocation.parse(id)));
    }

    @Nullable
    public static RingManager get() {
        return instance;
    }

    /** Parses and installs a rings.json; on errors the previous config stays active. */
    public static List<String> load(String json) {
        RingsConfig.Result result = RingsConfig.parse(json);
        List<String> problems = new ArrayList<>();
        result.errors().forEach(e -> problems.add("rings.json: " + e));
        if (result.ok()) {
            instance = new RingManager(result.config());
        } else if (instance != null) {
            problems.add("rings.json has errors - keeping the previous ring setup");
        }
        return problems;
    }

    public static void setForTesting(RingsConfig config) {
        instance = new RingManager(config);
    }

    /** Null-safe shortcut: the ring an entity is standing in. */
    @Nullable
    public static Ring ringOf(Entity entity) {
        RingManager mgr = instance;
        if (mgr == null || !(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        return mgr.ringAt(level, entity.getX(), entity.getZ());
    }

    /** True when the position is in a safe-zone ring. */
    public static boolean isSafe(ServerLevel level, BlockPos pos) {
        RingManager mgr = instance;
        if (mgr == null) {
            return false;
        }
        Ring ring = mgr.ringAt(level, pos.getX() + 0.5, pos.getZ() + 0.5);
        return ring != null && ring.safeZone();
    }

    @Nullable
    public Ring ringAt(ServerLevel level, double x, double z) {
        return ringAt(level, x, z, scale(level));
    }

    @Nullable
    private Ring ringAt(ServerLevel level, double x, double z, double scale) {
        ResourceLocation dim = level.dimension().location();
        if (radial.contains(dim)) {
            double d = distanceFromOrigin(level, x, z);
            for (Ring ring : rings) {
                if (ring.unbounded() || d <= RingLookup.outer(ring.def(), scale)) {
                    return ring;
                }
            }
            return rings.isEmpty() ? null : rings.get(rings.size() - 1);
        }
        return pinned.get(dim);
    }

    /** At night, danger reaches closer to spawn: every ring but the safe zone pulls in. */
    public double scale(ServerLevel level) {
        return level.isNight() ? config.nightRadiusMultiplier() : 1.0;
    }

    /** True when it's night and that is the only reason this spot is at a higher level. */
    public boolean raisedByNight(ServerLevel level, double x, double z) {
        double scale = scale(level);
        return scale < 1.0 && radial.contains(level.dimension().location())
                && ringAt(level, x, z, scale) != ringAt(level, x, z, 1.0);
    }

    public boolean isRadial(ServerLevel level) {
        return radial.contains(level.dimension().location());
    }

    @Nullable
    public Ring byId(String id) {
        for (Ring ring : rings) {
            if (ring.id().equals(id)) {
                return ring;
            }
        }
        return null;
    }

    /** {x, z} of the ring origin (world spawn by default, resolved live). */
    public double[] origin(ServerLevel level) {
        if (config.useWorldSpawn()) {
            BlockPos spawn = level.getServer().overworld().getSharedSpawnPos();
            return new double[]{spawn.getX() + 0.5, spawn.getZ() + 0.5};
        }
        return new double[]{config.originX(), config.originZ()};
    }

    public double distanceFromOrigin(ServerLevel level, double x, double z) {
        double[] o = origin(level);
        return Math.hypot(x - o[0], z - o[1]);
    }

    /** Blocks until the next ring outward, or -1 if there is none here. */
    public double blocksToNextRing(ServerLevel level, double x, double z) {
        if (!isRadial(level)) {
            return -1;
        }
        Ring ring = ringAt(level, x, z);
        if (ring == null || ring.unbounded()) {
            return -1;
        }
        return RingLookup.outer(ring.def(), scale(level)) - distanceFromOrigin(level, x, z);
    }

    public List<Ring> rings() {
        return rings;
    }

    public double maxHealthMult() {
        return config.maxHealthMult();
    }

    public double maxDamageMult() {
        return config.maxDamageMult();
    }

    /** Natural-ish spawns get ring treatment; spawners, eggs, commands and farms don't. */
    public boolean isNaturalSpawnType(MobSpawnType type) {
        return !config.excludedSpawnTypes().contains(type.name());
    }

    /** Bosses and blacklisted mobs are never scaled, promoted or made to dig. */
    public boolean isExcluded(Mob mob) {
        if (config.skipBosses() && mob.getType().is(Tags.EntityTypes.BOSSES)) {
            return true;
        }
        return entityBlacklist.contains(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
    }
}
