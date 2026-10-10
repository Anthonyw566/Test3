package dev.anthonyw.furtherout;

import com.mojang.logging.LogUtils;
import dev.anthonyw.furtherout.command.RingsCommand;
import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.fx.ResourcePacks;
import dev.anthonyw.furtherout.mob.EliteAbilities;
import dev.anthonyw.furtherout.mob.EliteRewards;
import dev.anthonyw.furtherout.mob.MobSpawns;
import dev.anthonyw.furtherout.player.DownedManager;
import dev.anthonyw.furtherout.player.MarkManager;
import dev.anthonyw.furtherout.player.Rifts;
import dev.anthonyw.furtherout.ring.BoundaryWatcher;
import dev.anthonyw.furtherout.util.Scheduler;
import dev.anthonyw.furtherout.util.Tips;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

/**
 * Further Out: the further from spawn, the harder the world - and the more
 * your friends matter. Danger levels by distance, five elite abilities, mobs
 * that dig to you, Downed & Revive, and being Marked.
 *
 * Server-side only: registers no items, blocks or network channels, so
 * players join with an unmodified ATM10 client.
 */
@Mod(FurtherOut.MODID)
public final class FurtherOut {
    public static final String MODID = "furtherout";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FurtherOut(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.register(new ServerTicker());
        NeoForge.EVENT_BUS.register(new MobSpawns());
        NeoForge.EVENT_BUS.register(new EliteAbilities());
        NeoForge.EVENT_BUS.register(new EliteRewards());
        NeoForge.EVENT_BUS.register(DownedManager.INSTANCE);
        NeoForge.EVENT_BUS.register(MarkManager.INSTANCE);
        NeoForge.EVENT_BUS.register(Rifts.INSTANCE);
        NeoForge.EVENT_BUS.register(Tips.INSTANCE);
        NeoForge.EVENT_BUS.register(ResourcePacks.INSTANCE);
    }

    private void onServerAboutToStart(ServerAboutToStartEvent event) {
        Configs.loadAll();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        Scheduler.clear();
        DownedManager.INSTANCE.clearAll();
        MarkManager.INSTANCE.clearAll();
        Rifts.INSTANCE.clearAll();
        BoundaryWatcher.clear();
        ResourcePacks.clear();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        RingsCommand.register(event.getDispatcher());
    }
}
