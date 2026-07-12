package dev.anthonyw.frontiers.event;

import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.state.FrontiersState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Surge nights: each dusk there is a 35% chance one ring "stirs" until dawn -
 * doubled elite chance and 1.5x kill Marks inside it. Cheap to run, and gives
 * the group a spontaneous "drop everything, it's a Duskreach night" moment.
 * Surge state persists across restarts via FrontiersState.
 */
public final class SurgeManager {
    public static final SurgeManager INSTANCE = new SurgeManager();

    private static final double NIGHTLY_CHANCE = 0.35;
    private static final int DUSK = 13000;

    // Mirrored from FrontiersState every tick so the hot spawn path can read
    // it without a server reference.
    private static volatile String activeRing = "";

    private long lastRolledDay = -1;

    private SurgeManager() {
    }

    public static double eliteMultiplier(String ringId) {
        return ringId.equals(activeRing) ? 2.0 : 1.0;
    }

    public static double marksMultiplier(String ringId) {
        return ringId.equals(activeRing) ? 1.5 : 1.0;
    }

    public static boolean isSurging(String ringId) {
        return ringId.equals(activeRing);
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (overworld.getGameTime() % 20 != 0) {
            return;
        }
        FrontiersState state = FrontiersState.get(server);
        long dayTime = overworld.getDayTime();
        long phase = dayTime % 24000;
        long day = dayTime / 24000;

        // End an active surge at dawn.
        if (!state.surgeRing().isEmpty() && dayTime >= state.surgeUntil()) {
            state.setSurge("", 0);
            broadcast(server, Component.literal("The surge has passed.")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), false);
        }

        // Roll once per dusk.
        if (phase >= DUSK && phase < DUSK + 200 && day != lastRolledDay) {
            lastRolledDay = day;
            if (state.surgeRing().isEmpty() && overworld.random.nextDouble() < NIGHTLY_CHANCE) {
                RingManager mgr = RingManager.get();
                if (mgr != null) {
                    List<Ring> candidates = new ArrayList<>();
                    for (Ring ring : mgr.rings()) {
                        if (ring.danger() > 0) {
                            candidates.add(ring);
                        }
                    }
                    if (!candidates.isEmpty()) {
                        Ring ring = candidates.get(overworld.random.nextInt(candidates.size()));
                        start(server, ring, dayTime - phase + 24000);
                    }
                }
            }
        }

        activeRing = state.surgeRing();
    }

    /** Also used by /rings surge for testing. */
    public static void start(MinecraftServer server, Ring ring, long untilDayTime) {
        FrontiersState.get(server).setSurge(ring.id(), untilDayTime);
        activeRing = ring.id();
        broadcast(server, Component.literal("⚡ ")
                .withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(ring.name()).withStyle(ring.color()))
                .append(Component.literal(" stirs tonight — elites hunt in force and Marks flow richer.")
                        .withStyle(ChatFormatting.LIGHT_PURPLE)), true);
    }

    public static void stop(MinecraftServer server) {
        FrontiersState.get(server).setSurge("", 0);
        activeRing = "";
    }

    private static void broadcast(MinecraftServer server, Component message, boolean sound) {
        server.getPlayerList().broadcastSystemMessage(message, false);
        if (sound) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.playNotifySound(SoundEvents.ENDER_DRAGON_GROWL, SoundSource.MASTER, 0.4f, 0.8f);
            }
        }
    }
}
