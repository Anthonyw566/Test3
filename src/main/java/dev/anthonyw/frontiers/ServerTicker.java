package dev.anthonyw.frontiers;

import dev.anthonyw.frontiers.player.DownedManager;
import dev.anthonyw.frontiers.player.HexManager;
import dev.anthonyw.frontiers.ring.BoundaryWatcher;
import dev.anthonyw.frontiers.util.Scheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * One heartbeat for all player-facing systems, driven from the server tick
 * (not per-player ticks) so every player in the player list is handled the
 * same way - including the mock players the in-game tests use.
 */
public final class ServerTicker {
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        Scheduler.tick(server);
        if (tick % 5 == 0) {
            DownedManager.INSTANCE.tick(server);
        }
        if (tick % 20 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                BoundaryWatcher.tick(player);
            }
            HexManager.INSTANCE.tick(server);
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        BoundaryWatcher.forget(event.getEntity().getUUID());
    }
}
