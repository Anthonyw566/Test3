package dev.anthonyw.furtherout.gametest;

import dev.anthonyw.furtherout.FurtherOut;
import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.EliteModifier;
import dev.anthonyw.furtherout.mob.Elites;
import dev.anthonyw.furtherout.player.MarkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static dev.anthonyw.furtherout.gametest.DownedTests.ARENA;
import static dev.anthonyw.furtherout.gametest.TestKit.any;
import static dev.anthonyw.furtherout.gametest.TestKit.check;
import static dev.anthonyw.furtherout.gametest.TestKit.cleanup;
import static dev.anthonyw.furtherout.gametest.TestKit.done;
import static dev.anthonyw.furtherout.gametest.TestKit.drain;
import static dev.anthonyw.furtherout.gametest.TestKit.elite;
import static dev.anthonyw.furtherout.gametest.TestKit.player;
import static dev.anthonyw.furtherout.gametest.TestKit.reset;

@GameTestHolder(FurtherOut.MODID)
@PrefixGameTestTemplate(false)
public final class MarkedTests {
    private MarkedTests() {
    }

    @GameTest(template = ARENA, batch = "mark_pass", timeoutTicks = 60)
    public static void markPassesOnPunchWithNoTagBacks(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        ServerPlayer b = player(h, "B", new BlockPos(5, 2, 4));
        MarkManager.INSTANCE.give(a, 2000);
        a.attack(b);
        check(MarkManager.remaining(a) == 0, "A should be free of the mark");
        check(MarkManager.remaining(b) > 1900, "B should now hold the mark, has " + MarkManager.remaining(b));
        b.attack(a);
        check(MarkManager.remaining(a) == 0, "no tag-backs: A is immune for a few seconds");
        done(h);
    }

    @GameTest(template = ARENA, batch = "mark_jump", timeoutTicks = 60)
    public static void markMovesToNearestOnDeath(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        ServerPlayer b = player(h, "B", new BlockPos(9, 2, 9));
        MarkManager.INSTANCE.give(a, 200);
        a.kill();
        check(MarkManager.remaining(a) == 0, "dead player keeps no mark");
        check(MarkManager.remaining(b) >= Configs.mechanics().marked().minJumpTicks(),
                "the mark should move to B with at least the minimum left, has " + MarkManager.remaining(b));
        done(h);
    }

    @GameTest(template = ARENA, batch = "mark_bar", timeoutTicks = 40)
    public static void markShowsABossBarNotATitle(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        drain(a);
        MarkManager.INSTANCE.give(a, 400);
        List<Object> sent = drain(a);
        check(any(sent, ClientboundBossEventPacket.class), "being marked should show a boss bar");
        check(!any(sent, ClientboundSetTitleTextPacket.class), "no big title");
        MarkManager.INSTANCE.clear(a);
        check(any(drain(a), ClientboundBossEventPacket.class), "clearing the mark should remove the bar");
        done(h);
    }

    @GameTest(template = ARENA, batch = "mark_safe", timeoutTicks = 120)
    public static void markWaitsForYouAtHome(GameTestHelper h) {
        reset(h, "safe", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        MarkManager.INSTANCE.give(a, 30);
        h.runAfterDelay(70, () -> {
            check(MarkManager.remaining(a) == 30, "you can't wait it out at home, has " + MarkManager.remaining(a));
            done(h);
        });
    }

    @GameTest(template = ARENA, batch = "mark_reward", timeoutTicks = 120)
    public static void markPaysOutWhenItEndsOutside(GameTestHelper h) {
        reset(h, "level2", "{ \"marked\": { \"firstAmbushAfterSeconds\": 600 } }");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        MarkManager.INSTANCE.give(a, 30);
        h.runAfterDelay(70, () -> {
            check(MarkManager.remaining(a) == 0, "the mark should have run out");
            List<ExperienceOrb> orbs = h.getLevel().getEntitiesOfClass(ExperienceOrb.class, a.getBoundingBox().inflate(4));
            check(!orbs.isEmpty(), "outlasting it away from spawn should pay XP");
            done(h);
        });
    }

    @GameTest(template = ARENA, batch = "mark_gain", timeoutTicks = 60)
    public static void killingAChampionMarksYou(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        Husk champion = elite(h, new BlockPos(8, 2, 8), EliteModifier.WARDED, EliteModifier.MAGNETIC);
        check(Elites.tier(champion) == 2, "two abilities should make a champion");
        champion.hurt(h.getLevel().damageSources().playerAttack(a), 1000f);
        check(!champion.isAlive(), "champion should be dead");
        check(MarkManager.remaining(a) == Configs.mechanics().marked().durationTicks(), "killer should be marked");
        done(h);
    }

    @GameTest(template = ARENA, batch = "mark_lure", timeoutTicks = 80)
    public static void markLuresNearbyMobs(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(2, 2, 2));
        Husk husk = h.spawn(EntityType.HUSK, new BlockPos(13, 2, 13));
        husk.setNoAi(true);
        MarkManager.INSTANCE.give(a, 1000);
        h.succeedWhen(() -> {
            check(husk.getTarget() == a, "the husk should be hunting the marked player");
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "mark_ambush", timeoutTicks = 120, skyAccess = true)
    public static void ambushesComeInPacks(GameTestHelper h) {
        reset(h, "level4", "{ \"marked\": { \"firstAmbushAfterSeconds\": 1, \"ambushMobs\": [\"minecraft:husk\"] } }");
        ServerPlayer a = player(h, "A", new BlockPos(8, 2, 8));
        MarkManager.INSTANCE.give(a, 2000);
        h.runAfterDelay(70, () -> {
            int husks = h.getLevel().getEntitiesOfClass(Husk.class, new AABB(a.blockPosition()).inflate(24)).size();
            check(husks >= 1 && husks <= 5, "one ambush of up to five expected at danger 4, found " + husks);
            done(h);
        });
    }
}
