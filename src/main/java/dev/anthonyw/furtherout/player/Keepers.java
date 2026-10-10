package dev.anthonyw.furtherout.player;

import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.KeeperRules;
import dev.anthonyw.furtherout.core.MechanicsConfig;
import dev.anthonyw.furtherout.fx.Fx;
import dev.anthonyw.furtherout.fx.Sfx;
import dev.anthonyw.furtherout.ring.RingManager;
import dev.anthonyw.furtherout.util.SpawnUtil;
import dev.anthonyw.furtherout.util.Tips;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Your things wait with a zombie. Die away from spawn and everything you
 * dropped is carried by a weak zombie wearing your head and your name. It
 * never despawns, doesn't burn or drown, and stays near the spot where you
 * died. Kill it (or ask a friend to) and it all spills out, glowing and
 * safe from lava and despawning. At home, things drop as usual.
 */
public final class Keepers {
    public static final Keepers INSTANCE = new Keepers();
    private static final String TAG = "fo_keeper";
    private static final String TAG_PENDING = "fo_keeper_at";

    private Keepers() {
    }

    public static boolean isKeeper(Entity entity) {
        return entity instanceof Zombie && entity.getPersistentData().contains(TAG);
    }

    /** What a keeper is carrying (for /rings inspect and tests). */
    public static List<ItemStack> carried(Zombie keeper) {
        List<ItemStack> out = new ArrayList<>();
        ListTag items = keeper.getPersistentData().getCompound(TAG).getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(keeper.registryAccess(), items.getCompound(i));
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ dying

    /** Low priority: grave mods and the like get first pick; we only take what's left. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        MechanicsConfig.Keeper cfg = Configs.mechanics().keeper();
        boolean safe = RingManager.isSafe(level, player.blockPosition());
        boolean belowWorld = player.getY() < level.getMinBuildHeight();
        if (!KeeperRules.shouldKeep(cfg, event.getDrops().size(), safe, belowWorld)) {
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            if (!drop.getItem().isEmpty()) {
                items.add(drop.getItem().copy());
            }
        }
        Zombie keeper = spawn(level, player, items, cfg);
        if (keeper != null) {
            event.getDrops().clear();
            CompoundTag pending = new CompoundTag();
            pending.putLong("pos", keeper.blockPosition().asLong());
            pending.putString("dim", level.dimension().location().toString());
            player.getPersistentData().put(TAG_PENDING, pending);
        }
    }

    @Nullable
    private static Zombie spawn(ServerLevel level, ServerPlayer owner, List<ItemStack> items, MechanicsConfig.Keeper cfg) {
        Zombie zombie = EntityType.ZOMBIE.create(level);
        if (zombie == null) {
            return null;
        }
        BlockPos home = owner.blockPosition();
        BlockPos ground = SpawnUtil.isStandable(level, home) ? home : SpawnUtil.findGroundNear(level, home, 0, 4);
        if (ground != null) {
            home = ground;
        }
        zombie.moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, owner.getYRot(), 0f);

        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, new ResolvableProfile(owner.getGameProfile()));
        zombie.setItemSlot(EquipmentSlot.HEAD, head); // a helmet: it never burns in daylight
        zombie.setDropChance(EquipmentSlot.HEAD, 0f);
        zombie.setCustomName(owner.getName().copy());
        zombie.setCustomNameVisible(true);
        zombie.setPersistenceRequired();
        zombie.setCanPickUpLoot(false);
        zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
        setBase(zombie.getAttribute(Attributes.MAX_HEALTH), cfg.health());
        setBase(zombie.getAttribute(Attributes.ATTACK_DAMAGE), cfg.attackDamage());
        setBase(zombie.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE), 0);
        zombie.setHealth(zombie.getMaxHealth());
        zombie.restrictTo(home, (int) cfg.leashRadius());

        ListTag list = new ListTag();
        for (ItemStack stack : items) {
            list.add(stack.save(level.registryAccess()));
        }
        CompoundTag data = new CompoundTag();
        data.put("items", list);
        data.putLong("home", home.asLong());
        data.putUUID("owner", owner.getUUID());
        zombie.getPersistentData().put(TAG, data);

        if (!level.addFreshEntity(zombie)) {
            return null;
        }
        level.sendParticles(ParticleTypes.SOUL, zombie.getX(), zombie.getY() + 1, zombie.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
        return zombie;
    }

    private static void setBase(@Nullable AttributeInstance attribute, double value) {
        if (attribute != null) {
            attribute.setBaseValue(value);
        }
    }

    /** One line after respawning: where your things are. */
    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isEndConquered()) {
            return;
        }
        CompoundTag pending = player.getPersistentData().getCompound(TAG_PENDING);
        player.getPersistentData().remove(TAG_PENDING);
        if (pending.isEmpty()) {
            return;
        }
        BlockPos pos = BlockPos.of(pending.getLong("pos"));
        Tips.once(player, "keeper", "When you die away from spawn, a zombie wearing your head keeps your things."
                + " It stays where you died. Anyone can get them back from it.");
        player.sendSystemMessage(Component.literal("Your things are with a zombie at " + pos.getX() + ", "
                + pos.getY() + ", " + pos.getZ()).withStyle(ChatFormatting.GRAY));
    }

    /** NeoForge doesn't copy persistent data through death by itself; keep the "where" note. */
    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        CompoundTag pending = event.getOriginal().getPersistentData().getCompound(TAG_PENDING);
        if (!pending.isEmpty()) {
            event.getEntity().getPersistentData().put(TAG_PENDING, pending.copy());
        }
    }

    // ------------------------------------------------------------------ waiting

    /** Stays near where you died, and shows a faint wisp now and then. */
    @SubscribeEvent
    public void onTick(EntityTickEvent.Post event) {
        if (event.getEntity().tickCount % 20 != 0 || !isKeeper(event.getEntity())
                || !(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        Zombie keeper = (Zombie) event.getEntity();
        BlockPos home = BlockPos.of(keeper.getPersistentData().getCompound(TAG).getLong("home"));
        double distance = Math.sqrt(keeper.blockPosition().distSqr(home));
        switch (KeeperRules.leash(distance, keeper.getTarget() != null, Configs.mechanics().keeper().leashRadius())) {
            case GIVE_UP_CHASE -> {
                keeper.setTarget(null);
                keeper.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
            }
            case WALK_HOME -> keeper.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0);
            case STAY -> {
            }
        }
        if (keeper.tickCount % 60 == 0) {
            level.sendParticles(ParticleTypes.SOUL, keeper.getX(), keeper.getEyeY() + 0.3, keeper.getZ(),
                    1, 0.15, 0.1, 0.15, 0.01);
        }
    }

    /** Keepers stay zombies: no drowning into a drowned (that would lose the items). */
    @SubscribeEvent
    public void onConvert(LivingConversionEvent.Pre event) {
        if (isKeeper(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ getting it back

    /** Killed: everything spills out right away. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Zombie keeper && isKeeper(keeper)
                && keeper.level() instanceof ServerLevel level) {
            spill(level, keeper);
        }
    }

    /** Removed for good some other way (peaceful difficulty, a command): same thing. */
    @SubscribeEvent
    public void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Zombie keeper)
                || !isKeeper(keeper)) {
            return;
        }
        Entity.RemovalReason reason = keeper.getRemovalReason();
        if (reason != null && reason.shouldDestroy()) {
            spill(level, keeper); // otherwise it's just unloading with its chunk
        }
    }

    private static void spill(ServerLevel level, Zombie keeper) {
        List<ItemStack> items = carried(keeper);
        keeper.getPersistentData().remove(TAG);
        Vec3 at = keeper.position();
        for (ItemStack stack : items) {
            SpawnUtil.dropSafely(level, at, stack);
        }
        if (!items.isEmpty()) {
            Fx.sound(level, at, Sfx.KEEPER_SPILL);
            level.sendParticles(ParticleTypes.SOUL, at.x, at.y + 1, at.z, 16, 0.4, 0.6, 0.4, 0.04);
        }
    }
}
