package dev.anthonyw.frontiers.ring;

import dev.anthonyw.frontiers.fx.Fx;
import dev.anthonyw.frontiers.fx.Glyphs;
import dev.anthonyw.frontiers.fx.Sfx;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Announces ring crossings with a title, a one-line flavor subtitle and a
 * sound: a low toll heading into danger, a bright chime heading home. Titles
 * are rate-limited so pacing back and forth on a boundary doesn't spam.
 * Called once per second per player from the server ticker.
 */
public final class BoundaryWatcher {
    private static final int TITLE_COOLDOWN_TICKS = 200;

    private static final Map<UUID, String> lastRing = new HashMap<>();
    private static final Map<UUID, Long> lastTitle = new HashMap<>();

    private BoundaryWatcher() {
    }

    public static void tick(ServerPlayer player) {
        Ring ring = RingManager.ringOf(player);
        UUID id = player.getUUID();
        if (ring == null) {
            lastRing.remove(id);
            return;
        }
        String previous = lastRing.put(id, ring.id());
        if (previous == null || previous.equals(ring.id())) {
            return;
        }
        RingManager mgr = RingManager.get();
        Ring before = mgr == null ? null : mgr.byId(previous);
        boolean deeper = before == null || ring.danger() > before.danger();

        long now = player.serverLevel().getGameTime();
        if (now - lastTitle.getOrDefault(id, Long.MIN_VALUE / 2) >= TITLE_COOLDOWN_TICKS) {
            lastTitle.put(id, now);
            // With the resource pack: the ring's emblem punches in, name + flavor below it.
            MutableComponent subtitle = Component.literal(ring.name()).withStyle(ring.color());
            if (!ring.entryMessage().isBlank()) {
                subtitle.append(Component.literal("  " + ring.entryMessage()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
            }
            if (!Fx.flipbook(player, Glyphs.ringReveal(ring.id()), Glyphs.RING_REVEAL_FRAMES, subtitle, 50)) {
                player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
                if (!ring.entryMessage().isBlank()) {
                    player.connection.send(new ClientboundSetSubtitleTextPacket(
                            Component.literal(ring.entryMessage()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));
                }
                player.connection.send(new ClientboundSetTitleTextPacket(
                        Component.literal(ring.name()).withStyle(ring.color())));
            }
            Fx.soundTo(player, deeper ? Sfx.RING_DEEPER : Sfx.RING_HOME);
        }
        player.displayClientMessage(ring.title(), true);
    }

    public static void forget(UUID player) {
        lastRing.remove(player);
        lastTitle.remove(player);
    }

    public static void clear() {
        lastRing.clear();
        lastTitle.clear();
    }
}
