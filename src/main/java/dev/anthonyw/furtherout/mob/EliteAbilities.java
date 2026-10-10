package dev.anthonyw.furtherout.mob;

import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.EliteModifier;
import dev.anthonyw.furtherout.core.Magnet;
import dev.anthonyw.furtherout.core.MechanicsConfig;
import dev.anthonyw.furtherout.fx.Fx;
import dev.anthonyw.furtherout.fx.Sfx;
import dev.anthonyw.furtherout.player.DownedManager;
import dev.anthonyw.furtherout.player.Rifts;
import dev.anthonyw.furtherout.ring.Ring;
import dev.anthonyw.furtherout.ring.RingManager;
import dev.anthonyw.furtherout.util.McRand;
import dev.anthonyw.furtherout.util.Scheduler;
import dev.anthonyw.furtherout.util.SpawnUtil;
import dev.anthonyw.furtherout.util.Tips;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The five elite abilities. Each one shows itself before it acts, each one
 * has a counter, and each one explains itself once, the first time it
 * touches a player.
 *
 * Warping   recharges, then swirls with portal particles; a charged hit teleports you. A shield stops it.
 * Thieving  grabs one stackable hotbar item (never what you're holding, never tools) and runs, glowing.
 * Magnetic  clicks, draws sparks to everyone it can see, then reels them in. Break line of sight.
 * Volatile  hisses for two seconds after it dies, then blows: capped damage, no block damage. Step back.
 * Warded    shrugs off the player it's chasing, but only while a second player is close by.
 */
public final class EliteAbilities {
    public static final String TAG_STOLEN = "fo_stolen";
    private static final String TAG_STOLE_ONCE = "fo_stole_once";
    private static final String TAG_WARP_READY = "fo_warp_ready";
    private static final String TAG_WARP_CUED = "fo_warp_cued";
    private static final String TAG_MAG_NEXT = "fo_mag_next";

    // ------------------------------------------------------------------ ambient tells

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.tickCount % 20 != 0 || !(entity instanceof Mob mob)
                || !(mob.level() instanceof ServerLevel level) || !mob.isAlive() || !Elites.isElite(mob)) {
            return;
        }
        for (EliteModifier modifier : Elites.modifiers(mob)) {
            switch (modifier) {
                case WARPER -> tickWarper(mob, level);
                case THIEF -> tickThief(mob, level);
                case MAGNETIC -> {
                    aura(level, mob, ParticleTypes.ELECTRIC_SPARK, 2);
                    tickMagnetic(mob, level);
                }
                case VOLATILE -> aura(level, mob, ParticleTypes.SMOKE, 3);
                case WARDED -> {
                    if (wardActive(mob)) {
                        aura(level, mob, ParticleTypes.ENCHANT, 8);
                    }
                }
            }
        }
    }

    private static void aura(ServerLevel level, Mob mob, ParticleOptions particle, int count) {
        level.sendParticles(particle, mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                count, 0.3, 0.45, 0.3, 0.02);
    }

    // ------------------------------------------------------------------ on-hit: warper, thief, warded

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        DamageSource source = event.getSource();
        if (event.getEntity() instanceof Mob mob && Elites.has(mob, EliteModifier.WARDED)
                && source.getEntity() != null && source.getEntity() == mob.getTarget()
                && mob.level() instanceof ServerLevel level && wardActive(mob)) {
            event.setAmount(event.getAmount() * (float) Configs.mechanics().warded().targetDamageMultiplier());
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, mob.getX(), mob.getY() + 1, mob.getZ(),
                    10, 0.4, 0.5, 0.4, 0.2);
            Fx.sound(level, mob.position(), Sfx.WARDED_DEFLECT);
            if (source.getEntity() instanceof ServerPlayer attacker) {
                showTips(attacker, mob);
            }
        }

        if (!(event.getEntity() instanceof ServerPlayer victim) || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        if (!(source.getEntity() instanceof Mob attacker) || !Elites.isElite(attacker) || !affectable(victim)) {
            return;
        }
        showTips(victim, attacker);
        if (victim.isDamageSourceBlocked(source)) {
            return; // a raised shield stops warps and theft
        }
        if (Elites.has(attacker, EliteModifier.WARPER)) {
            tryWarp(victim, attacker, level);
        }
        if (Elites.has(attacker, EliteModifier.THIEF) && source.getDirectEntity() == attacker) {
            trySteal(victim, attacker, level);
        }
    }

    private static boolean affectable(ServerPlayer player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator()
                && !DownedManager.isDowned(player);
    }

    /** One line of explanation the first time each ability touches a player - ever. */
    private static void showTips(ServerPlayer player, Mob elite) {
        for (EliteModifier modifier : Elites.modifiers(elite)) {
            Tips.once(player, "ability." + modifier.id(), modifier.tip());
        }
    }

    // ------------------------------------------------------------------ warper

    public static boolean warpCharged(Mob mob) {
        return mob.level().getGameTime() >= mob.getPersistentData().getLong(TAG_WARP_READY);
    }

    /** Charged: an enderman-like swirl and a single cue. Recharging: a faint shimmer. */
    private static void tickWarper(Mob mob, ServerLevel level) {
        if (!warpCharged(mob)) {
            aura(level, mob, ParticleTypes.REVERSE_PORTAL, 2);
            return;
        }
        level.sendParticles(ParticleTypes.PORTAL, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(),
                18, 0.35, 0.6, 0.35, 0.5);
        CompoundTag data = mob.getPersistentData();
        if (!data.getBoolean(TAG_WARP_CUED) && mob.getTarget() instanceof Player) {
            data.putBoolean(TAG_WARP_CUED, true);
            Fx.sound(level, mob.position(), Sfx.WARP_CHARGED);
        }
    }

    private static void tryWarp(ServerPlayer victim, Mob attacker, ServerLevel level) {
        if (!warpCharged(attacker)) {
            return;
        }
        MechanicsConfig.Warper cfg = Configs.mechanics().warper();
        CompoundTag data = attacker.getPersistentData();
        data.putLong(TAG_WARP_READY, level.getGameTime() + cfg.cooldownTicks());
        data.putBoolean(TAG_WARP_CUED, false);
        if (level.random.nextDouble() >= cfg.procChance()) {
            return;
        }
        // Let the hit and its knockback finish first; teleporting (especially across
        // dimensions) from inside the damage event is asking for trouble.
        Scheduler.schedule(level.getServer(), 1, () -> {
            if (affectable(victim) && victim.level() == level) {
                warp(victim, attacker, level, cfg);
            }
        });
    }

    private static void warp(ServerPlayer victim, Mob attacker, ServerLevel level, MechanicsConfig.Warper cfg) {
        victim.stopRiding();
        Ring ring = RingManager.ringOf(attacker);
        if (ring != null && level.dimension() == Level.OVERWORLD
                && cfg.rollRift(new McRand(level.random), ring.id())
                && Rifts.send(victim, cfg.riftTicks())) {
            warpFx(level, victim.position());
            return;
        }
        switch (cfg.roll(new McRand(level.random))) {
            case TOSS -> {
                if (!toss(victim, level)) {
                    scatter(victim, level);
                }
            }
            case SWAP -> {
                ServerPlayer friend = randomFriendNear(victim, cfg.swapRange());
                if (friend != null) {
                    swapPlayers(victim, friend, level);
                } else {
                    swapWithMob(victim, attacker, level);
                }
            }
            case SCATTER -> scatter(victim, level);
        }
    }

    /** Up ~8 blocks. Needs clear sky; otherwise falls back to scatter. */
    private static boolean toss(ServerPlayer player, ServerLevel level) {
        BlockPos feet = player.blockPosition();
        int clear = 0;
        while (clear < 8 && level.getBlockState(feet.above(clear + 2))
                .getCollisionShape(level, feet.above(clear + 2)).isEmpty()) {
            clear++;
        }
        if (clear < 6) {
            return false;
        }
        Vec3 from = player.position();
        player.teleportTo(player.getX(), player.getY() + clear, player.getZ());
        warpFx(level, from);
        warpFx(level, player.position());
        return true;
    }

    /** You and a nearby friend trade places. */
    private static void swapPlayers(ServerPlayer victim, ServerPlayer friend, ServerLevel level) {
        Vec3 a = victim.position();
        Vec3 b = friend.position();
        friend.stopRiding();
        victim.teleportTo(b.x, b.y, b.z);
        friend.teleportTo(a.x, a.y, a.z);
        victim.resetFallDistance();
        friend.resetFallDistance();
        warpFx(level, a);
        warpFx(level, b);
        victim.displayClientMessage(Component.literal("Swapped places with " + friend.getName().getString())
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        friend.displayClientMessage(Component.literal("Swapped places with " + victim.getName().getString())
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
    }

    private static void swapWithMob(ServerPlayer victim, Mob attacker, ServerLevel level) {
        Vec3 a = victim.position();
        Vec3 b = attacker.position();
        attacker.teleportTo(a.x, a.y, a.z);
        victim.teleportTo(b.x, b.y, b.z);
        victim.resetFallDistance();
        warpFx(level, a);
        warpFx(level, b);
    }

    private static void scatter(ServerPlayer player, ServerLevel level) {
        BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 8, 14);
        if (pos == null) {
            return;
        }
        Vec3 from = player.position();
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.resetFallDistance();
        warpFx(level, from);
        warpFx(level, player.position());
    }

    @Nullable
    private static ServerPlayer randomFriendNear(ServerPlayer victim, double range) {
        List<ServerPlayer> friends = new ArrayList<>();
        for (ServerPlayer p : victim.serverLevel().players()) {
            if (p != victim && affectable(p) && p.distanceToSqr(victim) <= range * range) {
                friends.add(p);
            }
        }
        return friends.isEmpty() ? null : friends.get(victim.getRandom().nextInt(friends.size()));
    }

    private static void warpFx(ServerLevel level, Vec3 pos) {
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + 1, pos.z, 30, 0.4, 0.8, 0.4, 0.1);
        Fx.sound(level, pos, Sfx.WARP);
    }

    // ------------------------------------------------------------------ thief

    private static void trySteal(ServerPlayer victim, Mob thief, ServerLevel level) {
        CompoundTag data = thief.getPersistentData();
        if (data.getBoolean(TAG_STOLE_ONCE) || level.random.nextDouble() >= Configs.mechanics().thief().procChance()) {
            return;
        }
        int slot = stealableSlot(victim, level.random);
        if (slot < 0) {
            return;
        }
        ItemStack loot = victim.getInventory().getItem(slot).copy();
        victim.getInventory().setItem(slot, ItemStack.EMPTY);

        thief.setItemSlot(EquipmentSlot.MAINHAND, loot);
        thief.setDropChance(EquipmentSlot.MAINHAND, 0f); // we drop it ourselves, safely
        data.putBoolean(TAG_STOLEN, true);
        data.putBoolean(TAG_STOLE_ONCE, true);
        thief.setPersistenceRequired(); // it can't despawn with your stuff
        int flee = Configs.mechanics().thief().fleeTicks();
        thief.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, flee, 1, false, false));
        thief.addEffect(new MobEffectInstance(MobEffects.GLOWING, -1, 0, false, false));
        thief.setTarget(null);
        attachFleeGoal(thief);

        Fx.sound(level, thief.position(), Sfx.THIEF_STEAL);
        victim.displayClientMessage(Component.literal("Stolen: ").withStyle(ChatFormatting.GRAY)
                .append(loot.getHoverName().copy().withStyle(ChatFormatting.WHITE)), true);
    }

    /**
     * A random hotbar slot holding something stackable that isn't in your
     * hand. Tools, weapons, armour, totems and shulker boxes don't stack, so
     * they are never taken. -1 when there's nothing fair to take.
     */
    public static int stealableSlot(ServerPlayer player, RandomSource random) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (i != player.getInventory().selected && !stack.isEmpty() && stack.isStackable()) {
                slots.add(i);
            }
        }
        return slots.isEmpty() ? -1 : slots.get(random.nextInt(slots.size()));
    }

    /** A thief carrying something sheds crumbs of it, so you can follow the trail. */
    private static void tickThief(Mob mob, ServerLevel level) {
        if (!mob.getPersistentData().getBoolean(TAG_STOLEN)) {
            return;
        }
        ItemStack held = mob.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!held.isEmpty()) {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, held), mob.getX(), mob.getY() + 0.8,
                    mob.getZ(), 4, 0.2, 0.3, 0.2, 0.03);
        }
    }

    /** A thief carrying loot runs from players. */
    public static void attachFleeGoal(Mob mob) {
        if (mob instanceof PathfinderMob pathfinder) {
            boolean present = mob.goalSelector.getAvailableGoals().stream()
                    .anyMatch(wrapped -> wrapped.getGoal() instanceof AvoidEntityGoal<?>);
            if (!present) {
                mob.goalSelector.addGoal(0, new AvoidEntityGoal<>(pathfinder, Player.class, 14f, 1.2, 1.5));
            }
        }
    }

    /** Whenever a thief leaves the world for good (killed, discarded), the loot drops safely. */
    @SubscribeEvent
    public void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        Entity.RemovalReason reason = mob.getRemovalReason();
        if (reason == null || !reason.shouldDestroy() || !mob.getPersistentData().getBoolean(TAG_STOLEN)) {
            return;
        }
        ItemStack loot = mob.getItemBySlot(EquipmentSlot.MAINHAND);
        mob.getPersistentData().remove(TAG_STOLEN);
        mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        SpawnUtil.dropSafely(level, mob.position(), loot);
    }

    // ------------------------------------------------------------------ magnetic

    private static void tickMagnetic(Mob mob, ServerLevel level) {
        MechanicsConfig.Magnetic cfg = Configs.mechanics().magnetic();
        CompoundTag data = mob.getPersistentData();
        long now = level.getGameTime();
        if (now < data.getLong(TAG_MAG_NEXT)) {
            return;
        }
        if (!(mob.getTarget() instanceof Player target) || mob.distanceToSqr(target) > 16 * 16
                || inReach(mob, level, cfg).isEmpty()) {
            return;
        }
        data.putLong(TAG_MAG_NEXT, now + cfg.intervalTicks());
        // Wind-up: a click, then a line of sparks to everyone about to be pulled.
        Fx.sound(level, mob.position(), Sfx.MAGNET_CHARGE);
        for (int t = 0; t < cfg.windupTicks(); t += 5) {
            Scheduler.schedule(level.getServer(), t, () -> sparkLines(mob, level, cfg));
        }
        Scheduler.schedule(level.getServer(), cfg.windupTicks(), () -> magneticPulse(mob));
    }

    private static List<ServerPlayer> inReach(Mob mob, ServerLevel level, MechanicsConfig.Magnetic cfg) {
        return level.getEntitiesOfClass(ServerPlayer.class, mob.getBoundingBox().inflate(cfg.radius()),
                p -> affectable(p) && (!cfg.needsLineOfSight() || mob.hasLineOfSight(p)));
    }

    private static void sparkLines(Mob mob, ServerLevel level, MechanicsConfig.Magnetic cfg) {
        if (!mob.isAlive()) {
            return;
        }
        Vec3 from = mob.position().add(0, mob.getBbHeight() * 0.6, 0);
        for (ServerPlayer player : inReach(mob, level, cfg)) {
            Vec3 to = player.position().add(0, 1, 0);
            for (int i = 1; i < 8; i++) {
                Vec3 at = from.lerp(to, i / 8.0);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0.05, 0.05, 0.05, 0);
            }
        }
    }

    /** Reels in every player in range it can see. Public so in-game tests can trigger it. */
    public static void magneticPulse(Mob mob) {
        if (!mob.isAlive() || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        MechanicsConfig.Magnetic cfg = Configs.mechanics().magnetic();
        for (ServerPlayer player : inReach(mob, level, cfg)) {
            double[] v = Magnet.pull(mob.getX() - player.getX(), mob.getY() - player.getY(),
                    mob.getZ() - player.getZ(), cfg.strength(), cfg.lift());
            player.setDeltaMovement(v[0], v[1], v[2]);
            player.hurtMarked = true; // sends the velocity to the client
        }
        Fx.sound(level, mob.position(), Sfx.MAGNET_PULL);
        level.sendParticles(ParticleTypes.CRIT, mob.getX(), mob.getY() + 1, mob.getZ(), 20, 0.5, 0.5, 0.5, 0.4);
    }

    // ------------------------------------------------------------------ volatile

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob mob && Elites.has(mob, EliteModifier.VOLATILE)
                && mob.level() instanceof ServerLevel level) {
            detonate(level, mob.position());
        }
    }

    /** Hisses and smokes for the fuse, then blows. Damage is capped; no blocks break. */
    public static void detonate(ServerLevel level, Vec3 pos) {
        MechanicsConfig.Volatile cfg = Configs.mechanics().volatileAbility();
        Fx.sound(level, pos, Sfx.VOLATILE_FUSE);
        for (int t = 0; t < cfg.fuseTicks(); t += 4) {
            int left = cfg.fuseTicks() - t;
            Scheduler.schedule(level.getServer(), t, () -> level.sendParticles(
                    left < 12 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    pos.x, pos.y + 0.6, pos.z, 6, 0.3, 0.3, 0.3, 0.02));
        }
        Scheduler.schedule(level.getServer(), cfg.fuseTicks(), () -> level.explode(null, null,
                new CappedBlast((float) cfg.maxDamage()), pos.x, pos.y + 0.5, pos.z,
                (float) cfg.power(), false, Level.ExplosionInteraction.NONE));
    }

    /** A blast that can hurt, but never more than {@code cap} (before armour). */
    private static final class CappedBlast extends ExplosionDamageCalculator {
        private final float cap;

        CappedBlast(float cap) {
            this.cap = cap;
        }

        @Override
        public float getEntityDamageAmount(Explosion explosion, Entity entity) {
            return Math.min(cap, super.getEntityDamageAmount(explosion, entity));
        }
    }

    // ------------------------------------------------------------------ warded

    /** Warding needs a group: it only works while a second player is close by. */
    public static boolean wardActive(Mob mob) {
        double range = Configs.mechanics().warded().groupRange();
        if (range <= 0) {
            return true;
        }
        int players = 0;
        for (Player p : mob.level().players()) {
            if (p.isAlive() && !p.isSpectator() && p.distanceToSqr(mob) <= range * range && ++players >= 2) {
                return true;
            }
        }
        return false;
    }
}
