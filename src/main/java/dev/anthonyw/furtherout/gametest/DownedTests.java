package dev.anthonyw.furtherout.gametest;

import dev.anthonyw.furtherout.FurtherOut;
import dev.anthonyw.furtherout.player.DownedManager;
import dev.anthonyw.furtherout.player.MarkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static dev.anthonyw.furtherout.gametest.TestKit.any;
import static dev.anthonyw.furtherout.gametest.TestKit.check;
import static dev.anthonyw.furtherout.gametest.TestKit.cleanup;
import static dev.anthonyw.furtherout.gametest.TestKit.done;
import static dev.anthonyw.furtherout.gametest.TestKit.drain;
import static dev.anthonyw.furtherout.gametest.TestKit.player;
import static dev.anthonyw.furtherout.gametest.TestKit.reset;

/**
 * In-game tests run on a real headless server by `./gradlew runGameTestServer`
 * (and in CI on every push). Each test has its own batch, so they run one at
 * a time and can't see each other's players or config overrides.
 */
@GameTestHolder(FurtherOut.MODID)
@PrefixGameTestTemplate(false)
public final class DownedTests {
    static final String ARENA = "arena";

    private DownedTests() {
    }

    /** Guards every other test's coordinates: floor at y=1, open air from y=2 up. */
    @GameTest(template = ARENA, batch = "arena", timeoutTicks = 20)
    public static void arenaLayoutIsAsExpected(GameTestHelper h) {
        h.assertBlockPresent(Blocks.STONE, new BlockPos(0, 1, 0));
        h.assertBlockPresent(Blocks.STONE, new BlockPos(15, 1, 15));
        h.assertBlockPresent(Blocks.AIR, new BlockPos(8, 2, 8));
        h.assertBlockPresent(Blocks.AIR, new BlockPos(8, 8, 8));
        h.succeed();
    }

    @GameTest(template = ARENA, batch = "downed_revive", timeoutTicks = 300)
    public static void downedInsteadOfDeathThenRevived(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        ServerPlayer b = player(h, "B", new BlockPos(5, 2, 4));
        a.hurt(h.getLevel().damageSources().generic(), 1000f);
        check(a.isAlive(), "A should survive the lethal hit (downed)");
        check(DownedManager.isDowned(a), "A should be downed");
        b.setShiftKeyDown(true);
        h.succeedWhen(() -> {
            check(!DownedManager.isDowned(a), "A not revived yet");
            check(Math.abs(a.getHealth() - 8f) < 0.01f, "revived health should be 8, was " + a.getHealth());
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "downed_bars", timeoutTicks = 60)
    public static void friendsNearbySeeTheBleedOutBar(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        ServerPlayer b = player(h, "B", new BlockPos(12, 2, 12));
        drain(a);
        drain(b);
        a.hurt(h.getLevel().damageSources().generic(), 1000f);
        check(DownedManager.isDowned(a), "A should be downed");
        List<Object> toA = drain(a);
        List<Object> toB = drain(b);
        check(any(toA, ClientboundBossEventPacket.class), "the downed player should get a bleed-out bar");
        check(any(toB, ClientboundBossEventPacket.class), "a friend in range should get the bar too");
        check(TestKit.actionBar(toB).isEmpty() && toB.stream().noneMatch(p ->
                        p instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket),
                "no chat message for the friend - the bar says it");
        done(h);
    }

    @GameTest(template = ARENA, batch = "downed_alone", timeoutTicks = 60)
    public static void aloneMeansDeath(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "Solo", new BlockPos(4, 2, 4));
        a.hurt(h.getLevel().damageSources().generic(), 1000f);
        check(!DownedManager.isDowned(a), "nobody near: should not be downed");
        check(a.isDeadOrDying(), "nobody near: should be dead");
        done(h);
    }

    @GameTest(template = ARENA, batch = "downed_bleed", timeoutTicks = 260)
    public static void downedBleedsOut(GameTestHelper h) {
        reset(h, "level2", "{ \"downed\": { \"bleedOutSeconds\": 5 } }");
        ServerPlayer a = player(h, "A", new BlockPos(1, 2, 1));
        player(h, "Far", new BlockPos(14, 2, 14));
        a.hurt(h.getLevel().damageSources().generic(), 1000f);
        check(DownedManager.isDowned(a), "A should be downed");
        h.succeedWhen(() -> {
            check(a.isDeadOrDying(), "A should have bled out");
            check(!DownedManager.isDowned(a), "downed state should be cleared");
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "downed_void", timeoutTicks = 60)
    public static void voidDamageIsNeverIntercepted(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        player(h, "B", new BlockPos(5, 2, 4));
        a.hurt(h.getLevel().damageSources().fellOutOfWorld(), 1000f);
        check(a.isDeadOrDying(), "void should kill even with a friend nearby");
        done(h);
    }

    @GameTest(template = ARENA, batch = "downed_helpless", timeoutTicks = 60)
    public static void downedPlayersCantFightOrPassTheMark(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        ServerPlayer b = player(h, "B", new BlockPos(5, 2, 4));
        MarkManager.INSTANCE.give(a, 2000);
        DownedManager.INSTANCE.goDown(a, h.getLevel().damageSources().generic());
        float before = b.getHealth();
        a.attack(b);
        check(MarkManager.remaining(b) == 0, "a downed player must not be able to pass the mark");
        check(b.getHealth() == before, "a downed player must not be able to hurt anyone");
        done(h);
    }
}
