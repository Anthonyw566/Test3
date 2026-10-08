package dev.anthonyw.frontiers.player;

import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.core.HexRules;
import dev.anthonyw.frontiers.core.MechanicsConfig;
import dev.anthonyw.frontiers.fx.Fx;
import dev.anthonyw.frontiers.fx.Glyphs;
import dev.anthonyw.frontiers.fx.Sfx;
import dev.anthonyw.frontiers.mob.EliteRewards;
import dev.anthonyw.frontiers.mob.Elites;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.util.SpawnUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Hex - a cursed hot potato.
 *
 * Kill an elite and its dying curse may land on you (champions always curse).
 * While hexed you glow, every hostile nearby turns on you, ambushes keep
 * coming, and everything you kill drops double loot and XP. Survive the
 * timer for a payout - or punch a friend to make it their problem (no
 * tag-backs for a few seconds). The timer freezes in the Hearth, so you can't
 * just wait it out at home... but you can come home and smack someone who's
 * busy building. If you die hexed, the curse jumps to the nearest player.
 */
public final class HexManager {
    public static final HexManager INSTANCE = new HexManager();
    public static final String TAG = "df_hex";

    private final Map<UUID, Long> immuneUntil = new HashMap<>();
    private final Map<UUID, Long> nextAmbush = new HashMap<>();

    private HexManager() {
    }

    public static int remaining(ServerPlayer player) {
        return player.getPersistentData().getInt(TAG);
    }

    private static void store(ServerPlayer player, int ticks) {
        if (ticks > 0) {
            player.getPersistentData().putInt(TAG, ticks);
        } else {
            player.getPersistentData().remove(TAG);
        }
    }

    // ------------------------------------------------------------------ gaining

    /** Hexes a player (never shortening an existing Hex). Public for commands and tests. */
    public void give(ServerPlayer player, int ticks, String reason) {
        MechanicsConfig.Hex cfg = Configs.mechanics().hex();
        int before = remaining(player);
        store(player, HexRules.onGain(before, ticks));
        nextAmbush.put(player.getUUID(), player.serverLevel().getGameTime() + cfg.firstAmbushTicks());
        if (before > 0) {
            return;
        }
        Component hint = Component.literal("Everything is coming for you. Survive it, or hit a friend to pass it on.")
                .withStyle(ChatFormatting.GRAY);
        showHexTitle(player, Component.literal("HEXED"), hint);
        Fx.soundTo(player, Sfx.HEX_CURSE);
        broadcast(player.serverLevel().getServer(), "☠ " + player.getName().getString() + " is HEXED" + reason
                + ". Stay close... or don't.");
    }

    public void clear(ServerPlayer player) {
        store(player, 0);
        player.removeEffect(MobEffects.GLOWING);
        nextAmbush.remove(player.getUUID());
    }

    /** Killing an elite: champions always curse their killer, elites sometimes. */
    @SubscribeEvent
    public void onEliteDeath(LivingDeathEvent event) {
        MechanicsConfig.Hex cfg = Configs.mechanics().hex();
        if (!cfg.enabled() || !(event.getEntity() instanceof Mob mob) || !Elites.isElite(mob)) {
            return;
        }
        ServerPlayer killer = EliteRewards.killer(event.getSource());
        if (killer == null || killer.isCreative() || killer.isSpectator()) {
            return;
        }
        boolean champion = Elites.tier(mob) >= 2;
        if ((champion && cfg.championAlways()) || killer.getRandom().nextDouble() < cfg.eliteChance()) {
            give(killer, cfg.durationTicks(), " by the dying curse of " + Elites.displayName(mob));
        }
    }

    // ------------------------------------------------------------------ passing

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer attacker) || !(event.getTarget() instanceof ServerPlayer target)) {
            return;
        }
        if (!Configs.mechanics().hex().enabled()) {
            return;
        }
        long now = attacker.serverLevel().getGameTime();
        if (!HexRules.canPass(remaining(attacker), remaining(target), attacker == target, target.isSpectator(),
                now, immuneUntil.getOrDefault(target.getUUID(), 0L))) {
            return;
        }
        pass(attacker, target);
    }

    private void pass(ServerPlayer from, ServerPlayer to) {
        MechanicsConfig.Hex cfg = Configs.mechanics().hex();
        int ticks = remaining(from);
        clear(from);
        immuneUntil.put(from.getUUID(), from.serverLevel().getGameTime() + cfg.passBackImmunityTicks());
        store(to, ticks);
        nextAmbush.put(to.getUUID(), to.serverLevel().getGameTime() + cfg.firstAmbushTicks());

        showHexTitle(to, Component.literal("TAG - YOU'RE HEXED"), Component.literal(
                from.getName().getString() + " passed it to you. " + HexRules.formatTicks(ticks) + " left.")
                .withStyle(ChatFormatting.GRAY));
        Fx.sound(to.serverLevel(), to.position(), Sfx.HEX_PASS);
        broadcast(from.serverLevel().getServer(), "☠ " + from.getName().getString() + " passed the Hex to "
                + to.getName().getString() + "!");
    }

    /** Die hexed and it jumps to whoever is nearest (downed handling happens first; real deaths only). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer dead) || remaining(dead) <= 0) {
            return;
        }
        MechanicsConfig.Hex cfg = Configs.mechanics().hex();
        int ticks = remaining(dead);
        clear(dead);
        List<HexRules.Candidate<ServerPlayer>> candidates = new ArrayList<>();
        for (ServerPlayer p : dead.serverLevel().players()) {
            if (p != dead && p.isAlive() && !p.isSpectator() && !p.isCreative()) {
                candidates.add(new HexRules.Candidate<>(p, p.distanceTo(dead)));
            }
        }
        ServerPlayer next = HexRules.nearest(candidates, cfg.jumpRange());
        MinecraftServer server = dead.serverLevel().getServer();
        if (next == null) {
            broadcast(server, "☠ The Hex dies with " + dead.getName().getString() + ".");
            return;
        }
        give(next, HexRules.jumpDuration(ticks, cfg.minJumpTicks()), "");
        broadcast(server, "☠ The Hex leaves " + dead.getName().getString() + "'s body and finds "
                + next.getName().getString() + "!");
    }

    // ------------------------------------------------------------------ while hexed

    /** Called once per second by the server ticker. */
    public void tick(MinecraftServer server) {
        MechanicsConfig.Hex cfg = Configs.mechanics().hex();
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
            int after = HexRules.tick(before, safe, 20);
            store(player, after);

            player.addEffect(new MobEffectInstance(MobEffects.GLOWING, 50, 0, false, false));
            player.serverLevel().sendParticles(ParticleTypes.WITCH, player.getX(), player.getY() + 1, player.getZ(),
                    5, 0.4, 0.6, 0.4, 0.02);

            if (!safe && player.isAlive() && !player.isSpectator()) {
                lure(player, cfg);
                maybeAmbush(player, ring, cfg);
            }
            if (!DownedManager.isDowned(player)) {
                player.displayClientMessage(Fx.icon(player, Glyphs.HEX).append(Component.literal(safe
                                ? "☠ HEXED (paused in " + ring.name() + ")  ·  hit a friend to pass it"
                                : "☠ HEXED " + HexRules.formatTicks(after) + "  ·  hit a friend to pass it")
                        .withStyle(ChatFormatting.DARK_PURPLE)), true);
            }
            if (after == 0) {
                survived(player, ring);
            }
        }
    }

    /** Every hostile in range drops what it's doing and comes for the hexed player. */
    private static void lure(ServerPlayer player, MechanicsConfig.Hex cfg) {
        for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class,
                player.getBoundingBox().inflate(cfg.lureRadius()),
                m -> m instanceof Enemy && m.isAlive() && m.getTarget() != player)) {
            mob.setTarget(player);
        }
    }

    private void maybeAmbush(ServerPlayer player, Ring ring, MechanicsConfig.Hex cfg) {
        if (ring == null || ring.danger() <= 0 || cfg.ambushMobs().isEmpty()) {
            return;
        }
        long now = player.serverLevel().getGameTime();
        long due = nextAmbush.computeIfAbsent(player.getUUID(), k -> now + cfg.firstAmbushTicks());
        if (now < due) {
            return;
        }
        nextAmbush.put(player.getUUID(), now + cfg.ambushEveryTicks());
        ServerLevel level = player.serverLevel();
        int count = HexRules.ambushSize(cfg.ambushBaseSize(), ring.danger());
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 10, 16);
            if (pos == null) {
                continue;
            }
            String id = cfg.ambushMobs().get(level.random.nextInt(cfg.ambushMobs().size()));
            Mob mob = SpawnUtil.spawnForRing(level, ring, id, pos);
            if (mob != null) {
                Elites.makeDigger(mob);
                mob.setTarget(player);
                spawned++;
            }
        }
        if (spawned > 0) {
            Fx.sound(level, player.position(), Sfx.HEX_AMBUSH);
            player.displayClientMessage(Component.literal("The Hex calls them to you...")
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC), true);
        }
    }

    private void survived(ServerPlayer player, Ring ring) {
        MechanicsConfig.Hex cfg = Configs.mechanics().hex();
        clear(player);
        ExperienceOrb.award(player.serverLevel(), player.position(), cfg.survivalXp());
        if (ring != null) {
            for (ItemStack stack : EliteRewards.rollLoot(player.serverLevel(), ring.eliteLoot(), player.position(), 1)) {
                player.serverLevel().addFreshEntity(new ItemEntity(player.serverLevel(),
                        player.getX(), player.getY() + 0.5, player.getZ(), stack));
            }
        }
        Fx.soundTo(player, Sfx.HEX_SURVIVE);
        for (ServerPlayer viewer : player.serverLevel().getServer().getPlayerList().getPlayers()) {
            viewer.sendSystemMessage(Fx.icon(viewer, Glyphs.CHAMPION).append(Component.literal(
                    "★ " + player.getName().getString() + " outlasted the Hex!").withStyle(ChatFormatting.GOLD)));
        }
    }

    // ------------------------------------------------------------------ greed

    /** Everything a hexed player kills drops double. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onDrops(LivingDropsEvent event) {
        if (!Configs.mechanics().hex().doubleDrops() || event.getEntity() instanceof ServerPlayer) {
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
        if (Configs.mechanics().hex().doubleDrops() && event.getAttackingPlayer() instanceof ServerPlayer killer
                && remaining(killer) > 0 && !(event.getEntity() instanceof ServerPlayer)) {
            event.setDroppedExperience(event.getDroppedExperience() * 2);
        }
    }

    /** Hex flipbook for pack users, a bold purple title for everyone else. */
    private static void showHexTitle(ServerPlayer player, Component title, Component hint) {
        MutableComponent styled = title.copy().withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD);
        if (!Fx.flipbook(player, Glyphs.HEX_ANIM, Glyphs.HEX_FRAMES, styled.copy().append("  ").append(hint), 50)) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
            player.connection.send(new ClientboundSetSubtitleTextPacket(hint));
            player.connection.send(new ClientboundSetTitleTextPacket(styled));
        }
    }

    /** Server-wide Hex news, with the Hex icon for players who have the pack. */
    private static void broadcast(MinecraftServer server, String text) {
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            viewer.sendSystemMessage(Fx.icon(viewer, Glyphs.HEX)
                    .append(Component.literal(text).withStyle(ChatFormatting.DARK_PURPLE)));
        }
    }

    public void clearAll() {
        immuneUntil.clear();
        nextAmbush.clear();
    }
}
