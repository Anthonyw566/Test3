package dev.anthonyw.furtherout.mob;

import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.MechanicsConfig;
import dev.anthonyw.furtherout.core.NoiseRules;
import dev.anthonyw.furtherout.player.Keepers;
import dev.anthonyw.furtherout.ring.RingManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Noise draws monsters. Explosions (TNT, creepers, a volatile elite) and
 * fights make idle monsters nearby come over to look; a groan or two lets you
 * know they're coming. Busy monsters ignore it and the safe area is quiet.
 */
public final class Noise {
    public static final Noise INSTANCE = new Noise();

    private final Map<UUID, Long> lastCombat = new HashMap<>();

    private Noise() {
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            make(level, event.getExplosion().center(), NoiseRules.Kind.EXPLOSION);
        }
    }

    /** A player hurting a monster, or being hurt by one. */
    @SubscribeEvent
    public void onDamage(LivingDamageEvent.Post event) {
        LivingEntity hurt = event.getEntity();
        Entity source = event.getSource().getEntity();
        ServerPlayer player = hurt instanceof ServerPlayer p ? p : source instanceof ServerPlayer q ? q : null;
        Entity other = player == hurt ? source : hurt;
        if (player == null || !(other instanceof Mob) || !(hurt.level() instanceof ServerLevel level)) {
            return;
        }
        long now = level.getGameTime();
        if (!NoiseRules.combatCooledDown(now, lastCombat.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2),
                Configs.mechanics().noise().combatCooldownTicks())) {
            return;
        }
        lastCombat.put(player.getUUID(), now);
        make(level, hurt.position(), NoiseRules.Kind.COMBAT);
    }

    /** Makes a noise at {@code at}; returns how many monsters come to look. */
    public static int make(ServerLevel level, Vec3 at, NoiseRules.Kind kind) {
        MechanicsConfig.Noise cfg = Configs.mechanics().noise();
        double radius = NoiseRules.radius(kind, cfg);
        if (!cfg.enabled() || radius <= 0) {
            return 0;
        }
        int listeners = 0;
        int cues = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(radius),
                m -> m instanceof Enemy && m instanceof PathfinderMob && m.isAlive() && listens(m))) {
            if (!NoiseRules.hears(mob.position().distanceTo(at), radius, mob.getTarget() != null,
                    RingManager.isSafe(level, mob.blockPosition()))) {
                continue;
            }
            InvestigateGoal.send(mob, at, cfg.investigateTicks());
            listeners++;
            if (cues < NoiseRules.CUES_PER_NOISE && level.random.nextBoolean()) {
                mob.playAmbientSound();
                cues++;
            }
        }
        return listeners;
    }

    /** Keepers stay put, thieves keep running, bosses do their own thing. */
    private static boolean listens(Mob mob) {
        return !Keepers.isKeeper(mob)
                && !mob.getPersistentData().getBoolean(EliteAbilities.TAG_STOLEN)
                && !mob.getType().is(Tags.EntityTypes.BOSSES);
    }

    public void clearAll() {
        lastCombat.clear();
    }
}
