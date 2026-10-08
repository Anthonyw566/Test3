package dev.anthonyw.frontiers.mob;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.config.Configs;
import dev.anthonyw.frontiers.core.EliteModifier;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.util.Scheduler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Why fight elites at all: each one killed by a player rolls its home ring's
 * loot table (champions roll twice), drops bonus XP, and champions get a
 * server-wide kill announcement. Rewards come from vanilla/pack loot tables,
 * so they scale with the ring without inventing new items.
 */
public final class EliteRewards {
    private static final Set<String> warnedTables = new HashSet<>();

    /** The real player behind a kill, or null for machines/fake players/environment. */
    @Nullable
    public static ServerPlayer killer(net.minecraft.world.damagesource.DamageSource source) {
        return source.getEntity() instanceof ServerPlayer p && !(p instanceof FakePlayer) ? p : null;
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || Elites.tier(mob) < 2) {
            return;
        }
        ServerPlayer killer = killer(event.getSource());
        if (killer != null) {
            Ring ring = RingManager.ringOf(mob);
            killer.getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                            "☠ " + Elites.displayName(mob) + " has fallen to " + killer.getName().getString()
                                    + (ring == null ? "" : " in " + ring.name()) + ".")
                    .withStyle(ChatFormatting.GOLD), false);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)
                || !Elites.isElite(mob) || killer(event.getSource()) == null) {
            return;
        }
        int rolls = Elites.tier(mob) >= 2 ? Configs.mechanics().elites().championLootRolls() : 1;
        Vec3 pos = mob.position();
        List<ItemStack> loot = rollLoot(level, lootTableFor(mob), pos, rolls);
        if (Elites.has(mob, EliteModifier.VOLATILE)) {
            // Don't let its own explosion eat the reward: drop it once the dust settles.
            int fuse = Configs.mechanics().volatileAbility().fuseTicks();
            Scheduler.schedule(level.getServer(), fuse + 2, () -> loot.forEach(stack ->
                    level.addFreshEntity(new ItemEntity(level, pos.x, pos.y + 0.5, pos.z, stack))));
            return;
        }
        for (ItemStack stack : loot) {
            event.getDrops().add(new ItemEntity(level, pos.x, pos.y + 0.5, pos.z, stack));
        }
    }

    @SubscribeEvent
    public void onExperience(LivingExperienceDropEvent event) {
        if (event.getEntity() instanceof Mob mob && Elites.isElite(mob) && event.getAttackingPlayer() != null) {
            event.setDroppedExperience(event.getDroppedExperience()
                    + Configs.mechanics().elites().xpBonus() * Elites.tier(mob));
        }
    }

    private static String lootTableFor(Mob mob) {
        RingManager mgr = RingManager.get();
        Ring home = mgr == null ? null : mgr.byId(Elites.homeRing(mob));
        if (home == null) {
            home = RingManager.ringOf(mob);
        }
        return home == null ? "" : home.eliteLoot();
    }

    /** Rolls a loot table {@code rolls} times; empty list if the table is missing. */
    public static List<ItemStack> rollLoot(ServerLevel level, String tableId, Vec3 pos, int rolls) {
        List<ItemStack> out = new ArrayList<>();
        ResourceLocation id = tableId == null || tableId.isEmpty() ? null : ResourceLocation.tryParse(tableId);
        if (id == null || rolls <= 0) {
            return out;
        }
        LootTable table = level.getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
        if (table == LootTable.EMPTY) {
            if (warnedTables.add(tableId)) {
                DistantFrontiers.LOGGER.warn("Loot table {} doesn't exist in this pack - check rings.json", tableId);
            }
            return out;
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, pos)
                .create(LootContextParamSets.CHEST);
        for (int i = 0; i < rolls; i++) {
            out.addAll(table.getRandomItems(params));
        }
        return out;
    }
}
