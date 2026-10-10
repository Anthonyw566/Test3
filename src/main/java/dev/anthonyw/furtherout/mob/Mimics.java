package dev.anthonyw.furtherout.mob;

import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.MechanicsConfig;
import dev.anthonyw.furtherout.core.MimicRules;
import dev.anthonyw.furtherout.fx.Fx;
import dev.anthonyw.furtherout.fx.Sfx;
import dev.anthonyw.furtherout.ring.Ring;
import dev.anthonyw.furtherout.ring.RingManager;
import dev.anthonyw.furtherout.util.SpawnUtil;
import dev.anthonyw.furtherout.util.Tips;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import javax.annotation.Nullable;

/**
 * Bait. Very rarely, a monster about to spawn in a dark cave near a player
 * becomes something worth picking up instead - a diamond, a few gold ingots.
 * It can't be picked up, and every couple of seconds it twitches. Reach for
 * it and it snaps into a monster that holds the bait and drops it when it
 * dies.
 */
public final class Mimics {
    public static final Mimics INSTANCE = new Mimics();
    private static final String TAG = "fo_mimic";
    private static final String TAG_MOB = "fo_mimic_mob";

    private Mimics() {
    }

    public static boolean isBait(ItemEntity item) {
        return item.getTags().contains(TAG);
    }

    /**
     * Natural spawns: maybe turn this one into bait. True when it did, and the
     * caller should cancel the spawn.
     */
    public static boolean maybeReplace(ServerLevel level, Ring ring, double x, double y, double z) {
        MechanicsConfig.Mimic cfg = Configs.mechanics().mimic();
        if (!cfg.enabled()) {
            return false;
        }
        Player near = level.getNearestPlayer(x, y, z, cfg.playerRange(), Mimics::counts);
        if (!MimicRules.replaces(cfg, ring.danger(), ring.safeZone(), near != null, level.random.nextDouble())
                || !underCover(level, BlockPos.containing(x, y, z))) {
            return false; // caves only
        }
        MimicRules.Bait bait = cfg.baits().get(level.random.nextInt(cfg.baits().size()));
        ItemStack stack = stackOf(bait);
        if (stack.isEmpty()) {
            return false;
        }
        place(level, new Vec3(x, y, z), stack, cfg.mobs().get(level.random.nextInt(cfg.mobs().size())));
        return true;
    }

    /** Solid ground somewhere overhead (leaves don't count): a cave, not a field at night. */
    private static boolean underCover(ServerLevel level, BlockPos pos) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) > pos.getY() + 1;
    }

    private static ItemStack stackOf(MimicRules.Bait bait) {
        ResourceLocation id = ResourceLocation.tryParse(bait.itemId());
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, Math.min(bait.count(), item.getDefaultMaxStackSize()));
    }

    /** Puts bait on the floor. Public for in-game tests. */
    public static ItemEntity place(ServerLevel level, Vec3 at, ItemStack bait, String mobId) {
        ItemEntity item = new ItemEntity(level, at.x, at.y + 0.1, at.z, bait, 0, 0, 0);
        item.setNeverPickUp();
        item.addTag(TAG);
        item.getPersistentData().putString(TAG_MOB, mobId);
        level.addFreshEntity(item);
        return item;
    }

    /** The tell (a twitch now and then) and the trap (reach for it). */
    @SubscribeEvent
    public void onTick(EntityTickEvent.Post event) {
        if (event.getEntity().tickCount % 5 != 0 || !(event.getEntity() instanceof ItemEntity item)
                || !isBait(item) || !(item.level() instanceof ServerLevel level) || item.isRemoved()) {
            return;
        }
        if (item.tickCount % 40 == 0 && item.onGround() && level.random.nextInt(3) > 0) {
            item.setDeltaMovement((level.random.nextDouble() - 0.5) * 0.08, 0.18, (level.random.nextDouble() - 0.5) * 0.08);
            item.hasImpulse = true;
        }
        double radius = Configs.mechanics().mimic().triggerRadius();
        Player near = level.getNearestPlayer(item.getX(), item.getY(), item.getZ(), radius, Mimics::counts);
        if (near instanceof ServerPlayer player && MimicRules.springs(player.distanceTo(item), radius)) {
            spring(level, item, player);
        }
    }

    private static boolean counts(net.minecraft.world.entity.Entity e) {
        return e instanceof Player p && !p.isSpectator() && !p.isCreative();
    }

    @Nullable
    private static Mob spring(ServerLevel level, ItemEntity item, ServerPlayer player) {
        ItemStack bait = item.getItem().copy();
        Vec3 at = item.position();
        String mobId = item.getPersistentData().getString(TAG_MOB);
        item.discard();
        Ring ring = RingManager.get() == null ? null : RingManager.get().ringAt(level, at.x, at.z);
        Mob mob = SpawnUtil.spawnForRing(level, ring == null || ring.safeZone() ? null : ring, mobId,
                BlockPos.containing(at));
        level.sendParticles(ParticleTypes.POOF, at.x, at.y + 0.5, at.z, 20, 0.3, 0.5, 0.3, 0.04);
        Fx.sound(level, at, Sfx.MIMIC_SPRING);
        if (mob == null) {
            level.addFreshEntity(new ItemEntity(level, at.x, at.y, at.z, bait)); // nothing to hide in: just bait
            return null;
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, bait);
        mob.setDropChance(EquipmentSlot.MAINHAND, 2.0f); // always drops, undamaged
        mob.setTarget(player);
        Tips.once(player, "mimic", "Not everything lying around in the dark is what it looks like."
                + " Whatever that was, it's holding the bait now.");
        return mob;
    }
}
