package dev.anthonyw.frontiers.heat;

import dev.anthonyw.frontiers.contract.ContractBoard;
import dev.anthonyw.frontiers.core.HeatMath;
import dev.anthonyw.frontiers.elite.EliteBehaviors;
import dev.anthonyw.frontiers.elite.Elites;
import dev.anthonyw.frontiers.event.SurgeManager;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.state.FrontiersState;
import dev.anthonyw.frontiers.util.SpawnUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Expedition Heat - the extraction loop.
 *
 * Heat accrues while a player is in rings that define a gain rate (Ring 2+ by
 * default) and jumps when elites die. High Heat means ambushes, then a named
 * Hunter. Marks earned in the field only bank when the player re-enters the
 * Hearth - with a bonus scaled by Heat at arrival (up to +50%) - so "one more
 * cache or go home?" is always a live decision. Dying banks half, no bonus.
 *
 * Every threshold change, ambush and hunter is announced with sound and text:
 * escalation is always legible and always the player's own doing.
 */
public final class HeatManager {
    public static final HeatManager INSTANCE = new HeatManager();

    public static final float RESTLESS = HeatMath.RESTLESS;
    public static final float HUNTED = HeatMath.HUNTED;
    public static final float MARKED = HeatMath.MARKED;

    private static final int GRACE_TICKS = 600;            // 30s after crossing a boundary
    private static final int AMBUSH_COOLDOWN = 9600;       // 8 min
    private static final int HUNTER_COOLDOWN = 18000;      // 15 min
    private static final int AMBUSH_WARNING_TICKS = 200;   // 10s of dread first

    private final Map<UUID, Long> graceUntil = new HashMap<>();
    private final Map<UUID, Long> lastAmbush = new HashMap<>();
    private final Map<UUID, Long> lastHunter = new HashMap<>();
    private final Map<UUID, Long> pendingAmbush = new HashMap<>();

    private HeatManager() {
    }

    /** Called by BoundaryWatcher on every ring crossing. */
    public void onRingChange(ServerPlayer player) {
        graceUntil.put(player.getUUID(), player.serverLevel().getGameTime() + GRACE_TICKS);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        graceUntil.remove(uuid);
        pendingAmbush.remove(uuid);
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % 20 != 10) { // offset from BoundaryWatcher's cadence
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || player.isSpectator()) {
            return;
        }
        RingManager mgr = RingManager.get();
        if (mgr == null) {
            return;
        }
        Ring ring = mgr.ringAt(level, player.getX(), player.getZ());
        if (ring == null) {
            return;
        }
        FrontiersState state = FrontiersState.get(player.serverLevel().getServer());
        UUID uuid = player.getUUID();

        if (ring.danger() <= 0) {
            cashOut(player, state);
            return;
        }

        float heat = state.heat(uuid);
        if (ring.heatGainPerMinute() > 0) {
            float before = heat;
            heat = HeatMath.clamp(heat + HeatMath.gainPerSecond(ring.heatGainPerMinute()));
            state.setHeat(uuid, heat);
            notifyThresholdCrossed(player, before, heat);
        }

        sendActionBar(player, ring, state, heat);

        long now = level.getGameTime();

        // Fire a scheduled ambush once its warning window has elapsed.
        Long due = pendingAmbush.get(uuid);
        if (due != null && now >= due) {
            pendingAmbush.remove(uuid);
            spawnAmbush(player, level, ring);
        }

        boolean inGrace = now < graceUntil.getOrDefault(uuid, 0L);
        if (!inGrace && due == null) {
            maybeScheduleAmbush(player, level, ring, state, now);
            maybeSendHunter(player, level, ring, state, now);
        }

        ContractBoard.tickPlayer(player, level, ring, state);
    }

    /** Heat bump on elite kills - killing the frontier's captains angers it. */
    public static void addKillHeat(ServerPlayer player, int tier) {
        FrontiersState state = FrontiersState.get(player.serverLevel().getServer());
        state.setHeat(player.getUUID(), state.heat(player.getUUID()) + HeatMath.killHeat(tier));
    }

    public static void relieveHeat(ServerPlayer player, float amount) {
        FrontiersState state = FrontiersState.get(player.serverLevel().getServer());
        state.setHeat(player.getUUID(), state.heat(player.getUUID()) - amount);
    }

    private void cashOut(ServerPlayer player, FrontiersState state) {
        UUID uuid = player.getUUID();
        int fieldMarks = state.fieldMarks(uuid);
        float heat = state.heat(uuid);
        if (fieldMarks <= 0 && heat <= 0) {
            return;
        }
        if (fieldMarks > 0) {
            double multiplier = HeatMath.bankMultiplier(heat);
            int banked = state.bankField(uuid, multiplier);
            int bonus = banked - fieldMarks;
            MutableComponent message = Component.literal("Expedition banked: ")
                    .withStyle(ChatFormatting.GREEN)
                    .append(Component.literal("◈ " + banked).withStyle(ChatFormatting.AQUA));
            if (bonus > 0) {
                message.append(Component.literal(" (+" + bonus + " heat bonus)")
                        .withStyle(ChatFormatting.GOLD));
            }
            player.sendSystemMessage(message);
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.7f, 1.0f);
        }
        state.setHeat(uuid, 0);
    }

    /** Death rule: half the field Marks make it home, the bonus never does. */
    public static void onPlayerDeath(ServerPlayer player) {
        FrontiersState state = FrontiersState.get(player.serverLevel().getServer());
        UUID uuid = player.getUUID();
        int fieldMarks = state.fieldMarks(uuid);
        if (fieldMarks > 0) {
            int banked = state.bankField(uuid, HeatMath.DEATH_BANK_FRACTION);
            player.sendSystemMessage(Component.literal(
                            "You fell. ◈ " + banked + " of your field Marks made it home; the rest are lost.")
                    .withStyle(ChatFormatting.RED));
        }
        state.setHeat(uuid, 0);
    }

    private void maybeScheduleAmbush(ServerPlayer player, ServerLevel level, Ring ring,
                                     FrontiersState state, long now) {
        if (state.heat(player.getUUID()) < HUNTED || ring.danger() < 2) {
            return;
        }
        if (now - lastAmbush.getOrDefault(player.getUUID(), Long.MIN_VALUE) < AMBUSH_COOLDOWN) {
            return;
        }
        if (level.random.nextDouble() >= 0.02) {
            return;
        }
        lastAmbush.put(player.getUUID(), now);
        pendingAmbush.put(player.getUUID(), now + AMBUSH_WARNING_TICKS);
        player.sendSystemMessage(Component.literal("You are being hunted…")
                .withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
        player.playNotifySound(SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.MASTER, 0.5f, 0.6f);
    }

    private void spawnAmbush(ServerPlayer player, ServerLevel level, Ring ring) {
        int count = Math.min(8, 3 + ring.danger());
        int spawned = 0;
        boolean eliteAssigned = false;
        for (int i = 0; i < count; i++) {
            BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 12, 20);
            if (pos == null) {
                continue;
            }
            Mob mob = SpawnUtil.spawnScaled(level, ring, ContractBoard.randomAmbushMob(level), pos);
            if (mob == null) {
                continue;
            }
            mob.getPersistentData().putBoolean(EliteBehaviors.TAG_NO_MARKS, true);
            mob.setTarget(player);
            if (!eliteAssigned) {
                Elites.promote(mob, ring, false, null);
                eliteAssigned = true;
            }
            spawned++;
        }
        if (spawned > 0) {
            level.playSound(null, player.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER,
                    SoundSource.HOSTILE, 0.4f, 1.4f);
        }
    }

    private void maybeSendHunter(ServerPlayer player, ServerLevel level, Ring ring,
                                 FrontiersState state, long now) {
        if (state.heat(player.getUUID()) < MARKED) {
            return;
        }
        if (now - lastHunter.getOrDefault(player.getUUID(), Long.MIN_VALUE) < HUNTER_COOLDOWN) {
            return;
        }
        if (level.random.nextDouble() >= 0.01) {
            return;
        }
        BlockPos pos = SpawnUtil.findGroundNear(level, player.blockPosition(), 24, 32);
        if (pos == null) {
            return;
        }
        Mob hunter = SpawnUtil.spawnScaled(level, ring, ContractBoard.randomAmbushMob(level), pos);
        if (hunter == null) {
            return;
        }
        lastHunter.put(player.getUUID(), now);
        Elites.promote(hunter, ring, true, null);
        if (EliteBehaviors.config().hunterAlwaysSieger()) {
            EliteBehaviors.forceSieger(hunter); // no wall hides you from a hunter
        }
        hunter.getPersistentData().putBoolean(ContractBoard.TAG_HUNTER, true);
        hunter.setPersistenceRequired();
        hunter.setTarget(player);
        String name = hunter.getCustomName() == null ? "Something" : hunter.getCustomName().getString();
        player.sendSystemMessage(Component.literal(name + " has caught your scent.")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.MASTER, 0.5f, 1.3f);
    }

    private static void notifyThresholdCrossed(ServerPlayer player, float before, float after) {
        String label = null;
        if (before < RESTLESS && after >= RESTLESS) {
            label = "The frontier has noticed you.";
        } else if (before < HUNTED && after >= HUNTED) {
            label = "You are being watched. Expect trouble.";
        } else if (before < MARKED && after >= MARKED) {
            label = "You are MARKED. Something is coming.";
        }
        if (label != null) {
            player.sendSystemMessage(Component.literal(label)
                    .withStyle(heatColor(after), ChatFormatting.ITALIC));
        }
    }

    private static void sendActionBar(ServerPlayer player, Ring ring, FrontiersState state, float heat) {
        MutableComponent bar = Component.literal(ring.name()
                        + (ring.danger() > 0 ? " " + "☠".repeat(ring.danger()) : ""))
                .withStyle(ring.color());
        bar.append(Component.literal("  Heat: ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(heatLabel(heat) + " (" + (int) heat + ")")
                        .withStyle(heatColor(heat)));
        int fieldMarks = state.fieldMarks(player.getUUID());
        if (fieldMarks > 0) {
            bar.append(Component.literal("  ◈ " + fieldMarks + " field")
                    .withStyle(ChatFormatting.AQUA));
        }
        if (SurgeManager.isSurging(ring.id())) {
            bar.append(Component.literal("  ⚡SURGE").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        player.displayClientMessage(bar, true);
    }

    public static String heatLabel(float heat) {
        return HeatMath.levelFor(heat).label();
    }

    public static ChatFormatting heatColor(float heat) {
        return switch (HeatMath.levelFor(heat)) {
            case MARKED -> ChatFormatting.RED;
            case HUNTED -> ChatFormatting.GOLD;
            case RESTLESS -> ChatFormatting.YELLOW;
            case CALM -> ChatFormatting.GREEN;
        };
    }
}
