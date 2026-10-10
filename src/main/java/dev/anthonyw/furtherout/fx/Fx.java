package dev.anthonyw.furtherout.fx;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Plays a {@link Sfx}, picking the pack version or the vanilla one per listener. */
public final class Fx {
    private Fx() {
    }

    /** A sound in the world, heard by everyone nearby. */
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

    /** A sound only this player hears, coming from somewhere else. */
    public static void soundTo(ServerPlayer player, Sfx sfx, Vec3 from) {
        send(player, sfx, from.x, from.y, from.z);
    }

    private static void send(ServerPlayer player, Sfx sfx, double x, double y, double z) {
        boolean pack = sfx.custom() != null && ResourcePacks.hasPack(player);
        player.connection.send(new ClientboundSoundPacket(
                pack ? sfx.custom() : sfx.fallback(), sfx.source(), x, y, z,
                pack ? 1.0f : sfx.fallbackVolume(), pack ? 1.0f : sfx.fallbackPitch(),
                player.getRandom().nextLong()));
    }
}
