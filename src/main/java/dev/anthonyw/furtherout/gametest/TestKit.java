package dev.anthonyw.furtherout.gametest;

import com.mojang.authlib.GameProfile;
import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.MechanicsConfig;
import dev.anthonyw.furtherout.core.RingDef;
import dev.anthonyw.furtherout.core.RingLookup;
import dev.anthonyw.furtherout.core.RingsConfig;
import dev.anthonyw.furtherout.player.DownedManager;
import dev.anthonyw.furtherout.core.EliteModifier;
import dev.anthonyw.furtherout.mob.Elites;
import dev.anthonyw.furtherout.player.MarkManager;
import dev.anthonyw.furtherout.player.Rifts;
import dev.anthonyw.furtherout.ring.RingManager;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Shared setup for the in-game tests: config overrides, ring placement and mock survival players. */
final class TestKit {
    static final String PREFIX = "t_";

    private TestKit() {
    }

    /** Arena coordinates: the GameTest framework places the structure one block above the test
     * origin, so the stone floor is at relative y=1 and things stand at y=2.
     *
     * Fresh state, mechanics.json overrides, and the arena placed in the middle of {@code ringId}. */
    static void reset(GameTestHelper h, String ringId, String mechanicsJson) {
        reset(h, ringId, mechanicsJson, r -> r);
    }

    static void reset(GameTestHelper h, String ringId, String mechanicsJson, UnaryOperator<RingDef> tweak) {
        removeTestPlayers(h.getLevel().getServer());
        DownedManager.INSTANCE.clearAll();
        MarkManager.INSTANCE.clearAll();
        Rifts.INSTANCE.clearAll();

        List<String> errors = new ArrayList<>();
        Configs.setMechanicsForTesting(MechanicsConfig.parse(mechanicsJson, errors));
        check(errors.isEmpty(), "bad test mechanics: " + errors);
        placeIn(h, ringId, tweak);
    }

    /** Moves the ring origin so the arena sits in the middle of {@code ringId}. Players stay. */
    static void placeIn(GameTestHelper h, String ringId) {
        placeIn(h, ringId, r -> r);
    }

    static void placeIn(GameTestHelper h, String ringId, UnaryOperator<RingDef> tweak) {
        RingsConfig base = RingsConfig.parse(RingsConfig.DEFAULT_JSON).config();
        RingDef ring = base.byId(ringId);
        check(ring != null, "no ring " + ringId);
        double inner = RingLookup.innerRadius(base.rings(), ringId);
        double outer = ring.unbounded() ? inner + 1000 : ring.outerRadius();
        BlockPos center = h.absolutePos(new BlockPos(8, 2, 8));
        double originX = center.getX() - (inner + outer) / 2;
        List<RingDef> rings = base.rings().stream().map(r -> r.id().equals(ringId) ? tweak.apply(r) : r).toList();
        placeAt(h, center.getX() - originX, rings);
    }

    /** Puts the arena's centre exactly {@code distance} blocks from the ring origin. */
    static void placeAt(GameTestHelper h, double distance) {
        placeAt(h, distance, RingsConfig.parse(RingsConfig.DEFAULT_JSON).config().rings());
    }

    private static void placeAt(GameTestHelper h, double distance, List<RingDef> rings) {
        RingsConfig base = RingsConfig.parse(RingsConfig.DEFAULT_JSON).config();
        BlockPos center = h.absolutePos(new BlockPos(8, 2, 8));
        RingManager.setForTesting(new RingsConfig(false, center.getX() + 0.5 - distance, center.getZ() + 0.5,
                base.radialDimensions(), base.dimensionRings(), rings, base.maxHealthMult(), base.maxDamageMult(),
                base.excludedSpawnTypes(), base.entityBlacklist(), base.skipBosses(), base.nightRadiusMultiplier()));
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

    /** A husk elite that stands still (no AI), so tests control every hit. */
    static Husk elite(GameTestHelper h, BlockPos pos, EliteModifier... mods) {
        Husk husk = h.spawn(EntityType.HUSK, pos);
        husk.setNoAi(true);
        Elites.promote(husk, List.of(mods));
        return husk;
    }

    /** Everything the server has sent this mock player since the last drain. */
    static List<Object> drain(ServerPlayer player) {
        List<Object> out = new ArrayList<>();
        if (player.connection.getConnection().channel() instanceof EmbeddedChannel ch) {
            Object msg;
            while ((msg = ch.readOutbound()) != null) {
                out.add(msg);
            }
        }
        return out;
    }

    static String soundsSent(List<Object> packets) {
        StringBuilder out = new StringBuilder();
        for (Object p : packets) {
            if (p instanceof ClientboundSoundPacket sound) {
                out.append(sound.getSound().value().getLocation()).append(' ');
            }
        }
        return out.toString();
    }

    /** Action-bar lines sent to a player (as plain text). */
    static List<String> actionBar(List<Object> packets) {
        List<String> out = new ArrayList<>();
        for (Object p : packets) {
            if (p instanceof ClientboundSystemChatPacket chat && chat.overlay()) {
                out.add(chat.content().getString());
            }
        }
        return out;
    }

    static boolean any(List<Object> packets, Class<?> type) {
        return packets.stream().anyMatch(type::isInstance);
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
