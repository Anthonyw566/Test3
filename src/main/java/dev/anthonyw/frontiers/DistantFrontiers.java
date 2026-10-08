package dev.anthonyw.frontiers;

import com.mojang.logging.LogUtils;
import dev.anthonyw.frontiers.command.RingsCommand;
import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.fx.ResourcePacks;
import dev.anthonyw.frontiers.mob.EliteAbilities;
import dev.anthonyw.frontiers.mob.EliteRewards;
import dev.anthonyw.frontiers.mob.MobSpawns;
import dev.anthonyw.frontiers.player.DownedManager;
import dev.anthonyw.frontiers.player.HexManager;
import dev.anthonyw.frontiers.ring.BoundaryWatcher;
import dev.anthonyw.frontiers.util.Scheduler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

/**
 * Distant Frontiers: the further from spawn, the harder the world - and the
 * more your friends matter. Distance rings, five elite abilities, mobs that
 * dig to you, Downed & Revive, and the Hex.
 *
 * Server-side only: registers no items, blocks or network channels, so
 * players join with an unmodified ATM10 client.
 */
@Mod(DistantFrontiers.MODID)
public final class DistantFrontiers {
    public static final String MODID = "distantfrontiers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DistantFrontiers(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.register(new ServerTicker());
        NeoForge.EVENT_BUS.register(new MobSpawns());
        NeoForge.EVENT_BUS.register(new EliteAbilities());
        NeoForge.EVENT_BUS.register(new EliteRewards());
        NeoForge.EVENT_BUS.register(DownedManager.INSTANCE);
        NeoForge.EVENT_BUS.register(HexManager.INSTANCE);
        NeoForge.EVENT_BUS.register(ResourcePacks.INSTANCE);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        Configs.loadAll();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        Scheduler.clear();
        DownedManager.INSTANCE.clearAll();
        HexManager.INSTANCE.clearAll();
        BoundaryWatcher.clear();
        ResourcePacks.clear();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        RingsCommand.register(event.getDispatcher());
    }
}
