package dev.anthonyw.frontiers.elite;

import dev.anthonyw.frontiers.scaling.SpawnScaling;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Event-driven behavior for the elite modifiers. Every ability is telegraphed:
 * ambient particles identify the modifier at a glance, and each activation has
 * its own sound. Nothing here stuns players or deals unavoidable damage.
 */
public final class EliteBehaviors {
    private static final String TAG_BLINK_READY = "df_blink_ready"; // game time
    private static final String TAG_SUMMON_READY = "df_summon_ready";
    private static final String TAG_MINION = "df_minion";
    public static final String TAG_NO_MARKS = "df_no_marks";

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob) || mob.level().isClientSide) {
            return;
        }
        if (mob.tickCount % 20 != 0) {
            return;
        }
        int tier = Elites.tier(mob);
        if (tier <= 0 || !(mob.level() instanceof ServerLevel level)) {
            return;
        }

        ambientTelegraph(mob, level);

        if (Elites.hasModifier(mob, EliteModifier.SUMMONER) && mob.getTarget() instanceof Player) {
            tickSummoner(mob, level);
        }
        if (Elites.hasModifier(mob, EliteModifier.CORROSIVE) && mob.getTarget() instanceof Player target
                && mob.distanceToSqr(target) < 16 && mob.tickCount % 160 == 0) {
            spawnAcid(level, mob.getX(), mob.getY(), mob.getZ(), 1.5f, 60);
        }
    }

    /** One second-interval particle puff per modifier so players can read the threat. */
    private static void ambientTelegraph(Mob mob, ServerLevel level) {
        String mods = mob.getPersistentData().getString(SpawnScaling.TAG_MODS);
        if (mods.isEmpty()) {
            return;
        }
        for (String id : mods.split(",")) {
            EliteModifier modifier = EliteModifier.byId(id);
            if (modifier == null) {
                continue;
            }
            SimpleParticleType particle = switch (modifier) {
                case SWIFT -> ParticleTypes.CLOUD;
                case STONEHIDE -> ParticleTypes.WHITE_ASH;
                case SUMMONER -> ParticleTypes.ENCHANT;
                case BLINKSTEP -> ParticleTypes.PORTAL;
                case CORROSIVE -> ParticleTypes.ITEM_SLIME;
                case VENGEFUL -> ParticleTypes.ANGRY_VILLAGER;
            };
            level.sendParticles(particle,
                    mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                    3, 0.3, 0.4, 0.3, 0.02);
        }
    }

    private static void tickSummoner(Mob mob, ServerLevel level) {
        var data = mob.getPersistentData();
        long now = level.getGameTime();
        if (now < data.getLong(TAG_SUMMON_READY)) {
            return;
        }
        long nearbyMinions = level.getEntitiesOfClass(Mob.class,
                        mob.getBoundingBox().inflate(16),
                        m -> m.getPersistentData().getBoolean(TAG_MINION))
                .size();
        if (nearbyMinions >= 2) {
            return;
        }
        data.putLong(TAG_SUMMON_READY, now + 240); // 12s
        level.playSound(null, mob.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON,
                SoundSource.HOSTILE, 1.0f, 1.0f);
        for (int i = 0; i < 2; i++) {
            BlockPos pos = mob.blockPosition().offset(
                    level.random.nextInt(5) - 2, 0, level.random.nextInt(5) - 2);
            Silverfish minion = EntityType.SILVERFISH.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
            if (minion != null) {
                minion.getPersistentData().putBoolean(TAG_MINION, true);
                minion.getPersistentData().putBoolean(TAG_NO_MARKS, true);
                level.sendParticles(ParticleTypes.ENCHANT,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        20, 0.3, 0.5, 0.3, 0.5);
            }
        }
    }

    /** Blinkstep: short escape teleport when hit, on a 6 second cooldown. */
    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (!Elites.hasModifier(mob, EliteModifier.BLINKSTEP)) {
            return;
        }
        var data = mob.getPersistentData();
        long now = level.getGameTime();
        if (now < data.getLong(TAG_BLINK_READY)) {
            return;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            double x = mob.getX() + (level.random.nextDouble() - 0.5) * 16;
            double y = mob.getY() + (level.random.nextInt(9) - 4);
            double z = mob.getZ() + (level.random.nextDouble() - 0.5) * 16;
            if (mob.randomTeleport(x, y, z, false)) {
                data.putLong(TAG_BLINK_READY, now + 120);
                level.playSound(null, BlockPos.containing(x, y, z), SoundEvents.ENDERMAN_TELEPORT,
                        SoundSource.HOSTILE, 1.0f, 1.2f);
                break;
            }
        }
    }

    /** Corrosive death clouds and Vengeful enrage triggers. */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob dead) || !(dead.level() instanceof ServerLevel level)) {
            return;
        }
        if (Elites.hasModifier(dead, EliteModifier.CORROSIVE)) {
            spawnAcid(level, dead.getX(), dead.getY(), dead.getZ(), 2.5f, 100);
        }

        // Nearby Vengeful elites enrage when any mob dies around them.
        for (Mob ally : level.getEntitiesOfClass(Mob.class,
                dead.getBoundingBox().inflate(12),
                m -> m != dead && Elites.hasModifier(m, EliteModifier.VENGEFUL))) {
            ally.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 160, 1));
            ally.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 160, 0));
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                    ally.getX(), ally.getY() + ally.getBbHeight(), ally.getZ(),
                    10, 0.4, 0.4, 0.4, 0.1);
            level.playSound(null, ally.blockPosition(), SoundEvents.RAVAGER_ROAR,
                    SoundSource.HOSTILE, 0.8f, 1.3f);
        }
    }

    private static void spawnAcid(ServerLevel level, double x, double y, double z,
                                  float radius, int duration) {
        AreaEffectCloud cloud = new AreaEffectCloud(level, x, y, z);
        cloud.setRadius(radius);
        cloud.setDuration(duration);
        cloud.setWaitTime(10);
        cloud.setRadiusOnUse(-0.3f);
        cloud.setPotionContents(new PotionContents(Potions.HARMING));
        level.addFreshEntity(cloud);
    }
}
