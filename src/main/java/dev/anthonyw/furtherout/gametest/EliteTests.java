package dev.anthonyw.furtherout.gametest;

import dev.anthonyw.furtherout.FurtherOut;
import dev.anthonyw.furtherout.core.EliteModifier;
import dev.anthonyw.furtherout.mob.EliteAbilities;
import dev.anthonyw.furtherout.mob.Elites;
import dev.anthonyw.furtherout.mob.WarpedPearls;
import dev.anthonyw.furtherout.player.Rifts;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.List;

import static dev.anthonyw.furtherout.gametest.DownedTests.ARENA;
import static dev.anthonyw.furtherout.gametest.TestKit.check;
import static dev.anthonyw.furtherout.gametest.TestKit.cleanup;
import static dev.anthonyw.furtherout.gametest.TestKit.done;
import static dev.anthonyw.furtherout.gametest.TestKit.elite;
import static dev.anthonyw.furtherout.gametest.TestKit.player;
import static dev.anthonyw.furtherout.gametest.TestKit.reset;

@GameTestHolder(FurtherOut.MODID)
@PrefixGameTestTemplate(false)
public final class EliteTests {
    private static final String SWAP_ONLY = """
            { "warper": { "tossWeight": 0, "swapWeight": 1, "scatterWeight": 0, "netherRiftChance": 0 } }""";

    private EliteTests() {
    }

    @GameTest(template = ARENA, batch = "elite_name", timeoutTicks = 20)
    public static void elitesHavePlainNames(GameTestHelper h) {
        reset(h, "level2", "{}");
        Husk warper = elite(h, new BlockPos(8, 2, 8), EliteModifier.WARPER);
        Husk champion = elite(h, new BlockPos(4, 2, 4), EliteModifier.MAGNETIC, EliteModifier.VOLATILE);
        check("Warping Husk".equals(warper.getCustomName().getString()), "got " + warper.getCustomName().getString());
        check("Magnetic Volatile Husk".equals(champion.getCustomName().getString()),
                "got " + champion.getCustomName().getString());
        check(!warper.isCustomNameVisible(), "names show when you look at the mob, not through walls");
        check(!champion.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), "champions don't glow");
        done(h);
    }

    // ---------------------------------------------------------------- warper

    @GameTest(template = ARENA, batch = "warper_swap", timeoutTicks = 60)
    public static void warperSwapsYouWithAFriend(GameTestHelper h) {
        reset(h, "level2", SWAP_ONLY);
        ServerPlayer a = player(h, "A", new BlockPos(2, 2, 2));
        ServerPlayer b = player(h, "B", new BlockPos(13, 2, 13));
        Husk warper = elite(h, new BlockPos(3, 2, 2), EliteModifier.WARPER);
        Vec3 aBefore = a.position();
        Vec3 bBefore = b.position();
        a.hurt(h.getLevel().damageSources().mobAttack(warper), 1f);
        h.succeedWhen(() -> {
            check(a.position().distanceTo(bBefore) < 0.6, "A should be where B was, is at " + a.position());
            check(b.position().distanceTo(aBefore) < 0.6, "B should be where A was, is at " + b.position());
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "warper_recharge", timeoutTicks = 60)
    public static void warperNeedsToRecharge(GameTestHelper h) {
        reset(h, "level2", SWAP_ONLY);
        ServerPlayer a = player(h, "A", new BlockPos(2, 2, 2));
        ServerPlayer b = player(h, "B", new BlockPos(13, 2, 13));
        Husk warper = elite(h, new BlockPos(3, 2, 2), EliteModifier.WARPER);
        check(EliteAbilities.warpCharged(warper), "a fresh warper starts charged");
        a.hurt(h.getLevel().damageSources().mobAttack(warper), 1f);
        check(!EliteAbilities.warpCharged(warper), "warping uses the charge");
        h.runAfterDelay(5, () -> {
            Vec3 aAfterFirst = a.position();
            a.invulnerableTime = 0;
            a.hurt(h.getLevel().damageSources().mobAttack(warper), 1f);
            h.runAfterDelay(5, () -> {
                check(a.position().distanceTo(aAfterFirst) < 0.1, "an uncharged hit must not warp");
                done(h);
            });
        });
    }

    @GameTest(template = ARENA, batch = "warper_shield", timeoutTicks = 60)
    public static void aShieldStopsTheWarp(GameTestHelper h) {
        reset(h, "level2", SWAP_ONLY);
        ServerPlayer a = player(h, "A", new BlockPos(8, 2, 8));
        ServerPlayer b = player(h, "B", new BlockPos(2, 2, 2));
        a.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        a.startUsingItem(InteractionHand.OFF_HAND);
        raiseShieldNow(a);
        check(a.isBlocking(), "test setup: A should be blocking");
        Husk warper = elite(h, new BlockPos(8, 2, 9), EliteModifier.WARPER); // in front of A (A faces +z)
        Vec3 before = a.position();
        Vec3 bBefore = b.position();
        a.hurt(h.getLevel().damageSources().mobAttack(warper), 1f);
        h.runAfterDelay(5, () -> {
            check(a.position().distanceTo(before) < 0.1, "a blocked hit must not warp, A moved to " + a.position());
            check(b.position().distanceTo(bBefore) < 0.1, "B must not be swapped either");
            done(h);
        });
    }

    /** Mock players never tick, so skip the shield's 5-tick raise delay by hand. */
    private static void raiseShieldNow(ServerPlayer player) {
        try {
            Field field = LivingEntity.class.getDeclaredField("useItemRemaining");
            field.setAccessible(true);
            field.setInt(player, player.getUseItem().getUseDuration(player) - 10);
        } catch (ReflectiveOperationException e) {
            throw new GameTestAssertException("couldn't raise the shield: " + e);
        }
    }

    @GameTest(template = ARENA, batch = "warper_toss", timeoutTicks = 60, skyAccess = true)
    public static void warperFlingsYouSkyward(GameTestHelper h) {
        reset(h, "level2", """
                { "warper": { "tossWeight": 1, "swapWeight": 0, "scatterWeight": 0, "netherRiftChance": 0 } }""");
        ServerPlayer a = player(h, "A", new BlockPos(8, 2, 8));
        Husk warper = elite(h, new BlockPos(9, 2, 8), EliteModifier.WARPER);
        double y = a.getY();
        a.hurt(h.getLevel().damageSources().mobAttack(warper), 1f);
        h.succeedWhen(() -> {
            check(a.getY() >= y + 6, "A should be flung at least 6 blocks up, went from " + y + " to " + a.getY());
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "warper_rift", timeoutTicks = 300)
    public static void riftPullsYouBackAfterAFewSeconds(GameTestHelper h) {
        reset(h, "level4", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(8, 2, 8));
        Vec3 home = a.position();
        check(Rifts.send(a, 60), "the rift should find a landing spot in the Nether");
        check(a.level().dimension() == Level.NETHER, "A should be in the Nether");
        h.succeedWhen(() -> {
            check(a.level().dimension() == Level.OVERWORLD, "A should be back in the Overworld");
            check(a.position().distanceTo(home) < 0.6, "A should be back exactly where they were, is at " + a.position());
            check(!Rifts.inRift(a), "the rift should be over");
            cleanup(h);
        });
    }

    // ---------------------------------------------------------------- thief

    @GameTest(template = ARENA, batch = "thief", timeoutTicks = 120)
    public static void thiefCanTakeTheSwordOutOfYourHand(GameTestHelper h) {
        reset(h, "level2", "{ \"thief\": { \"procChance\": 1.0 } }");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        a.getInventory().selected = 0;
        a.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD)); // the only thing in the hotbar
        Husk thief = elite(h, new BlockPos(5, 2, 4), EliteModifier.THIEF);
        a.hurt(h.getLevel().damageSources().mobAttack(thief), 1f);
        check(a.getInventory().getItem(0).isEmpty(), "the sword should be gone from A's hand");
        check(thief.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD), "the thief should be holding it");
        check(thief.hasEffect(net.minecraft.world.effect.MobEffects.GLOWING), "and glowing, so you can chase it");
        Vec3 where = thief.position();
        thief.kill();
        h.succeedWhen(() -> {
            List<ItemEntity> items = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(where, where).inflate(4),
                    e -> e.getItem().is(Items.DIAMOND_SWORD));
            check(!items.isEmpty(), "the sword should drop where the thief died");
            check(items.get(0).isInvulnerable(), "recovered loot should be indestructible");
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "thief_gentle", timeoutTicks = 40)
    public static void gentleThievesLeaveHandsAndToolsAlone(GameTestHelper h) {
        reset(h, "level2", """
                { "thief": { "procChance": 1.0, "takeHeldItem": false, "takeTools": false } }""");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        a.getInventory().selected = 0;
        a.getInventory().setItem(0, new ItemStack(Items.TORCH, 16));         // in hand
        a.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 3));        // fair game
        a.getInventory().setItem(2, new ItemStack(Items.DIAMOND_PICKAXE));   // a tool
        Husk thief = elite(h, new BlockPos(5, 2, 4), EliteModifier.THIEF);
        a.hurt(h.getLevel().damageSources().mobAttack(thief), 1f);
        check(a.getInventory().getItem(1).isEmpty(), "the diamonds should be gone");
        check(a.getInventory().getItem(0).is(Items.TORCH), "not what's in your hand");
        check(a.getInventory().getItem(2).is(Items.DIAMOND_PICKAXE), "not a tool");
        done(h);
    }

    // ---------------------------------------------------------------- warded

    @GameTest(template = ARENA, batch = "warded", timeoutTicks = 60)
    public static void wardedOnlyHurtsWhenAFriendHitsIt(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        ServerPlayer b = player(h, "B", new BlockPos(4, 2, 6));
        Husk warded = elite(h, new BlockPos(6, 2, 5), EliteModifier.WARDED);
        warded.setTarget(a);
        float start = warded.getHealth();
        warded.hurt(h.getLevel().damageSources().playerAttack(a), 10f);
        float lossFromTarget = start - warded.getHealth();
        warded.invulnerableTime = 0;
        float mid = warded.getHealth();
        warded.hurt(h.getLevel().damageSources().playerAttack(b), 10f);
        float lossFromFriend = mid - warded.getHealth();
        check(lossFromTarget <= 1.5f, "its target should barely scratch it, did " + lossFromTarget);
        check(a.getHealth() < a.getMaxHealth(), "and hitting it should sting its target");
        check(lossFromFriend >= 6f, "a friend should hit it properly, did " + lossFromFriend);
        done(h);
    }

    @GameTest(template = ARENA, batch = "warded_solo", timeoutTicks = 40)
    public static void wardedIsNormalWhenYouAreAlone(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "Solo", new BlockPos(4, 2, 4));
        Husk warded = elite(h, new BlockPos(6, 2, 5), EliteModifier.WARDED);
        warded.setTarget(a);
        float start = warded.getHealth();
        warded.hurt(h.getLevel().damageSources().playerAttack(a), 10f);
        check(start - warded.getHealth() >= 6f, "solo players must be able to hurt it normally");
        done(h);
    }

    // ---------------------------------------------------------------- volatile

    @GameTest(template = ARENA, batch = "volatile", timeoutTicks = 100)
    public static void volatileExplodesWithoutBreakingBlocks(GameTestHelper h) {
        reset(h, "level2", "{}");
        Husk bomb = elite(h, new BlockPos(8, 2, 8), EliteModifier.VOLATILE);
        Pig pig = h.spawn(EntityType.PIG, new BlockPos(9, 2, 8));
        pig.setNoAi(true);
        bomb.kill();
        check(pig.getHealth() == pig.getMaxHealth(), "the blast should wait for the fuse");
        h.succeedWhen(() -> {
            check(!pig.isAlive() || pig.getHealth() < pig.getMaxHealth(), "the pig should be caught in the blast");
            h.assertBlockPresent(Blocks.STONE, new BlockPos(8, 1, 8));
            h.assertBlockPresent(Blocks.STONE, new BlockPos(9, 1, 8));
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "volatile_cap", timeoutTicks = 100)
    public static void volatileNeverOneShots(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(8, 2, 9));
        Husk bomb = elite(h, new BlockPos(8, 2, 8), EliteModifier.VOLATILE);
        bomb.kill();
        h.runAfterDelay(60, () -> {
            float lost = a.getMaxHealth() - a.getHealth();
            check(lost > 0, "standing on it should still hurt");
            check(lost <= 18.01f && a.isAlive(), "point blank hurts a lot but never one-shots (12, x1.5 on Hard), lost " + lost);
            done(h);
        });
    }

    // ---------------------------------------------------------------- magnetic

    @GameTest(template = ARENA, batch = "magnetic", timeoutTicks = 40)
    public static void magneticDragsPlayersIn(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(3, 2, 8));
        Husk magnet = elite(h, new BlockPos(12, 2, 8), EliteModifier.MAGNETIC);
        a.setDeltaMovement(Vec3.ZERO);
        EliteAbilities.magneticPulse(magnet);
        Vec3 v = a.getDeltaMovement();
        check(v.x > 0.5, "A should be pulled toward the magnet (+x), velocity " + v);
        check(v.y > 0, "the pull should lift A off the ground, velocity " + v);
        done(h);
    }

    @GameTest(template = ARENA, batch = "magnetic_cover", timeoutTicks = 40)
    public static void magneticCantPullWhatItCantSee(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(3, 2, 8));
        for (int z = 6; z <= 10; z++) {
            for (int y = 2; y <= 4; y++) {
                h.setBlock(new BlockPos(6, y, z), Blocks.STONE);
            }
        }
        Husk magnet = elite(h, new BlockPos(12, 2, 8), EliteModifier.MAGNETIC);
        a.setDeltaMovement(Vec3.ZERO);
        EliteAbilities.magneticPulse(magnet);
        check(a.getDeltaMovement().lengthSqr() < 1.0e-6, "a wall between you and it should save you");
        done(h);
    }

    // ---------------------------------------------------------------- warped pearl

    @GameTest(template = ARENA, batch = "pearl_swap", timeoutTicks = 100)
    public static void warpedPearlSwapsYouWithWhateverLandsClosest(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(2, 2, 2));
        Pig pig = h.spawn(EntityType.PIG, new BlockPos(12, 2, 12));
        pig.setNoAi(true);
        Vec3 aBefore = a.position();
        Vec3 pigBefore = pig.position();
        ThrownEnderpearl pearl = new ThrownEnderpearl(h.getLevel(), a);
        pearl.setItem(WarpedPearls.create(1));
        Vec3 above = h.absoluteVec(new Vec3(12.5, 4.5, 11.0));
        pearl.setPos(above.x, above.y, above.z);
        pearl.setDeltaMovement(0, -0.8, 0);
        h.getLevel().addFreshEntity(pearl);
        h.succeedWhen(() -> {
            check(a.position().distanceTo(pigBefore) < 1.0, "A should be where the pig was, is at " + a.position());
            check(pig.position().distanceTo(aBefore) < 1.0, "the pig should be where A was, is at " + pig.position());
            cleanup(h);
        });
    }

    @GameTest(template = ARENA, batch = "pearl_drop", timeoutTicks = 40)
    public static void warpingChampionsCanDropTheWarpedPearl(GameTestHelper h) {
        reset(h, "level2", "{ \"elites\": { \"warpedPearlChance\": 1.0 } }");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        Husk champion = elite(h, new BlockPos(8, 2, 8), EliteModifier.WARPER, EliteModifier.WARDED);
        Vec3 where = champion.position();
        champion.hurt(h.getLevel().damageSources().playerAttack(a), 1000f);
        h.runAfterDelay(2, () -> {
            List<ItemEntity> pearls = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(where, where).inflate(3),
                    e -> WarpedPearls.isWarped(e.getItem()));
            check(!pearls.isEmpty(), "a warping champion should drop the pearl (chance set to 1 for the test)");
            check(!WarpedPearls.isWarped(new ItemStack(Items.ENDER_PEARL)), "ordinary pearls stay ordinary");
            done(h);
        });
    }

    // ---------------------------------------------------------------- rewards

    @GameTest(template = ARENA, batch = "elite_loot", timeoutTicks = 40)
    public static void championKillsDropLoot(GameTestHelper h) {
        reset(h, "level3", "{}");
        ServerPlayer a = player(h, "A", new BlockPos(4, 2, 4));
        Husk champion = elite(h, new BlockPos(8, 2, 8), EliteModifier.WARDED, EliteModifier.THIEF);
        Vec3 where = champion.position();
        champion.hurt(h.getLevel().damageSources().playerAttack(a), 1000f);
        h.runAfterDelay(2, () -> {
            int stacks = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(where, where).inflate(3)).size();
            check(stacks >= 3, "a champion should drop two rolls of loot, found " + stacks + " stacks");
            done(h);
        });
    }
}
