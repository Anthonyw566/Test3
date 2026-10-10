package dev.anthonyw.furtherout.player;

import dev.anthonyw.furtherout.fx.Fx;
import dev.anthonyw.furtherout.fx.Sfx;
import dev.anthonyw.furtherout.util.SpawnUtil;
import dev.anthonyw.furtherout.util.Tips;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A rare warp far from spawn: a few seconds in the Nether, then you're pulled
 * back to exactly where you were. A short, survivable detour - never a long
 * walk home. Dying, or leaving the Nether on your own, ends it.
 */
public final class Rifts {
    public static final Rifts INSTANCE = new Rifts();
    private static final String TAG = "fo_rift";

    private final Map<UUID, ServerBossEvent> bars = new HashMap<>();

    private Rifts() {
    }

    public static boolean inRift(ServerPlayer player) {
        return player.getPersistentData().contains(TAG);
    }

    /** Pulls a player into the Nether above their 1:8 position. False if there is no safe spot. */
    public static boolean send(ServerPlayer player, int ticks) {
        MinecraftServer server = player.getServer();
        ServerLevel nether = server == null ? null : server.getLevel(Level.NETHER);
        if (nether == null || player.level().dimension() != Level.OVERWORLD || inRift(player)) {
            return false;
        }
        BlockPos landing = findLanding(nether, (int) Math.floor(player.getX() / 8), (int) Math.floor(player.getZ() / 8));
        if (landing == null) {
            return false;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", player.level().dimension().location().toString());
        tag.putDouble("x", player.getX());
        tag.putDouble("y", player.getY());
        tag.putDouble("z", player.getZ());
        tag.putFloat("yaw", player.getYRot());
        tag.putFloat("pitch", player.getXRot());
        tag.putLong("until", server.overworld().getGameTime() + ticks);
        tag.putInt("total", ticks);
        player.getPersistentData().put(TAG, tag);

        player.teleportTo(nether, landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        player.resetFallDistance();
        Fx.soundTo(player, Sfx.WARP);
        Tips.once(player, "rift", "Far from spawn, a warp can pull you into the Nether for a few seconds."
                + " Hold on: you'll be pulled back to where you were.");
        return true;
    }

    @Nullable
    private static BlockPos findLanding(ServerLevel nether, int x, int z) {
        int[][] offsets = {{0, 0}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}, {5, 5}, {-5, -5}};
        for (int[] o : offsets) {
            for (int y = 100; y >= 32; y--) {
                BlockPos pos = new BlockPos(x + o[0], y, z + o[1]);
                if (SpawnUtil.isStandable(nether, pos) && nether.getFluidState(pos.below()).isEmpty()) {
                    return pos;
                }
            }
        }
        return null;
    }

    /** Called once per second by the server ticker. */
    public void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CompoundTag tag = player.getPersistentData().getCompound(TAG);
            if (tag.isEmpty()) {
                hideBar(player);
                continue;
            }
            if (player.level().dimension() != Level.NETHER || !player.isAlive()) {
                end(player); // they found their own way out
                continue;
            }
            long until = tag.getLong("until");
            if (now >= until) {
                pullBack(player, tag);
                continue;
            }
            ServerBossEvent bar = bars.computeIfAbsent(player.getUUID(), id -> new ServerBossEvent(
                    Component.literal("Returning"), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS));
            bar.addPlayer(player);
            bar.setProgress(Math.max(0f, Math.min(1f, (until - now) / (float) Math.max(1, tag.getInt("total")))));
        }
    }

    private void pullBack(ServerPlayer player, CompoundTag tag) {
        MinecraftServer server = player.getServer();
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dim"));
        ServerLevel home = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        end(player);
        if (home == null) {
            home = server.overworld();
        }
        player.serverLevel().sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1,
                player.getZ(), 30, 0.4, 0.8, 0.4, 0.1);
        player.teleportTo(home, tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"),
                tag.getFloat("yaw"), tag.getFloat("pitch"));
        player.resetFallDistance();
        Fx.sound(home, player.position(), Sfx.WARP);
        home.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(),
                30, 0.4, 0.8, 0.4, 0.1);
    }

    private void end(ServerPlayer player) {
        player.getPersistentData().remove(TAG);
        hideBar(player);
    }

    private void hideBar(ServerPlayer player) {
        ServerBossEvent bar = bars.remove(player.getUUID());
        if (bar != null) {
            bar.removeAllPlayers();
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !event.isCanceled()) {
            end(player);
        }
    }

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        event.getEntity().getPersistentData().remove(TAG);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerBossEvent bar = bars.remove(event.getEntity().getUUID());
        if (bar != null) {
            bar.removeAllPlayers();
        }
    }

    public void clearAll() {
        bars.values().forEach(ServerBossEvent::removeAllPlayers);
        bars.clear();
    }
}
