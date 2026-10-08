package dev.anthonyw.frontiers.gametest;

import com.mojang.authlib.GameProfile;
import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.core.MechanicsConfig;
import dev.anthonyw.frontiers.core.RingDef;
import dev.anthonyw.frontiers.core.RingLookup;
import dev.anthonyw.frontiers.core.RingsConfig;
import dev.anthonyw.frontiers.player.DownedManager;
import dev.anthonyw.frontiers.player.HexManager;
import dev.anthonyw.frontiers.ring.RingManager;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Shared setup for the in-game tests: config overrides, ring placement and mock survival players. */
final class TestKit {
    static final String PREFIX = "dft_";

    private TestKit() {
    }

    /** Fresh state, mechanics.json overrides, and the arena placed in the middle of {@code ringId}. */
    static void reset(GameTestHelper h, String ringId, String mechanicsJson) {
        reset(h, ringId, mechanicsJson, r -> r);
    }

    static void reset(GameTestHelper h, String ringId, String mechanicsJson, UnaryOperator<RingDef> tweak) {
        removeTestPlayers(h.getLevel().getServer());
        DownedManager.INSTANCE.clearAll();
        HexManager.INSTANCE.clearAll();

        List<String> errors = new ArrayList<>();
        Configs.setMechanicsForTesting(MechanicsConfig.parse(mechanicsJson, errors));
        check(errors.isEmpty(), "bad test mechanics: " + errors);

        RingsConfig base = RingsConfig.parse(RingsConfig.DEFAULT_JSON).config();
        RingDef ring = base.byId(ringId);
        check(ring != null, "no ring " + ringId);
        double inner = RingLookup.innerRadius(base.rings(), ringId);
        double outer = ring.unbounded() ? inner + 1000 : ring.outerRadius();
        BlockPos center = h.absolutePos(new BlockPos(8, 1, 8));
        double originX = center.getX() - (inner + outer) / 2;
        List<RingDef> rings = base.rings().stream().map(r -> r.id().equals(ringId) ? tweak.apply(r) : r).toList();
        RingManager.setForTesting(new RingsConfig(false, originX, center.getZ(), base.radialDimensions(),
                base.dimensionRings(), rings, base.maxHealthMult(), base.maxDamageMult(),
                base.excludedSpawnTypes(), base.entityBlacklist(), base.skipBosses()));
    }

    /** A real ServerPlayer in survival, without spawn protection, standing at a spot in the arena. */
    static ServerPlayer player(GameTestHelper h, String name, BlockPos relative) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), PREFIX + name), false);
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        Vec3 pos = h.absoluteVec(Vec3.atBottomCenterOf(relative));
        player.teleportTo(level, pos.x, pos.y, pos.z, 0f, 0f);
        try {
            Field field = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            field.setAccessible(true);
            field.setInt(player, 0);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("couldn't clear spawn protection: " + e);
        }
        player.invulnerableTime = 0;
        return player;
    }

    static void removeTestPlayers(MinecraftServer server) {
        for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (p.getGameProfile().getName().startsWith(PREFIX)) {
                server.getPlayerList().remove(p);
            }
        }
    }

    /** Synchronous finish: clean up and pass. */
    static void done(GameTestHelper h) {
        cleanup(h);
        h.succeed();
    }

    /** For succeedWhen lambdas, which pass the test themselves once the checks hold. */
    static void cleanup(GameTestHelper h) {
        removeTestPlayers(h.getLevel().getServer());
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
