package dev.anthonyw.furtherout.player;

import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.DarkSoundRules;
import dev.anthonyw.furtherout.core.MechanicsConfig;
import dev.anthonyw.furtherout.ring.Ring;
import dev.anthonyw.furtherout.ring.RingManager;
import dev.anthonyw.furtherout.util.McRand;
import dev.anthonyw.furtherout.util.Scheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Alone in the dark, every so often, you hear something behind you that
 * nobody else hears: footsteps, someone mining, a door, a chest closing, a
 * groan, something fizzling out - and, very rarely, a hiss. Nothing ever
 * comes of it. Only for a player with no one nearby, in real darkness, away
 * from spawn, many minutes apart. All vanilla sounds, sent to that player only.
 */
public final class DarkSounds {
    public static final DarkSounds INSTANCE = new DarkSounds();

    /** In the order of {@link DarkSoundRules#WEIGHTS}. */
    private enum Kind { FOOTSTEPS, MINING, DOOR, CHEST, GROAN, FIZZLE, HISS }

    private final Map<UUID, Long> next = new HashMap<>();

    private DarkSounds() {
    }

    /** Called once per second by the server ticker. */
    public void tick(MinecraftServer server) {
        MechanicsConfig.DarkSounds cfg = Configs.mechanics().darkSounds();
        if (!cfg.enabled()) {
            return;
        }
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            McRand rand = new McRand(player.getRandom());
            long due = next.computeIfAbsent(player.getUUID(), id -> now + DarkSoundRules.nextDelay(rand, cfg));
            if (now < due || !eligible(player, cfg)) {
                continue;
            }
            next.put(player.getUUID(), now + DarkSoundRules.nextDelay(rand, cfg));
            play(player, Kind.values()[DarkSoundRules.pick(rand)]);
        }
    }

    private static boolean eligible(ServerPlayer player, MechanicsConfig.DarkSounds cfg) {
        ServerLevel level = player.serverLevel();
        Ring ring = RingManager.ringOf(player);
        if (ring == null || !player.isAlive()) {
            return false;
        }
        boolean alone = true;
        double range = cfg.aloneRange() * cfg.aloneRange();
        for (ServerPlayer other : level.players()) {
            if (other != player && !other.isSpectator() && other.distanceToSqr(player) <= range) {
                alone = false;
                break;
            }
        }
        BlockPos eyes = BlockPos.containing(player.getEyePosition());
        int brightness = level.getRawBrightness(eyes, level.getSkyDarken());
        return DarkSoundRules.eligible(alone, brightness, ring.safeZone(),
                !player.isCreative() && !player.isSpectator(), cfg);
    }

    private static void play(ServerPlayer player, Kind kind) {
        ServerLevel level = player.serverLevel();
        double side = player.getRandom().nextDouble() * 3 - 1.5;
        double distance = 6 + player.getRandom().nextDouble() * 3;
        Vec3 spot = behind(player, distance, side);
        SoundType ground = level.getBlockState(BlockPos.containing(spot).below()).getSoundType();
        switch (kind) {
            case FOOTSTEPS -> {
                for (int i = 0; i < 4; i++) {
                    Vec3 step = behind(player, distance - i * 0.8, side);
                    later(player, i * 7, ground.getStepSound(), SoundSource.PLAYERS, step, 0.35f, 1.0f);
                }
            }
            case MINING -> {
                for (int i = 0; i < 3; i++) {
                    later(player, i * 6, ground.getHitSound(), SoundSource.BLOCKS, spot, 0.4f, 0.6f);
                }
                later(player, 20, ground.getBreakSound(), SoundSource.BLOCKS, spot, 0.6f, 0.8f);
            }
            case DOOR -> {
                later(player, 0, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, spot, 0.5f, 0.9f);
                later(player, 25, SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, spot, 0.5f, 0.9f);
            }
            case CHEST -> later(player, 0, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, spot, 0.4f, 0.9f);
            case GROAN -> later(player, 0, SoundEvents.ZOMBIE_AMBIENT, SoundSource.HOSTILE,
                    behind(player, distance + 6, side), 0.5f, 0.8f);
            case FIZZLE -> later(player, 0, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, spot, 0.3f, 1.2f);
            case HISS -> later(player, 0, SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, spot, 0.6f, 0.9f);
        }
    }

    private static Vec3 behind(ServerPlayer player, double distance, double side) {
        double[] d = DarkSoundRules.behind(player.getYRot(), distance, side);
        return new Vec3(player.getX() + d[0], player.getEyeY() - 0.6, player.getZ() + d[1]);
    }

    /** One sound for one player's ears only, after {@code delay} ticks. */
    private static void later(ServerPlayer player, int delay, SoundEvent sound, SoundSource source, Vec3 at,
                              float volume, float pitch) {
        Runnable send = () -> {
            if (!player.hasDisconnected()) {
                player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                        source, at.x, at.y, at.z, volume, pitch, player.getRandom().nextLong()));
            }
        };
        if (delay <= 0) {
            send.run();
        } else {
            Scheduler.schedule(player.serverLevel().getServer(), delay, send);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        next.remove(event.getEntity().getUUID());
    }

    public void clearAll() {
        next.clear();
    }
}
