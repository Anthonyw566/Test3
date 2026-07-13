package dev.anthonyw.frontiers;

import com.mojang.logging.LogUtils;
import dev.anthonyw.frontiers.command.RingsCommand;
import dev.anthonyw.frontiers.contract.ContractBoard;
import dev.anthonyw.frontiers.economy.KillRewards;
import dev.anthonyw.frontiers.economy.Shop;
import dev.anthonyw.frontiers.elite.EliteBehaviors;
import dev.anthonyw.frontiers.event.SurgeManager;
import dev.anthonyw.frontiers.heat.HeatManager;
import dev.anthonyw.frontiers.ring.BoundaryWatcher;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.scaling.SpawnScaling;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.slf4j.Logger;

/**
 * Distant Frontiers - distance-based difficulty rings, elites, expedition Heat
 * and a contract board for a private ATM10 server.
 *
 * Deliberately server-side only: no registered items, blocks, entities or
 * network channels, so vanilla-modlist clients can join freely and the jar
 * only needs to be dropped into the server's mods folder.
 */
@Mod(DistantFrontiers.MODID)
public final class DistantFrontiers {
    public static final String MODID = "distantfrontiers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DistantFrontiers(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.register(new BoundaryWatcher());
        NeoForge.EVENT_BUS.register(new SpawnScaling());
        NeoForge.EVENT_BUS.register(new EliteBehaviors());
        NeoForge.EVENT_BUS.register(new KillRewards());
        NeoForge.EVENT_BUS.register(HeatManager.INSTANCE);
        NeoForge.EVENT_BUS.register(SurgeManager.INSTANCE);
        NeoForge.EVENT_BUS.register(ContractBoard.INSTANCE);
        LOGGER.info("Distant Frontiers loaded. The frontier awaits.");
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        RingManager.load();
        ContractBoard.loadConfig();
        Shop.load();
        EliteBehaviors.loadConfig();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        RingsCommand.register(event.getDispatcher());
    }
}
