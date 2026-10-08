package dev.anthonyw.frontiers.fx;

import dev.anthonyw.frontiers.util.Scheduler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Presentation helpers that pick, per player, between the resource-pack
 * version of an effect and its vanilla fallback.
 */
public final class Fx {
    private Fx() {
    }

    /** A sound in the world, heard by everyone nearby (custom or vanilla per listener). */
    public static void sound(ServerLevel level, Vec3 pos, Sfx sfx) {
        double range = 16.0 * Math.max(1.0f, sfx.fallbackVolume());
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) <= range * range) {
                send(player, sfx, pos.x, pos.y, pos.z);
            }
        }
    }

    /** A sound only this player hears, played at their position. */
    public static void soundTo(ServerPlayer player, Sfx sfx) {
        send(player, sfx, player.getX(), player.getEyeY(), player.getZ());
    }

    private static void send(ServerPlayer player, Sfx sfx, double x, double y, double z) {
        boolean pack = ResourcePacks.hasPack(player);
        player.connection.send(new ClientboundSoundPacket(
                pack ? sfx.custom() : sfx.fallback(), sfx.source(), x, y, z,
                pack ? 1.0f : sfx.fallbackVolume(), pack ? 1.0f : sfx.fallbackPitch(),
                player.getRandom().nextLong()));
    }

    /** "[icon] " for players with the pack, nothing for everyone else. */
    public static MutableComponent icon(ServerPlayer viewer, int codePoint) {
        if (!ResourcePacks.hasPack(viewer)) {
            return Component.empty();
        }
        return Glyphs.icon(codePoint).append(Component.literal(" "));
    }

    /**
     * Plays a title flipbook (pack only): frames {@code first..first+count-1}
     * of the big font, two ticks apart, holding the last frame.
     * Returns false (and does nothing) for players without the pack, so the
     * caller can show its plain-text title instead.
     */
    public static boolean flipbook(ServerPlayer player, int first, int count, Component subtitle, int holdTicks) {
        if (!ResourcePacks.hasPack(player) || first < 0) {
            return false;
        }
        player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 3, 0));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        for (int i = 0; i < count; i++) {
            int frame = first + i;
            boolean last = i == count - 1;
            Scheduler.schedule(player.serverLevel().getServer(), 1 + i * 2, () -> {
                if (player.isRemoved()) {
                    return;
                }
                if (last) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(0, holdTicks, 15));
                }
                player.connection.send(new ClientboundSetTitleTextPacket(Glyphs.big(frame)));
            });
        }
        return true;
    }
}
