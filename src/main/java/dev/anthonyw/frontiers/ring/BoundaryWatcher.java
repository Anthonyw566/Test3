package dev.anthonyw.frontiers.ring;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects ring crossings (checked once per second per player) and plays the
 * entry fanfare: colored title, flavor subtitle, action-bar danger readout and
 * a directional sound - a low toll heading into danger, a bright chime heading
 * home. All of it is plain vanilla packets, so unmodded clients see everything.
 */
public final class BoundaryWatcher {
    private final Map<UUID, String> lastRing = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % 20 != 0) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        RingManager mgr = RingManager.get();
        if (mgr == null) {
            return;
        }

        Ring ring = mgr.ringAt(level, player.getX(), player.getZ());
        if (ring == null) {
            lastRing.remove(player.getUUID());
            return;
        }

        String previous = lastRing.put(player.getUUID(), ring.id());
        if (previous == null || previous.equals(ring.id())) {
            return;
        }

        Ring previousRing = mgr.byId(previous);
        boolean deeper = previousRing == null || ring.danger() > previousRing.danger();
        announce(player, ring, deeper);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        lastRing.remove(event.getEntity().getUUID());
    }

    private static void announce(ServerPlayer player, Ring ring, boolean deeper) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        if (!ring.entryMessage().isBlank()) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal(ring.entryMessage())
                            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
        }
        player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal(ring.name()).withStyle(ring.color())));
        player.displayClientMessage(dangerReadout(ring), true);
        if (deeper) {
            player.playNotifySound(SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.MASTER, 0.35f, 1.6f);
        } else {
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.6f, 1.4f);
        }
    }

    /** e.g. "The Duskreach ☠☠☠" in the ring's color. */
    public static Component dangerReadout(Ring ring) {
        String skulls = ring.danger() > 0 ? "  " + "☠".repeat(ring.danger()) : "";
        return Component.literal(ring.name() + skulls).withStyle(ring.color());
    }
}
