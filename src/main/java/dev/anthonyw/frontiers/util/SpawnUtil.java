package dev.anthonyw.frontiers.util;

import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.scaling.SpawnScaling;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;

import javax.annotation.Nullable;

/**
 * Spawn helpers for ambushes, hunters, bounty quarries and cache guards.
 * Everything spawned through here uses MOB_SUMMONED (excluded from the
 * natural pipeline) and is then scaled manually, so behavior is fully
 * controlled and never double-applied.
 */
public final class SpawnUtil {
    private SpawnUtil() {
    }

    /**
     * Finds a standable position between {@code minDist} and {@code maxDist}
     * blocks of {@code center}, biased to similar height - works on the
     * surface and in caves. Null if nothing suitable is found.
     */
    @Nullable
    public static BlockPos findGroundNear(ServerLevel level, BlockPos center, int minDist, int maxDist) {
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double dist = minDist + level.random.nextDouble() * (maxDist - minDist);
            int x = center.getX() + (int) (Math.cos(angle) * dist);
            int z = center.getZ() + (int) (Math.sin(angle) * dist);
            for (int dy = 4; dy >= -6; dy--) {
                BlockPos pos = new BlockPos(x, center.getY() + dy, z);
                if (isStandable(level, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /**
     * Spawns a mob by id at the position and applies the ring's manual
     * scaling. Returns null when the id is unknown or spawning fails.
     */
    @Nullable
    public static Mob spawnScaled(ServerLevel level, Ring ring, String mobId, BlockPos pos) {
        EntityType<?> type = EntityType.byString(mobId).orElse(null);
        if (type == null) {
            return null;
        }
        Entity entity = type.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return null;
        }
        SpawnScaling.applyManual(mob, ring);
        return mob;
    }
}
