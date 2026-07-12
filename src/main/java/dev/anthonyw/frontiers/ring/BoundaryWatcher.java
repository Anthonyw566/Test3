package dev.anthonyw.frontiers.ring;

import dev.anthonyw.frontiers.contract.ContractBoard;
import dev.anthonyw.frontiers.heat.HeatManager;
import dev.anthonyw.frontiers.state.FrontiersState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects ring crossings (checked once per second per player) and plays the
 * entry fanfare: colored title, flavor subtitle and a directional sound - a
 * low toll heading into danger, a bright chime heading home. Also handles
 * first-discovery announcements, uncharted-ring warnings, and tells the Heat
 * system about crossings (grace periods, cash-out). All vanilla packets, so
 * unmodded clients see everything.
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
        HeatManager.INSTANCE.onRingChange(player);
        onCrossedInto(player, ring, mgr);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        lastRing.remove(event.getEntity().getUUID());
    }

    /** First discoveries and uncharted-territory warnings. */
    private static void onCrossedInto(ServerPlayer player, Ring ring, RingManager mgr) {
        if (ring.danger() <= 0) {
            return;
        }
        MinecraftServer server = player.serverLevel().getServer();
        FrontiersState state = FrontiersState.get(server);

        if (state.discover(ring.id())) {
            int bonus = 10 * ring.danger();
            state.addBanked(player.getUUID(), bonus);
            server.getPlayerList().broadcastSystemMessage(Component.literal("★ ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(player.getName().getString()
                            + " is the first to reach ").withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(ring.name()).withStyle(ring.color()))
                    .append(Component.literal("! (◈ " + bonus + " banked)")
                            .withStyle(ChatFormatting.GOLD)), false);
        }

        // Warn when entering a ring beyond the charter frontier (soft progression:
        // scary, allowed, and clearly communicated).
        List<Ring> dangerRings = mgr.rings().stream().filter(r -> r.danger() > 0).toList();
        int index = -1;
        for (int i = 0; i < dangerRings.size(); i++) {
            if (dangerRings.get(i).id().equals(ring.id())) {
                index = i;
                break;
            }
        }
        if (index > state.chartered().size()
                && !ring.id().equals(ContractBoard.activeCharterRing(state, mgr))) {
            player.sendSystemMessage(Component.literal(
                            "This land is uncharted — the Board offers no support here yet.")
                    .withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
        }
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
