package dev.anthonyw.furtherout.util;

import dev.anthonyw.furtherout.mob.Elites;
import dev.anthonyw.furtherout.ring.Ring;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

public final class SpawnUtil {
    private SpawnUtil() {
    }

    /** A spot a mob or player can stand on, {@code min}..{@code max} blocks from center, near its height. */
    @Nullable
    public static BlockPos findGroundNear(ServerLevel level, BlockPos center, int min, int max) {
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double dist = min + level.random.nextDouble() * (max - min);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);
            for (int dy = 4; dy >= -6; dy--) {
                BlockPos pos = new BlockPos(x, center.getY() + dy, z);
                if (isStandable(level, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    public static boolean isStandable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /**
     * Spawns a mob for an event (ambush etc.) with the ring's scaling applied.
     * Uses MOB_SUMMONED, which the natural spawn pipeline ignores, so nothing
     * is applied twice.
     */
    @Nullable
    public static Mob spawnForRing(ServerLevel level, @Nullable Ring ring, String entityId, BlockPos pos) {
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        EntityType<?> type = id == null ? null : EntityType.byString(entityId).orElse(null);
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
        if (ring != null) {
            Elites.scale(mob, ring);
        }
        return mob;
    }

    /** Drops a stack that can't burn, despawn or be lost easily, glowing so it's easy to find. */
    public static void dropSafely(ServerLevel level, Vec3 pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        double y = Math.max(pos.y, level.getMinBuildHeight() + 1);
        ItemEntity item = new ItemEntity(level, pos.x, y + 0.5, pos.z, stack);
        item.setInvulnerable(true);
        item.setUnlimitedLifetime();
        item.setGlowingTag(true);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
    }
}
