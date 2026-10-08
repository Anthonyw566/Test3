package dev.anthonyw.frontiers.mob;

import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.core.EliteModifier;
import dev.anthonyw.frontiers.core.Magnet;
import dev.anthonyw.frontiers.core.MechanicsConfig;
import dev.anthonyw.frontiers.player.DownedManager;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.util.McRand;
import dev.anthonyw.frontiers.util.Scheduler;
import dev.anthonyw.frontiers.util.SpawnUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
 * The five elite abilities. Each one is loud and readable: a particle aura
 * names the ability at a glance, every activation has a sound, and the first
 * time an ability touches you, chat explains it in one line.
 */
public final class EliteAbilities {
    public static final String TAG_STOLEN = "df_stolen";
    private static final String TAG_THIEF_VICTIM = "df_victim";
    private static final String TAG_WARP_READY = "df_warp_ready";
    private static final String TAG_MAG_NEXT = "df_mag_next";
    private static final String TAG_TIPS = "df_tips";

    // ------------------------------------------------------------------ ambient + magnetic

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.tickCount % 20 != 0 || !(entity instanceof Mob mob)
                || !(mob.level() instanceof ServerLevel level) || !mob.isAlive()) {
            return;
        }
        if (!Elites.isElite(mob)) {
            return;
        }
        for (EliteModifier modifier : Elites.modifiers(mob)) {
            level.sendParticles(aura(modifier), mob.getX(), mob.getY() + mob.getBbHeight() * 0.6, mob.getZ(),
                    4, 0.3, 0.45, 0.3, 0.02);
        }
        if (Elites.has(mob, EliteModifier.MAGNETIC)) {
            tickMagnetic(mob, level);
        }
    }

    private static SimpleParticleType aura(EliteModifier modifier) {
        return switch (modifier) {
            case WARPER -> ParticleTypes.REVERSE_PORTAL;
            case THIEF -> ParticleTypes.SMOKE;
            case MAGNETIC -> ParticleTypes.ELECTRIC_SPARK;
            case VOLATILE -> ParticleTypes.FLAME;
            case WARDED -> ParticleTypes.ENCHANT;
        };
    }

    private static void tickMagnetic(Mob mob, ServerLevel level) {
        MechanicsConfig.Magnetic cfg = Configs.mechanics().magnetic();
        CompoundTag data = mob.getPersistentData();
        long now = level.getGameTime();
        if (now < data.getLong(TAG_MAG_NEXT)) {
            return;
        }
        if (!(mob.getTarget() instanceof Player target) || mob.distanceToSqr(target) > 16 * 16) {
            return;
        }
        data.putLong(TAG_MAG_NEXT, now + cfg.intervalTicks());
        // Wind-up: a rising hum and converging sparks, then the yank.
        level.playSound(null, mob.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.2f, 1.6f);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mob.getX(), mob.getY() + 1, mob.getZ(),
                40, cfg.radius() / 3, 1.0, cfg.radius() / 3, 0.0);
        Scheduler.schedule(level.getServer(), cfg.windupTicks(), () -> magneticPulse(mob));
    }

    /** Drags every player within range toward the mob. Public so in-game tests can trigger it. */
    public static void magneticPulse(Mob mob) {
        if (!mob.isAlive() || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        MechanicsConfig.Magnetic cfg = Configs.mechanics().magnetic();
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class,
                mob.getBoundingBox().inflate(cfg.radius()), EliteAbilities::affectable)) {
            double[] v = Magnet.pull(mob.getX() - player.getX(), mob.getY() - player.getY(),
                    mob.getZ() - player.getZ(), cfg.strength(), cfg.lift());
            player.setDeltaMovement(v[0], v[1], v[2]);
            player.hurtMarked = true; // sends the velocity to the client
        }
        level.playSound(null, mob.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 0.6f, 1.8f);
        level.sendParticles(ParticleTypes.SONIC_BOOM, mob.getX(), mob.getY() + 1, mob.getZ(), 1, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------ on-hit: warper, thief, warded

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        // Warded: barely hurt by the one it's chasing.
        if (event.getEntity() instanceof Mob mob && Elites.has(mob, EliteModifier.WARDED)
                && event.getSource().getEntity() != null && event.getSource().getEntity() == mob.getTarget()
                && mob.level() instanceof ServerLevel level) {
            event.setAmount(event.getAmount() * (float) Configs.mechanics().warded().targetDamageMultiplier());
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, mob.getX(), mob.getY() + 1, mob.getZ(),
                    12, 0.4, 0.5, 0.4, 0.2);
            level.playSound(null, mob.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.8f, 1.2f);
            if (event.getSource().getEntity() instanceof ServerPlayer attacker) {
                attacker.displayClientMessage(Component.literal(Elites.displayName(mob)
                                + " is fixated on you - your hits glance off. Someone else has to hit it!")
                        .withStyle(ChatFormatting.LIGHT_PURPLE), true);
            }
        }

        if (!(event.getEntity() instanceof ServerPlayer victim) || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof Mob attacker) || !Elites.isElite(attacker)) {
            return;
        }
        if (!affectable(victim)) {
            return;
        }
        showTips(victim, attacker);
        if (Elites.has(attacker, EliteModifier.WARPER)) {
            tryWarp(victim, attacker, level);
        }
        if (Elites.has(attacker, EliteModifier.THIEF) && event.getSource().getDirectEntity() == attacker) {
            trySteal(victim, attacker, level);
        }
    }

    private static boolean affectable(ServerPlayer player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator()
                && !DownedManager.isDowned(player);
    }

    /** One line of explanation the first time each ability touches a player. */
    private static void showTips(ServerPlayer player, Mob attacker) {
        CompoundTag data = player.getPersistentData();
        String seen = data.getString(TAG_TIPS);
        for (EliteModifier modifier : Elites.modifiers(attacker)) {
            if (!(";" + seen + ";").contains(";" + modifier.id() + ";")) {
                seen = seen.isEmpty() ? modifier.id() : seen + ";" + modifier.id();
                player.sendSystemMessage(Component.literal("⚑ " + capitalize(modifier.epithet().substring(4)) + ": ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(modifier.tip()).withStyle(ChatFormatting.GRAY)));
            }
        }
        data.putString(TAG_TIPS, seen);
    }

    // ------------------------------------------------------------------ warper

    private static void tryWarp(ServerPlayer victim, Mob attacker, ServerLevel level) {
        MechanicsConfig.Warper cfg = Configs.mechanics().warper();
        CompoundTag data = attacker.getPersistentData();
        long now = level.getGameTime();
        if (now < data.getLong(TAG_WARP_READY) || level.random.nextDouble() >= cfg.procChance()) {
            return;
        }
        data.putLong(TAG_WARP_READY, now + cfg.cooldownTicks());
        victim.stopRiding();
        String who = Elites.displayName(attacker);

        Ring ring = RingManager.ringOf(attacker);
        if (ring != null && level.dimension() == Level.OVERWORLD
                && cfg.rollRift(new McRand(level.random), ring.id()) && riftToNether(victim, who, level)) {
            return;
        }
        switch (cfg.roll(new McRand(level.random))) {
            case TOSS -> {
                if (!toss(victim, who, level)) {
                    scatter(victim, who, level);
                }
            }
            case SWAP -> {
                ServerPlayer friend = randomFriendNear(victim, cfg.swapRange());
                if (friend != null) {
                    swapPlayers(victim, friend, who, level);
                } else {
                    swapWithMob(victim, attacker, who, level);
                }
            }
            case SCATTER -> scatter(victim, who, level);
        }
    }

    /** Flung straight up ~10 blocks. Needs clear sky; otherwise falls back to scatter. */
    private static boolean toss(ServerPlayer player, String who, ServerLevel level) {
        BlockPos feet = player.blockPosition();
        int clear = 0;
        while (clear < 10 && level.getBlockState(feet.above(clear + 2))
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
        player.displayClientMessage(Component.literal(who + " flings you into the sky!")
                .withStyle(ChatFormatting.DARK_PURPLE), true);
        return true;
    }

    /** The friendship tester: you and a nearby friend trade places. */
    private static void swapPlayers(ServerPlayer victim, ServerPlayer friend, String who, ServerLevel level) {
        Vec3 a = victim.position();
        Vec3 b = friend.position();
        friend.stopRiding();
        victim.teleportTo(b.x, b.y, b.z);
        friend.teleportTo(a.x, a.y, a.z);
        victim.resetFallDistance();
        friend.resetFallDistance();
        warpFx(level, a);
        warpFx(level, b);
        victim.sendSystemMessage(Component.literal(who + " swapped you with " + friend.getName().getString() + "!")
                .withStyle(ChatFormatting.DARK_PURPLE));
        friend.sendSystemMessage(Component.literal(who + " warped you into "
                        + victim.getName().getString() + "'s fight!")
                .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
    }

    private static void swapWithMob(ServerPlayer victim, Mob attacker, String who, ServerLevel level) {
        Vec3 a = victim.position();
        Vec3 b = attacker.position();
        attacker.teleportTo(a.x, a.y, a.z);
        victim.teleportTo(b.x, b.y, b.z);
        victim.resetFallDistance();
        warpFx(level, a);
        warpFx(level, b);
        victim.displayClientMessage(Component.literal("You trade places with " + who + "!")
                .withStyle(ChatFormatting.DARK_PURPLE), true);
    }

    private static void scatter(ServerPlayer player, String who, ServerLevel level) {
        BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 8, 14);
        if (pos == null) {
            return;
        }
        Vec3 from = player.position();
        player.teleportTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.resetFallDistance();
        warpFx(level, from);
        warpFx(level, player.position());
        player.displayClientMessage(Component.literal(who + " folds space - you're somewhere else.")
                .withStyle(ChatFormatting.DARK_PURPLE), true);
    }

    /** Rare, deep rings only: ripped into the Nether at 1:8 coordinates, onto safe ground. */
    private static boolean riftToNether(ServerPlayer player, String who, ServerLevel level) {
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            return false;
        }
        int x = (int) Math.floor(player.getX() / 8);
        int z = (int) Math.floor(player.getZ() / 8);
        for (int y = 100; y >= 32; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (SpawnUtil.isStandable(nether, pos) && nether.getFluidState(pos.below()).isEmpty()) {
                warpFx(level, player.position());
                player.teleportTo(nether, x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
                player.playNotifySound(SoundEvents.PORTAL_TRAVEL, SoundSource.MASTER, 0.5f, 0.8f);
                player.sendSystemMessage(Component.literal("Reality tears. You smell sulfur. Find your own way home.")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                level.getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                                "⚠ " + player.getName().getString() + " was ripped into the Nether by " + who + "!")
                        .withStyle(ChatFormatting.DARK_PURPLE), false);
                return true;
            }
        }
        return false;
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
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + 1, pos.z, 40, 0.4, 0.8, 0.4, 0.15);
        level.playSound(null, BlockPos.containing(pos), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.8f);
    }

    // ------------------------------------------------------------------ thief

    private static void trySteal(ServerPlayer victim, Mob thief, ServerLevel level) {
        CompoundTag data = thief.getPersistentData();
        if (data.getBoolean(TAG_STOLEN) || level.random.nextDouble() >= Configs.mechanics().thief().procChance()) {
            return;
        }
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (!victim.getInventory().getItem(i).isEmpty()) {
                slots.add(i);
            }
        }
        if (slots.isEmpty()) {
            return;
        }
        int slot = slots.get(level.random.nextInt(slots.size()));
        ItemStack loot = victim.getInventory().getItem(slot).copy();
        victim.getInventory().setItem(slot, ItemStack.EMPTY);

        thief.setItemSlot(EquipmentSlot.MAINHAND, loot);
        thief.setDropChance(EquipmentSlot.MAINHAND, 0f); // we drop it ourselves, safely
        data.putBoolean(TAG_STOLEN, true);
        data.putString(TAG_THIEF_VICTIM, victim.getName().getString());
        thief.setPersistenceRequired();
        int flee = Configs.mechanics().thief().fleeTicks();
        thief.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, flee, 1));
        thief.addEffect(new MobEffectInstance(MobEffects.GLOWING, flee * 3, 0));
        thief.setTarget(null);
        attachFleeGoal(thief);

        level.playSound(null, thief.blockPosition(), SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 1.0f, 1.4f);
        String who = Elites.displayName(thief);
        victim.sendSystemMessage(Component.literal(who + " stole your ")
                .withStyle(ChatFormatting.RED)
                .append(loot.getHoverName().copy().withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("! Kill it to get it back.").withStyle(ChatFormatting.RED)));
        for (ServerPlayer other : level.players()) {
            if (other != victim && other.distanceToSqr(victim) < 48 * 48) {
                other.sendSystemMessage(Component.literal(who + " stole " + victim.getName().getString() + "'s ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(loot.getHoverName().copy().withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" - it's getting away!").withStyle(ChatFormatting.GRAY)));
            }
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
        String victim = mob.getPersistentData().getString(TAG_THIEF_VICTIM);
        ServerPlayer owner = level.getServer().getPlayerList().getPlayerByName(victim);
        if (owner != null) {
            owner.sendSystemMessage(Component.literal("Your stolen ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(loot.getHoverName().copy().withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" dropped at " + mob.blockPosition().toShortString() + " (it glows).")
                            .withStyle(ChatFormatting.GREEN)));
        }
    }

    // ------------------------------------------------------------------ volatile

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob mob && Elites.has(mob, EliteModifier.VOLATILE)
                && mob.level() instanceof ServerLevel level) {
            detonate(level, mob.position());
        }
    }

    /** Hisses and smokes for the fuse, then blows. Hurts everyone; breaks no blocks. */
    public static void detonate(ServerLevel level, Vec3 pos) {
        MechanicsConfig.Volatile cfg = Configs.mechanics().volatileAbility();
        level.playSound(null, BlockPos.containing(pos), SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.5f, 0.6f);
        for (int t = 0; t < cfg.fuseTicks(); t += 5) {
            Scheduler.schedule(level.getServer(), t, () -> level.sendParticles(ParticleTypes.LARGE_SMOKE,
                    pos.x, pos.y + 0.6, pos.z, 6, 0.3, 0.3, 0.3, 0.02));
        }
        Scheduler.schedule(level.getServer(), cfg.fuseTicks(), () -> level.explode(null, pos.x, pos.y + 0.5, pos.z,
                (float) cfg.power(), Level.ExplosionInteraction.NONE));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
