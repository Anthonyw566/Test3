package dev.anthonyw.furtherout.gametest;

import dev.anthonyw.furtherout.FurtherOut;
import dev.anthonyw.furtherout.player.Keepers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static dev.anthonyw.furtherout.gametest.DownedTests.ARENA;
import static dev.anthonyw.furtherout.gametest.TestKit.check;
import static dev.anthonyw.furtherout.gametest.TestKit.done;
import static dev.anthonyw.furtherout.gametest.TestKit.player;
import static dev.anthonyw.furtherout.gametest.TestKit.reset;

@GameTestHolder(FurtherOut.MODID)
@PrefixGameTestTemplate(false)
public final class KeeperTests {
    private KeeperTests() {
    }

    private static List<ItemEntity> itemsNear(GameTestHelper h, Vec3 at, net.minecraft.world.item.Item item) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at, at).inflate(6), e -> e.getItem().is(item));
    }

    @GameTest(template = ARENA, batch = "keeper", timeoutTicks = 60)
    public static void yourThingsWaitWithAZombie(GameTestHelper h) {
        reset(h, "level2", "{}");
        ServerPlayer a = player(h, "Keep", new BlockPos(8, 2, 8));
        a.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        a.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
        Vec3 where = a.position();
        a.hurt(h.getLevel().damageSources().generic(), 1000f); // alone: a real death
        check(a.isDeadOrDying(), "test setup: A should die");

        List<Zombie> keepers = h.getLevel().getEntitiesOfClass(Zombie.class, new AABB(where, where).inflate(6),
                Keepers::isKeeper);
        check(keepers.size() == 1, "one zombie should be keeping A's things, found " + keepers.size());
        Zombie keeper = keepers.get(0);
        check("t_Keep".equals(keeper.getCustomName().getString()), "it carries A's name");
        check(keeper.getItemBySlot(EquipmentSlot.HEAD).is(Items.PLAYER_HEAD), "and wears A's head");
        check(keeper.isPersistenceRequired(), "it must never despawn");
        check(keeper.getMaxHealth() <= 12, "it's weak: you come back with nothing");
        check(Keepers.carried(keeper).size() == 2, "it holds both stacks, has " + Keepers.carried(keeper).size());
        check(itemsNear(h, where, Items.DIAMOND).isEmpty(), "nothing is left lying on the floor");

        keeper.kill();
        List<ItemEntity> diamonds = itemsNear(h, where, Items.DIAMOND);
        check(diamonds.size() == 1 && diamonds.get(0).getItem().getCount() == 5, "the diamonds spill out when it dies");
        check(diamonds.get(0).isInvulnerable(), "and can't burn or be blown up");
        check(!itemsNear(h, where, Items.IRON_PICKAXE).isEmpty(), "the pickaxe too");
        done(h);
    }

    @GameTest(template = ARENA, batch = "keeper_home", timeoutTicks = 40)
    public static void atHomeThingsDropAsUsual(GameTestHelper h) {
        reset(h, "safe", "{}");
        ServerPlayer a = player(h, "Home", new BlockPos(8, 2, 8));
        a.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        Vec3 where = a.position();
        a.hurt(h.getLevel().damageSources().generic(), 1000f);
        check(h.getLevel().getEntitiesOfClass(Zombie.class, new AABB(where, where).inflate(6), Keepers::isKeeper).isEmpty(),
                "no zombie in the safe area");
        check(!itemsNear(h, where, Items.DIAMOND).isEmpty(), "the diamonds drop normally at home");
        done(h);
    }
}
