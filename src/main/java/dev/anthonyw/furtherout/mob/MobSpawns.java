package dev.anthonyw.furtherout.mob;

import dev.anthonyw.furtherout.ring.Ring;
import dev.anthonyw.furtherout.ring.RingManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Gives naturally spawned hostiles their ring: scaling, elite roll, digger
 * roll. In the safe zone, natural hostile spawns are cancelled outright.
 * Spawners, spawn eggs, commands, breeding etc. are left completely alone,
 * so mob farms built on spawners behave exactly as in vanilla.
 */
public final class MobSpawns {
    private static final String TAG_SUPPRESS = "fo_suppress";

    @SubscribeEvent
    public void onFinalizeSpawn(FinalizeSpawnEvent event) {
        Mob mob = event.getEntity();
        if (!(mob instanceof Enemy) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        RingManager mgr = RingManager.get();
        if (mgr == null || !mgr.isNaturalSpawnType(event.getSpawnType()) || mgr.isExcluded(mob)) {
            return;
        }
        Ring ring = mgr.ringAt(level, event.getX(), event.getZ());
        if (ring == null) {
            return;
        }
        if (ring.safeZone()) {
            event.setSpawnCancelled(true);
            // Belt and braces: if a spawn path ignores the cancel, the join is refused below.
            mob.getPersistentData().putBoolean(TAG_SUPPRESS, true);
            return;
        }
        Elites.scale(mob, ring);
        Elites.rollElite(mob, ring);
        Elites.rollDigger(mob, ring);
    }

    /** Re-attach AI goals to mobs loaded from disk (goals aren't saved, flags are). */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        CompoundTag data = mob.getPersistentData();
        if (data.getBoolean(TAG_SUPPRESS)) {
            event.setCanceled(true);
            return;
        }
        if (data.getBoolean(Elites.TAG_DIGGER)) {
            DigGoal.attach(mob);
        }
        if (data.getBoolean(EliteAbilities.TAG_STOLEN)) {
            EliteAbilities.attachFleeGoal(mob);
        }
    }
}
