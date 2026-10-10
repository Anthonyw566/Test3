package dev.anthonyw.furtherout.player;

import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.MarkRules;
import dev.anthonyw.furtherout.core.MechanicsConfig;
import dev.anthonyw.furtherout.fx.Fx;
import dev.anthonyw.furtherout.fx.Sfx;
import dev.anthonyw.furtherout.mob.EliteRewards;
import dev.anthonyw.furtherout.mob.Elites;
import dev.anthonyw.furtherout.ring.Ring;
import dev.anthonyw.furtherout.ring.RingManager;
import dev.anthonyw.furtherout.util.SpawnUtil;
import dev.anthonyw.furtherout.util.Tips;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Marked - a hot potato.
 *
 * Killing an elite can mark you (champions always do). While marked you glow,
 * nearby monsters come for you, a couple of small ambushes find you, and
 * everything you kill drops double. A purple boss bar shows the time left.
 * Let it run out away from spawn for some XP and loot - or hit another player
 * to hand it over (no tag-backs for a few seconds). Going home is always an
 * option; the mark just runs out there without paying anything. If you die
 * marked, it moves to the nearest player.
 */
public final class MarkManager {
    public static final MarkManager INSTANCE = new MarkManager();
    public static final String TAG = "fo_marked";
    private static final String TAG_TOTAL = "fo_marked_total";

    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    private final Map<UUID, Long> nextAmbush = new HashMap<>();
    private final Map<UUID, ServerBossEvent> bars = new HashMap<>();

    private MarkManager() {
    }

    public static int remaining(ServerPlayer player) {
        return player.getPersistentData().getInt(TAG);
    }

    private static void store(ServerPlayer player, int ticks) {
        if (ticks > 0) {
            player.getPersistentData().putInt(TAG, ticks);
        } else {
            player.getPersistentData().remove(TAG);
            player.getPersistentData().remove(TAG_TOTAL);
        }
    }

    // ------------------------------------------------------------------ gaining

    /** Marks a player (never shortening an existing mark). Public for commands and tests. */
    public void give(ServerPlayer player, int ticks) {
        int before = remaining(player);
        int after = MarkRules.onGain(before, ticks);
        store(player, after);
        player.getPersistentData().putInt(TAG_TOTAL, Math.max(after, player.getPersistentData().getInt(TAG_TOTAL)));
        nextAmbush.put(player.getUUID(), player.serverLevel().getGameTime() + Configs.mechanics().marked().firstAmbushTicks());
        if (before > 0) {
            return;
        }
        Fx.soundTo(player, Sfx.MARKED_GAIN);
        player.displayClientMessage(Component.literal("You've been marked").withStyle(ChatFormatting.LIGHT_PURPLE), true);
        explain(player);
        updateBar(player, after, false);
    }

    private static void explain(ServerPlayer player) {
        Tips.once(player, "marked", "Marked: monsters nearby come for you and everything you kill drops double."
                + " Let it run out away from spawn for a reward, or hit another player to pass it on.");
    }

    public void clear(ServerPlayer player) {
        store(player, 0);
        player.removeEffect(MobEffects.GLOWING);
        nextAmbush.remove(player.getUUID());
        hideBar(player.getUUID());
    }

    /** Killing an elite: champions always mark their killer, elites sometimes. */
    @SubscribeEvent
    public void onEliteDeath(LivingDeathEvent event) {
        MechanicsConfig.Marked cfg = Configs.mechanics().marked();
        if (!cfg.enabled() || !(event.getEntity() instanceof Mob mob) || !Elites.isElite(mob)) {
            return;
        }
        ServerPlayer killer = EliteRewards.killer(event.getSource());
        if (killer == null || killer.isCreative() || killer.isSpectator()) {
            return;
        }
        boolean champion = Elites.tier(mob) >= 2;
        if ((champion && cfg.championAlways()) || killer.getRandom().nextDouble() < cfg.eliteChance()) {
            give(killer, cfg.durationTicks());
        }
    }

    // ------------------------------------------------------------------ passing

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer attacker) || !(event.getTarget() instanceof ServerPlayer target)) {
            return;
        }
        if (!Configs.mechanics().marked().enabled()) {
            return;
        }
        long now = attacker.serverLevel().getGameTime();
        if (!MarkRules.canPass(remaining(attacker), remaining(target), attacker == target, target.isSpectator(),
                now, immuneUntil.getOrDefault(target.getUUID(), 0L))) {
            return;
        }
        pass(attacker, target);
    }

    private void pass(ServerPlayer from, ServerPlayer to) {
        MechanicsConfig.Marked cfg = Configs.mechanics().marked();
        int ticks = remaining(from);
        int total = Math.max(ticks, from.getPersistentData().getInt(TAG_TOTAL));
        clear(from);
        immuneUntil.put(from.getUUID(), from.serverLevel().getGameTime() + cfg.passBackImmunityTicks());
        store(to, ticks);
        to.getPersistentData().putInt(TAG_TOTAL, total);
        nextAmbush.put(to.getUUID(), to.serverLevel().getGameTime() + cfg.firstAmbushTicks());

        Fx.sound(to.serverLevel(), to.position(), Sfx.MARKED_PASS);
        to.serverLevel().sendParticles(ParticleTypes.WITCH, to.getX(), to.getY() + 1, to.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
        to.displayClientMessage(Component.literal(from.getName().getString() + " passed the mark to you")
                .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        from.displayClientMessage(Component.literal("Passed the mark to " + to.getName().getString())
                .withStyle(ChatFormatting.GRAY), true);
        explain(to);
        updateBar(to, ticks, false);
    }

    /** Die marked and it moves to whoever is nearest (Downed comes first; real deaths only). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer dead) || remaining(dead) <= 0) {
            return;
        }
        MechanicsConfig.Marked cfg = Configs.mechanics().marked();
        int ticks = remaining(dead);
        clear(dead);
        List<MarkRules.Candidate<ServerPlayer>> candidates = new ArrayList<>();
        for (ServerPlayer p : dead.serverLevel().players()) {
            if (p != dead && p.isAlive() && !p.isSpectator() && !p.isCreative()) {
                candidates.add(new MarkRules.Candidate<>(p, p.distanceTo(dead)));
            }
        }
        ServerPlayer next = MarkRules.nearest(candidates, cfg.jumpRange());
        if (next != null) {
            give(next, MarkRules.jumpDuration(ticks, cfg.minJumpTicks()));
            next.displayClientMessage(Component.literal("The mark moved to you from " + dead.getName().getString())
                    .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        }
    }

    /** A respawned player never carries the old mark. */
    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        if (event.isWasDeath()) {
            event.getEntity().getPersistentData().remove(TAG);
            event.getEntity().getPersistentData().remove(TAG_TOTAL);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        hideBar(event.getEntity().getUUID());
    }

    // ------------------------------------------------------------------ while marked

    /** Called once per second by the server ticker. */
    public void tick(MinecraftServer server) {
        MechanicsConfig.Marked cfg = Configs.mechanics().marked();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int before = remaining(player);
            if (before <= 0) {
                continue;
            }
            if (!cfg.enabled()) {
                clear(player);
                continue;
            }
            Ring ring = RingManager.ringOf(player);
            boolean safe = ring != null && ring.safeZone();
            int after = MarkRules.tick(before, 20);
            store(player, after);

            player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 50, 0, false, false));
            player.serverLevel().sendParticles(ParticleTypes.WITCH, player.getX(), player.getY() + 1, player.getZ(),
                    3, 0.4, 0.6, 0.4, 0.02);

            if (!safe && player.isAlive() && !player.isSpectator()) {
                lure(player, cfg);
                maybeAmbush(player, ring, after, cfg);
            }
            if (after == 0) {
                fade(player, ring, safe);
            } else {
                updateBar(player, after, safe);
            }
        }
    }

    private void updateBar(ServerPlayer player, int remaining, boolean safe) {
        ServerBossEvent bar = bars.computeIfAbsent(player.getUUID(), id -> new ServerBossEvent(
                Component.literal("Marked"), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS));
        bar.addPlayer(player);
        bar.setColor(safe ? BossEvent.BossBarColor.WHITE : BossEvent.BossBarColor.PURPLE);
        bar.setProgress(MarkRules.progress(remaining, player.getPersistentData().getInt(TAG_TOTAL)));
    }

    private void hideBar(UUID player) {
        ServerBossEvent bar = bars.remove(player);
        if (bar != null) {
            bar.removeAllPlayers();
        }
    }

    /** Hostiles in range drop what they're doing and come for the marked player. */
    private static void lure(ServerPlayer player, MechanicsConfig.Marked cfg) {
        for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class,
                player.getBoundingBox().inflate(cfg.lureRadius()),
                m -> m instanceof Enemy && m.isAlive() && m.getTarget() != player)) {
            mob.setTarget(player);
        }
    }

    private void maybeAmbush(ServerPlayer player, Ring ring, int remaining, MechanicsConfig.Marked cfg) {
        if (ring == null || cfg.ambushMobs().isEmpty()) {
            return;
        }
        long now = player.serverLevel().getGameTime();
        long due = nextAmbush.computeIfAbsent(player.getUUID(), k -> now + cfg.firstAmbushTicks());
        if (!MarkRules.ambushDue(now, due, remaining, ring.safeZone(), ring.danger())) {
            return;
        }
        nextAmbush.put(player.getUUID(), now + cfg.ambushEveryTicks());
        ServerLevel level = player.serverLevel();
        int count = MarkRules.ambushSize(cfg.ambushBaseSize(), ring.danger());
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 12, 20);
            if (pos == null) {
                continue;
            }
            String id = cfg.ambushMobs().get(level.random.nextInt(cfg.ambushMobs().size()));
            Mob mob = SpawnUtil.spawnForRing(level, ring, id, pos);
            if (mob != null) {
                Elites.makeDigger(mob);
                mob.setTarget(player);
                level.sendParticles(ParticleTypes.POOF, mob.getX(), mob.getY() + 0.5, mob.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
                spawned++;
            }
        }
        if (spawned > 0) {
            Fx.sound(level, player.position(), Sfx.MARKED_AMBUSH);
        }
    }

    private void fade(ServerPlayer player, Ring ring, boolean safe) {
        MechanicsConfig.Marked cfg = Configs.mechanics().marked();
        clear(player);
        Fx.soundTo(player, Sfx.MARKED_END);
        if (!MarkRules.earnsReward(safe)) {
            player.displayClientMessage(Component.literal("The mark fades").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        player.displayClientMessage(Component.literal("The mark fades").withStyle(ChatFormatting.LIGHT_PURPLE), true);
        ExperienceOrb.award(player.serverLevel(), player.position(), cfg.survivalXp());
        if (ring != null) {
            for (ItemStack stack : EliteRewards.rollLoot(player.serverLevel(), ring.eliteLoot(), player.position(), 1)) {
                player.serverLevel().addFreshEntity(new ItemEntity(player.serverLevel(),
                        player.getX(), player.getY() + 0.5, player.getZ(), stack));
            }
        }
    }

    // ------------------------------------------------------------------ greed

    /** Everything a marked player kills drops double. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onDrops(LivingDropsEvent event) {
        if (!Configs.mechanics().marked().doubleDrops() || event.getEntity() instanceof ServerPlayer) {
            return;
        }
        ServerPlayer killer = EliteRewards.killer(event.getSource());
        if (killer == null || remaining(killer) <= 0) {
            return;
        }
        List<ItemEntity> extra = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            extra.add(new ItemEntity(drop.level(), drop.getX(), drop.getY(), drop.getZ(), drop.getItem().copy()));
        }
        event.getDrops().addAll(extra);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onExperience(LivingExperienceDropEvent event) {
        if (Configs.mechanics().marked().doubleDrops() && event.getAttackingPlayer() instanceof ServerPlayer killer
                && remaining(killer) > 0 && !(event.getEntity() instanceof ServerPlayer)) {
            event.setDroppedExperience(event.getDroppedExperience() * 2);
        }
    }

    public void clearAll() {
        immuneUntil.clear();
        nextAmbush.clear();
        bars.values().forEach(ServerBossEvent::removeAllPlayers);
        bars.clear();
    }
}
