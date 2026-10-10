package dev.anthonyw.furtherout.mob;

import dev.anthonyw.furtherout.config.Configs;
import dev.anthonyw.furtherout.core.PearlRules;
import dev.anthonyw.furtherout.fx.Fx;
import dev.anthonyw.furtherout.fx.Sfx;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.util.List;

/**
 * The Warped Ender Pearl: an ordinary ender pearl (no new items, nothing
 * for clients to install) marked by its data. Thrown, it swaps you with
 * whatever living thing is closest to where it lands. With nothing close
 * enough it's a normal pearl.
 */
public final class WarpedPearls {
    public static final WarpedPearls INSTANCE = new WarpedPearls();
    private static final String MARK = "furtherout_warped";

    private WarpedPearls() {
    }

    public static ItemStack create(int count) {
        ItemStack stack = new ItemStack(Items.ENDER_PEARL, count);
        stack.set(DataComponents.ITEM_NAME, Component.literal("Warped Ender Pearl"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Swaps you with whatever lands closest")
                .withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(MARK, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    public static boolean isWarped(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(MARK);
    }

    @SubscribeEvent
    public void onImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof ThrownEnderpearl pearl) || !(pearl.level() instanceof ServerLevel level)
                || !isWarped(pearl.getItem()) || !(pearl.getOwner() instanceof ServerPlayer owner)
                || owner.level() != level || !owner.isAlive()) {
            return;
        }
        Vec3 at = event.getRayTraceResult().getLocation();
        double radius = Configs.mechanics().elites().pearlSwapRadius();
        List<PearlRules.Candidate<LivingEntity>> candidates = level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(at, at).inflate(radius), e -> e != owner && e.isAlive() && swappable(e))
                .stream().map(e -> new PearlRules.Candidate<>(e, e.position().distanceTo(at))).toList();
        LivingEntity other = PearlRules.swapTarget(candidates, radius);
        if (other == null) {
            return; // nothing close: an ordinary pearl
        }
        event.setCanceled(true);
        pearl.discard();
        swap(level, owner, other);
    }

    private static boolean swappable(LivingEntity e) {
        if (e instanceof Player p) {
            return !p.isSpectator() && !p.isCreative();
        }
        return !e.getType().is(Tags.EntityTypes.BOSSES);
    }

    private static void swap(ServerLevel level, ServerPlayer owner, LivingEntity other) {
        Vec3 a = owner.position();
        Vec3 b = other.position();
        owner.stopRiding();
        other.stopRiding();
        owner.teleportTo(b.x, b.y, b.z);
        other.teleportTo(a.x, a.y, a.z);
        owner.resetFallDistance();
        other.resetFallDistance();
        for (Vec3 p : List.of(a, b)) {
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y + 1, p.z, 30, 0.4, 0.8, 0.4, 0.1);
            Fx.sound(level, p, Sfx.WARP);
        }
        if (other instanceof ServerPlayer friend) {
            friend.displayClientMessage(Component.literal("Swapped places with " + owner.getName().getString())
                    .withStyle(ChatFormatting.LIGHT_PURPLE), true);
        }
    }
}
