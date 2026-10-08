package dev.anthonyw.frontiers.gametest;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.core.EliteModifier;
import dev.anthonyw.frontiers.core.RingDef;
import dev.anthonyw.frontiers.mob.EliteAbilities;
import dev.anthonyw.frontiers.mob.Elites;
import dev.anthonyw.frontiers.player.DownedManager;
import dev.anthonyw.frontiers.player.HexManager;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static dev.anthonyw.frontiers.gametest.TestKit.check;
import static dev.anthonyw.frontiers.gametest.TestKit.cleanup;
import static dev.anthonyw.frontiers.gametest.TestKit.done;
import static dev.anthonyw.frontiers.gametest.TestKit.player;
import static dev.anthonyw.frontiers.gametest.TestKit.reset;

/**
 * In-game tests: run on a real headless server by `./gradlew runGameTestServer`
 * (and in CI on every push). Each test has its own batch so they run one at a
 * time and can't see each other's players or config overrides.
 */
@GameTestHolder(DistantFrontiers.MODID)
@PrefixGameTestTemplate(false)
public final class FrontierGameTests {
    private static final String ARENA = "arena";

    private FrontierGameTests() {
    }

    private static Husk elite(GameTestHelper h, BlockPos pos, EliteModifier... mods) {
        Husk husk = h.spawn(EntityType.HUSK, pos);
        husk.setNoAi(true);
        Elites.promote(husk, List.of(mods));
        return husk;
    }

    // ================================================================ Downed & Revive

    @GameTest(template = ARENA, batch = "downed_revive", timeoutTicks = 300)
    public static void downedInsteadOfDeathThenRevived(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 1, 4));
        ServerPlayer b = player(h, "B", new BlockPos(5, 1, 4));
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

    @GameTest(template = ARENA, batch = "downed_alone", timeoutTicks = 60)
    public static void aloneMeansDeath(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "Solo", new BlockPos(4, 1, 4));
        a.hurt(h.getLevel().damageSources().generic(), 1000f);
        check(!DownedManager.isDowned(a), "nobody near: should not be downed");
        check(a.isDeadOrDying(), "nobody near: should be dead");
        done(h);
    }

    @GameTest(template = ARENA, batch = "downed_bleed", timeoutTicks = 260)
    public static void downedBleedsOut(GameTestHelper h) {
        reset(h, "wildmarch", "{ \"downed\": { \"bleedOutSeconds\": 5 } }");
        ServerPlayer a = player(h, "A", new BlockPos(1, 1, 1));
        player(h, "Far", new BlockPos(14, 1, 14));
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
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 1, 4));
        player(h, "B", new BlockPos(5, 1, 4));
        a.hurt(h.getLevel().damageSources().fellOutOfWorld(), 1000f);
        check(a.isDeadOrDying(), "void should kill even with a friend nearby");
        done(h);
    }

    // ================================================================ The Hex

    @GameTest(template = ARENA, batch = "hex_pass", timeoutTicks = 60)
    public static void hexPassesOnPunchWithNoTagBacks(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 1, 4));
        ServerPlayer b = player(h, "B", new BlockPos(5, 1, 4));
        HexManager.INSTANCE.give(a, 2000, " for testing");
        a.attack(b);
        check(HexManager.remaining(a) == 0, "A should be free of the Hex");
        check(HexManager.remaining(b) > 1900, "B should now hold the Hex, has " + HexManager.remaining(b));
        b.attack(a);
        check(HexManager.remaining(a) == 0, "no tag-backs: A is immune for a few seconds");
        done(h);
    }

    @GameTest(template = ARENA, batch = "hex_jump", timeoutTicks = 60)
    public static void hexJumpsToNearestOnDeath(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 1, 4));
        ServerPlayer b = player(h, "B", new BlockPos(9, 1, 9));
        HexManager.INSTANCE.give(a, 200, " for testing");
        a.kill();
        check(HexManager.remaining(a) == 0, "dead player keeps no Hex");
        check(HexManager.remaining(b) >= 1200, "Hex should jump to B with at least a minute, has "
                + HexManager.remaining(b));
        done(h);
    }

    @GameTest(template = ARENA, batch = "hex_home", timeoutTicks = 100)
    public static void hexFreezesInTheHearth(GameTestHelper h) {
        reset(h, "hearth", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 1, 4));
        HexManager.INSTANCE.give(a, 400, " for testing");
        h.runAfterDelay(60, () -> {
            check(HexManager.remaining(a) == 400, "timer should not run at home, has " + HexManager.remaining(a));
            done(h);
        });
    }

    @GameTest(template = ARENA, batch = "hex_ticks", timeoutTicks = 100)
    public static void hexCountsDownOutside(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 1, 4));
        HexManager.INSTANCE.give(a, 400, " for testing");
        h.runAfterDelay(60, () -> {
            int left = HexManager.remaining(a);
            check(left < 400 && left > 0, "timer should run outside, has " + left);
            done(h);
        });
    }

    @GameTest(template = ARENA, batch = "hex_gain", timeoutTicks = 60)
    public static void killingAChampionHexesYou(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 1, 4));
        Husk champion = elite(h, new BlockPos(8, 1, 8), EliteModifier.WARDED, EliteModifier.MAGNETIC);
        check(Elites.tier(champion) == 2, "two abilities should make a champion");
        champion.hurt(h.getLevel().damageSources().playerAttack(a), 1000f);
        check(!champion.isAlive(), "champion should be dead");
        check(HexManager.remaining(a) == Configs.mechanics().hex().durationTicks(), "killer should be hexed");
        done(h);
    }

    @GameTest(template = ARENA, batch = "hex_lure", timeoutTicks = 80)
    public static void hexLuresNearbyMobs(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(2, 1, 2));
        Husk husk = h.spawn(EntityType.HUSK, new BlockPos(13, 1, 13));
        husk.setNoAi(true);
        HexManager.INSTANCE.give(a, 1000, " for testing");
        h.succeedWhen(() -> {
            check(husk.getTarget() == a, "the husk should be hunting the hexed player");
            cleanup(h);
        });
    }

    // ================================================================ Elite abilities

    @GameTest(template = ARENA, batch = "warper_swap", timeoutTicks = 60)
    public static void warperSwapsYouWithAFriend(GameTestHelper h) {
        reset(h, "wildmarch", """
                { "warper": { "procChance": 1.0, "tossWeight": 0, "swapWeight": 1, "scatterWeight": 0,
                              "netherRiftChance": 0 } }""");
        ServerPlayer a = player(h, "A", new BlockPos(2, 1, 2));
        ServerPlayer b = player(h, "B", new BlockPos(13, 1, 13));
        Husk warper = elite(h, new BlockPos(3, 1, 2), EliteModifier.WARPER);
        Vec3 aBefore = a.position();
        Vec3 bBefore = b.position();
        a.hurt(h.getLevel().damageSources().mobAttack(warper), 1f);
        check(a.position().distanceTo(bBefore) < 0.6, "A should be where B was, is at " + a.position());
        check(b.position().distanceTo(aBefore) < 0.6, "B should be where A was, is at " + b.position());
        done(h);
    }

    @GameTest(template = ARENA, batch = "warper_toss", timeoutTicks = 60)
    public static void warperFlingsYouSkyward(GameTestHelper h) {
        reset(h, "wildmarch", """
                { "warper": { "procChance": 1.0, "tossWeight": 1, "swapWeight": 0, "scatterWeight": 0,
                              "netherRiftChance": 0 } }""");
        ServerPlayer a = player(h, "A", new BlockPos(8, 1, 8));
        Husk warper = elite(h, new BlockPos(9, 1, 8), EliteModifier.WARPER);
        double y = a.getY();
        a.hurt(h.getLevel().damageSources().mobAttack(warper), 1f);
        check(a.getY() >= y + 6, "A should be flung at least 6 blocks up, went from " + y + " to " + a.getY());
        done(h);
    }

    @GameTest(template = ARENA, batch = "thief", timeoutTicks = 120)
    public static void thiefStealsAndDropsItOnDeath(GameTestHelper h) {
        reset(h, "wildmarch", "{ \"thief\": { \"procChance\": 1.0 } }");
        ServerPlayer a = player(h, "A", new BlockPos(4, 1, 4));
        a.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        Husk thief = elite(h, new BlockPos(5, 1, 4), EliteModifier.THIEF);
        a.hurt(h.getLevel().damageSources().mobAttack(thief), 1f);
        check(a.getInventory().getItem(0).isEmpty(), "the diamonds should be gone from the hotbar");
        check(thief.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND), "the thief should be holding them");
        Vec3 where = thief.position();
        thief.kill();
        h.succeedWhen(() -> {
            List<ItemEntity> items = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(where, where).inflate(4),
                    e -> e.getItem().is(Items.DIAMOND) && e.getItem().getCount() == 3);
            check(!items.isEmpty(), "the stolen diamonds should drop where the thief died");
            check(items.get(0).isInvulnerable(), "recovered loot should be indestructible");
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "warded", timeoutTicks = 60)
    public static void wardedOnlyHurtsWhenAFriendHitsIt(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 1, 4));
        ServerPlayer b = player(h, "B", new BlockPos(4, 1, 6));
        Husk warded = elite(h, new BlockPos(6, 1, 5), EliteModifier.WARDED);
        warded.setTarget(a);
        float start = warded.getHealth();
        warded.hurt(h.getLevel().damageSources().playerAttack(a), 10f);
        float lossFromTarget = start - warded.getHealth();
        warded.invulnerableTime = 0;
        float mid = warded.getHealth();
        warded.hurt(h.getLevel().damageSources().playerAttack(b), 10f);
        float lossFromFriend = mid - warded.getHealth();
        check(lossFromTarget <= 2.5f, "its target should barely scratch it, did " + lossFromTarget);
        check(lossFromFriend >= 6f, "a friend should hit it properly, did " + lossFromFriend);
        done(h);
    }

    @GameTest(template = ARENA, batch = "volatile", timeoutTicks = 100)
    public static void volatileExplodesWithoutBreakingBlocks(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        Husk bomb = elite(h, new BlockPos(8, 1, 8), EliteModifier.VOLATILE);
        Pig pig = h.spawn(EntityType.PIG, new BlockPos(9, 1, 8));
        pig.setNoAi(true);
        bomb.kill();
        check(pig.getHealth() == pig.getMaxHealth(), "the blast should wait for the fuse");
        h.succeedWhen(() -> {
            check(!pig.isAlive() || pig.getHealth() < pig.getMaxHealth(), "the pig should be caught in the blast");
            h.assertBlockPresent(Blocks.STONE, new BlockPos(8, 0, 8));
            h.assertBlockPresent(Blocks.STONE, new BlockPos(9, 0, 8));
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "magnetic", timeoutTicks = 40)
    public static void magneticDragsPlayersIn(GameTestHelper h) {
        reset(h, "wildmarch", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(3, 1, 8));
        Husk magnet = elite(h, new BlockPos(12, 1, 8), EliteModifier.MAGNETIC);
        a.setDeltaMovement(Vec3.ZERO);
        EliteAbilities.magneticPulse(magnet);
        Vec3 v = a.getDeltaMovement();
        check(v.x > 0.5, "A should be pulled toward the magnet (+x), velocity " + v);
        check(v.y > 0, "the pull should lift A off the ground, velocity " + v);
        done(h);
    }

    // ================================================================ Digging

    private static final BlockPos BUNKER = new BlockPos(12, 1, 8);

    private static List<BlockPos> buildBunker(GameTestHelper h) {
        List<BlockPos> shell = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean inside = dx == 0 && dz == 0 && dy < 2;
                    if (!inside) {
                        BlockPos pos = BUNKER.offset(dx, dy, dz);
                        h.setBlock(pos, Blocks.STONE);
                        shell.add(pos);
                    }
                }
            }
        }
        return shell;
    }

    @GameTest(template = ARENA, batch = "dig_breach", timeoutTicks = 600)
    public static void diggerBreaksIntoABunker(GameTestHelper h) {
        reset(h, "duskreach", "{}");
        List<BlockPos> shell = buildBunker(h);
        ServerPlayer a = player(h, "Hider", BUNKER);
        Husk digger = h.spawn(EntityType.HUSK, new BlockPos(4, 1, 8));
        Elites.makeDigger(digger);
        digger.setTarget(a);
        h.succeedWhen(() -> {
            boolean breached = shell.stream().anyMatch(pos -> h.getBlockState(pos).isAir());
            check(breached, "the husk should have broken into the bunker");
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "dig_hearth", timeoutTicks = 400)
    public static void nothingDigsInTheHearth(GameTestHelper h) {
        reset(h, "hearth", "{}");
        List<BlockPos> shell = buildBunker(h);
        ServerPlayer a = player(h, "Hider", BUNKER);
        Husk digger = h.spawn(EntityType.HUSK, new BlockPos(4, 1, 8));
        Elites.makeDigger(digger);
        digger.setTarget(a);
        h.runAfterDelay(300, () -> {
            for (BlockPos pos : shell) {
                h.assertBlockPresent(Blocks.STONE, pos);
            }
            done(h);
        });
    }

    // ================================================================ Spawning & scaling

    @GameTest(template = ARENA, batch = "spawn_scaled", timeoutTicks = 40)
    public static void naturalSpawnsAreScaledExactlyOnce(GameTestHelper h) {
        reset(h, "duskreach", "{}", r -> withNoElitesOrDigging(r));
        Mob husk = EntityType.HUSK.spawn(h.getLevel(), h.absolutePos(new BlockPos(8, 1, 8)), MobSpawnType.NATURAL);
        check(husk != null, "natural spawn should succeed outside the Hearth");
        Ring ring = RingManager.ringOf(husk);
        check(ring != null && ring.id().equals("duskreach"), "arena should be in the Duskreach");
        float expected = 20f * (float) ring.healthMult();
        check(Math.abs(husk.getMaxHealth() - expected) < 0.01f, "expected " + expected + " health, got " + husk.getMaxHealth());
        Elites.scale(husk, ring);
        check(Math.abs(husk.getMaxHealth() - expected) < 0.01f, "scaling must never stack");
        check(!Elites.isElite(husk) && !Elites.isDigger(husk), "rolls were disabled for this test");
        done(h);
    }

    @GameTest(template = ARENA, batch = "spawn_hearth", timeoutTicks = 40)
    public static void hearthBlocksNaturalHostiles(GameTestHelper h) {
        reset(h, "hearth", "{}");
        EntityType.HUSK.spawn(h.getLevel(), h.absolutePos(new BlockPos(8, 1, 8)), MobSpawnType.NATURAL);
        h.runAfterDelay(2, () -> {
            h.assertEntityNotPresent(EntityType.HUSK);
            done(h);
        });
    }

    @GameTest(template = ARENA, batch = "spawn_spawner", timeoutTicks = 40)
    public static void spawnerMobsAreLeftAlone(GameTestHelper h) {
        reset(h, "ashenfront", "{}");
        Mob husk = EntityType.HUSK.spawn(h.getLevel(), h.absolutePos(new BlockPos(8, 1, 8)), MobSpawnType.SPAWNER);
        check(husk != null, "spawner mobs must still spawn");
        check(Math.abs(husk.getMaxHealth() - 20f) < 0.01f, "spawner mobs must not be scaled");
        check(!Elites.isElite(husk) && !Elites.isDigger(husk), "spawner mobs must not become elites or diggers");
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
        done(h);
    }

    private static RingDef withNoElitesOrDigging(RingDef r) {
        return new RingDef(r.id(), r.name(), r.colorName(), r.danger(), r.outerRadius(), r.entryMessage(),
                r.safeZone(), r.healthMult(), r.damageMult(), 0.0, 0.0, 0.0, r.modifiers(), r.eliteLoot());
    }
}
