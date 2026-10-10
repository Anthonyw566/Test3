package dev.anthonyw.furtherout.gametest;

import dev.anthonyw.furtherout.FurtherOut;
import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.RingDef;
import dev.anthonyw.furtherout.fx.Fx;
import dev.anthonyw.furtherout.fx.ResourcePacks;
import dev.anthonyw.furtherout.fx.Sfx;
import dev.anthonyw.furtherout.mob.Elites;
import dev.anthonyw.furtherout.mob.InvestigateGoal;
import dev.anthonyw.furtherout.ring.BoundaryWatcher;
import dev.anthonyw.furtherout.ring.Ring;
import dev.anthonyw.furtherout.ring.RingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static dev.anthonyw.furtherout.gametest.DownedTests.ARENA;
import static dev.anthonyw.furtherout.gametest.TestKit.actionBar;
import static dev.anthonyw.furtherout.gametest.TestKit.any;
import static dev.anthonyw.furtherout.gametest.TestKit.check;
import static dev.anthonyw.furtherout.gametest.TestKit.cleanup;
import static dev.anthonyw.furtherout.gametest.TestKit.done;
import static dev.anthonyw.furtherout.gametest.TestKit.drain;
import static dev.anthonyw.furtherout.gametest.TestKit.player;
import static dev.anthonyw.furtherout.gametest.TestKit.reset;
import static dev.anthonyw.furtherout.gametest.TestKit.soundsSent;

/** Danger levels, spawning, digging, the indicator, the sound pack and the shipped configs. */
@GameTestHolder(FurtherOut.MODID)
@PrefixGameTestTemplate(false)
public final class WorldTests {
    private WorldTests() {
    }

    // ================================================================ The indicator

    @GameTest(template = ARENA, batch = "indicator", timeoutTicks = 40)
    public static void crossingALevelIsQuiet(GameTestHelper h) {
        reset(h, "level1", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(8, 2, 8));
        BoundaryWatcher.tick(a); // remembers level 1
        TestKit.placeIn(h, "level3");
        drain(a);
        BoundaryWatcher.tick(a);
        List<Object> sent = drain(a);
        check(actionBar(sent).stream().anyMatch(s -> s.equals("Danger level 3")),
                "one line above the hotbar, got " + actionBar(sent));
        check(soundsSent(sent).contains("beacon"), "and a soft sound, got " + soundsSent(sent));
        check(!any(sent, ClientboundSetTitleTextPacket.class), "no big title");
        drain(a);
        BoundaryWatcher.tick(a);
        check(actionBar(drain(a)).isEmpty(), "nothing more while you stay put");
        BoundaryWatcher.forget(a.getUUID());
        done(h);
    }

    // ================================================================ Night

    @GameTest(template = ARENA, batch = "night", timeoutTicks = 60)
    public static void nightPullsDangerCloserButNotIntoTheSafeArea(GameTestHelper h) {
        reset(h, "level1", "{}");
        TestKit.placeAt(h, 1100); // level 1 by day, inside level 2's reach at night
        ServerLevel level = h.getLevel();
        long before = level.getDayTime();
        BlockPos at = h.absolutePos(new BlockPos(8, 2, 8));
        level.setDayTime(6000);
        h.runAfterDelay(2, () -> {
            RingManager mgr = RingManager.get();
            check(mgr.ringAt(level, at.getX() + 0.5, at.getZ() + 0.5).danger() == 1, "noon: level 1");
            check(!mgr.raisedByNight(level, at.getX() + 0.5, at.getZ() + 0.5), "nothing raised by day");
            level.setDayTime(18000);
            h.runAfterDelay(2, () -> {
                try {
                    check(level.isNight(), "test setup: it should be night");
                    check(mgr.ringAt(level, at.getX() + 0.5, at.getZ() + 0.5).danger() == 2, "midnight: level 2");
                    check(mgr.raisedByNight(level, at.getX() + 0.5, at.getZ() + 0.5), "and the night is why");
                    TestKit.placeAt(h, 390);
                    check(RingManager.get().ringAt(level, at.getX() + 0.5, at.getZ() + 0.5).safeZone(),
                            "the safe area never shrinks");
                } finally {
                    level.setDayTime(before);
                }
                done(h);
            });
        });
    }

    // ================================================================ Noise

    @GameTest(template = ARENA, batch = "noise_blast", timeoutTicks = 200)
    public static void explosionsBringMonstersOverToLook(GameTestHelper h) {
        reset(h, "level2", "{}");
        Husk husk = h.spawn(EntityType.HUSK, new BlockPos(2, 2, 8));
        double startX = husk.getX();
        Vec3 blast = h.absoluteVec(new Vec3(14.5, 2, 8.5));
        h.getLevel().explode(null, blast.x, blast.y, blast.z, 1.0f, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
        check(InvestigateGoal.spot(husk) != null, "the husk should have heard it");
        h.succeedWhen(() -> {
            check(husk.getX() > startX + 4, "the husk should be walking over, moved " + (husk.getX() - startX));
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "noise_fight", timeoutTicks = 40)
    public static void fightsBringNeighboursButOnlyNowAndThen(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(2, 2, 8));
        Husk target = h.spawn(EntityType.HUSK, new BlockPos(3, 2, 8));
        Husk near = h.spawn(EntityType.HUSK, new BlockPos(12, 2, 8));
        Husk busy = h.spawn(EntityType.HUSK, new BlockPos(12, 2, 4));
        near.setNoAi(true);
        busy.setNoAi(true);
        busy.setTarget(a);
        target.hurt(h.getLevel().damageSources().playerAttack(a), 2f);
        check(InvestigateGoal.spot(near) != null, "an idle husk nearby should come to look");
        check(InvestigateGoal.spot(busy) == null, "a husk already chasing someone ignores it");
        Husk later = h.spawn(EntityType.HUSK, new BlockPos(12, 2, 12));
        later.setNoAi(true);
        target.invulnerableTime = 0;
        target.hurt(h.getLevel().damageSources().playerAttack(a), 2f);
        check(InvestigateGoal.spot(later) == null, "a long fight isn't a magnet: one noise every few seconds");
        done(h);
    }

    @GameTest(template = ARENA, batch = "noise_safe", timeoutTicks = 40)
    public static void theSafeAreaIsQuiet(GameTestHelper h) {
        reset(h, "safe", "{}");
        Husk husk = h.spawn(EntityType.HUSK, new BlockPos(2, 2, 8));
        husk.setNoAi(true);
        Vec3 blast = h.absoluteVec(new Vec3(14.5, 2, 8.5));
        h.getLevel().explode(null, blast.x, blast.y, blast.z, 1.0f, net.minecraft.world.level.Level.ExplosionInteraction.NONE);
        check(InvestigateGoal.spot(husk) == null, "nothing is drawn in at home");
        done(h);
    }

    // ================================================================ Alone in the dark

    private static final String DARK_SOON = """
            { "darkSounds": { "minMinutes": 0.05, "maxMinutes": 0.05, "maxLight": 15 } }""";

    @GameTest(template = ARENA, batch = "dark_alone", timeoutTicks = 140)
    public static void aloneInTheDarkYouHearSomethingBehindYou(GameTestHelper h) {
        reset(h, "level2", DARK_SOON);
        ServerPlayer a = player(h, "Alone", new BlockPos(8, 2, 8));
        drain(a);
        h.runAfterDelay(110, () -> {
            List<net.minecraft.network.protocol.game.ClientboundSoundPacket> heard = drain(a).stream()
                    .filter(p -> p instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket)
                    .map(p -> (net.minecraft.network.protocol.game.ClientboundSoundPacket) p).toList();
            check(!heard.isEmpty(), "a lone player in the dark should hear something");
            var first = heard.get(0);
            double away = a.position().distanceTo(new Vec3(first.getX(), a.getY(), first.getZ()));
            check(away >= 3, "it comes from a few blocks away, not from you: " + away);
            done(h);
        });
    }

    @GameTest(template = ARENA, batch = "dark_company", timeoutTicks = 140)
    public static void withAFriendNearbyItStaysQuiet(GameTestHelper h) {
        reset(h, "level2", DARK_SOON);
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 8));
        player(h, "B", new BlockPos(12, 2, 8));
        drain(a);
        h.runAfterDelay(110, () -> {
            String heard = soundsSent(drain(a));
            check(heard.isEmpty(), "company means silence, heard: " + heard);
            done(h);
        });
    }

    // ================================================================ Digging

    private static final BlockPos BUNKER = new BlockPos(12, 2, 8);

    private static List<BlockPos> buildBunker(GameTestHelper h, Block material) {
        List<BlockPos> shell = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean inside = dx == 0 && dz == 0 && dy < 2;
                    if (!inside) {
                        BlockPos pos = BUNKER.offset(dx, dy, dz);
                        h.setBlock(pos, material);
                        shell.add(pos);
                    }
                }
            }
        }
        return shell;
    }

    private static Husk digger(GameTestHelper h, ServerPlayer quarry) {
        Husk digger = h.spawn(EntityType.HUSK, new BlockPos(4, 2, 8));
        Elites.makeDigger(digger);
        digger.setTarget(quarry);
        return digger;
    }

    @GameTest(template = ARENA, batch = "dig_breach", timeoutTicks = 600)
    public static void diggerDigsYouOutOfAHole(GameTestHelper h) {
        reset(h, "level3", "{}");
        List<BlockPos> shell = buildBunker(h, Blocks.STONE);
        digger(h, player(h, "Hider", BUNKER));
        h.succeedWhen(() -> {
            check(shell.stream().anyMatch(pos -> h.getBlockState(pos).isAir()), "the husk should dig through the stone");
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "dig_built", timeoutTicks = 400)
    public static void diggersLeaveBuildingBlocksAlone(GameTestHelper h) {
        reset(h, "level3", "{}");
        List<BlockPos> shell = buildBunker(h, Blocks.OAK_PLANKS);
        digger(h, player(h, "Hider", BUNKER));
        h.runAfterDelay(300, () -> {
            shell.forEach(pos -> h.assertBlockPresent(Blocks.OAK_PLANKS, pos));
            done(h);
        });
    }

    @GameTest(template = ARENA, batch = "dig_base", timeoutTicks = 400)
    public static void diggersLeaveBasesAlone(GameTestHelper h) {
        reset(h, "level3", "{}");
        List<BlockPos> shell = buildBunker(h, Blocks.STONE);
        h.setBlock(BUNKER.offset(0, 0, 3), Blocks.CHEST);
        digger(h, player(h, "Hider", BUNKER));
        h.runAfterDelay(300, () -> {
            shell.forEach(pos -> h.assertBlockPresent(Blocks.STONE, pos));
            done(h);
        });
    }

    @GameTest(template = ARENA, batch = "dig_safe", timeoutTicks = 400)
    public static void nothingDigsInTheSafeArea(GameTestHelper h) {
        reset(h, "safe", "{}");
        List<BlockPos> shell = buildBunker(h, Blocks.STONE);
        digger(h, player(h, "Hider", BUNKER));
        h.runAfterDelay(300, () -> {
            shell.forEach(pos -> h.assertBlockPresent(Blocks.STONE, pos));
            done(h);
        });
    }

    // ================================================================ Spawning & scaling

    @GameTest(template = ARENA, batch = "spawn_scaled", timeoutTicks = 40)
    public static void naturalSpawnsAreScaledExactlyOnce(GameTestHelper h) {
        reset(h, "level3", "{}", WorldTests::withNoElitesOrDigging);
        Mob husk = EntityType.HUSK.spawn(h.getLevel(), h.absolutePos(new BlockPos(8, 2, 8)), MobSpawnType.NATURAL);
        check(husk != null, "natural spawn should succeed outside the safe area");
        Ring ring = RingManager.ringOf(husk);
        check(ring != null && ring.danger() == 3, "arena should be at danger level 3");
        float expected = 20f * (float) ring.healthMult();
        check(Math.abs(husk.getMaxHealth() - expected) < 0.01f, "expected " + expected + " health, got " + husk.getMaxHealth());
        Elites.scale(husk, ring);
        check(Math.abs(husk.getMaxHealth() - expected) < 0.01f, "scaling must never stack");
        check(!Elites.isElite(husk) && !Elites.isDigger(husk), "rolls were disabled for this test");
        done(h);
    }

    @GameTest(template = ARENA, batch = "spawn_safe", timeoutTicks = 40)
    public static void safeAreaBlocksNaturalHostiles(GameTestHelper h) {
        reset(h, "safe", "{}");
        EntityType.HUSK.spawn(h.getLevel(), h.absolutePos(new BlockPos(8, 2, 8)), MobSpawnType.NATURAL);
        h.runAfterDelay(2, () -> {
            h.assertEntityNotPresent(EntityType.HUSK);
            done(h);
        });
    }

    @GameTest(template = ARENA, batch = "spawn_spawner", timeoutTicks = 40)
    public static void spawnerMobsAreLeftAlone(GameTestHelper h) {
        reset(h, "level4", "{}");
        Mob husk = EntityType.HUSK.spawn(h.getLevel(), h.absolutePos(new BlockPos(8, 2, 8)), MobSpawnType.SPAWNER);
        check(husk != null, "spawner mobs must still spawn");
        check(Math.abs(husk.getMaxHealth() - 20f) < 0.01f, "spawner mobs must not be scaled");
        check(!Elites.isElite(husk) && !Elites.isDigger(husk), "spawner mobs must not become elites or diggers");
        done(h);
    }

    private static RingDef withNoElitesOrDigging(RingDef r) {
        return new RingDef(r.id(), r.danger(), r.outerRadius(), r.safeZone(), r.healthMult(), r.damageMult(),
                0.0, 0.0, 0.0, r.modifiers(), r.eliteLoot());
    }

    // ================================================================ Sound pack

    @GameTest(template = ARENA, batch = "fx_sounds", timeoutTicks = 40)
    public static void packPlayersHearPackSoundsOthersVanilla(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer withPack = player(h, "Pack", new BlockPos(4, 2, 4));
        ServerPlayer without = player(h, "NoPack", new BlockPos(6, 2, 4));
        ResourcePacks.setLoadedForTesting(withPack, true);
        drain(withPack);
        drain(without);
        Fx.soundTo(withPack, Sfx.DOWNED_HEARTBEAT);
        Fx.soundTo(without, Sfx.DOWNED_HEARTBEAT);
        Fx.soundTo(withPack, Sfx.VOLATILE_FUSE);
        String heardWith = soundsSent(drain(withPack));
        String heardWithout = soundsSent(drain(without));
        ResourcePacks.setLoadedForTesting(withPack, false);
        check(heardWith.contains("furtherout:downed.heartbeat"), "pack player should get the pack sound, got: " + heardWith);
        check(heardWith.contains("minecraft:entity.creeper.primed"), "sounds without a pack version stay vanilla, got: " + heardWith);
        check(heardWithout.contains("minecraft:block.note_block.basedrum"), "others get the vanilla one, got: " + heardWithout);
        check(!heardWithout.contains("furtherout:"), "never send a pack sound to someone without the pack");
        done(h);
    }

    @GameTest(template = ARENA, batch = "fx_offer", timeoutTicks = 40)
    public static void packIsOfferedOnJoinWithTheBuiltHash(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer joiner = player(h, "Joiner", new BlockPos(4, 2, 4));
        ClientboundResourcePackPushPacket offer = null;
        for (Object p : drain(joiner)) {
            if (p instanceof ClientboundResourcePackPushPacket push && push.id().equals(ResourcePacks.PACK_ID)) {
                offer = push;
            }
        }
        check(offer != null, "joining players should be offered the pack");
        check(offer.hash().matches("[0-9a-f]{40}"), "offer should carry the build's SHA-1, got " + offer.hash());
        check(offer.url().endsWith("furtherout-pack.zip"), "offer url " + offer.url());
        check(!offer.required(), "the pack must be optional by default");
        done(h);
    }

    // ================================================================ Config sanity in a real pack

    @GameTest(template = ARENA, batch = "config", timeoutTicks = 40)
    public static void shippedConfigsAreValidInGame(GameTestHelper h) {
        List<String> problems = Configs.loadAll();
        check(problems.isEmpty(), "default configs should load cleanly: " + problems);
        for (Ring ring : RingManager.get().rings()) {
            if (!ring.eliteLoot().isEmpty()) {
                LootTable table = h.getLevel().getServer().reloadableRegistries().getLootTable(
                        ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(ring.eliteLoot())));
                check(table != LootTable.EMPTY, ring.id() + " loot table " + ring.eliteLoot() + " doesn't exist");
            }
        }
        check(Blocks.STONE.defaultBlockState().is(dev.anthonyw.furtherout.mob.DigGoal.DIGGABLE), "stone is diggable");
        check(!Blocks.OAK_PLANKS.defaultBlockState().is(dev.anthonyw.furtherout.mob.DigGoal.DIGGABLE), "planks are not");
        done(h);
    }
}
