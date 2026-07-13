package dev.anthonyw.frontiers.economy;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.contract.ContractBoard;
import dev.anthonyw.frontiers.core.ShopConfigParser;
import dev.anthonyw.frontiers.core.ShopConfigParser.OfferDef;
import dev.anthonyw.frontiers.ring.Ring;
import dev.anthonyw.frontiers.ring.RingManager;
import dev.anthonyw.frontiers.state.FrontiersState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Marks shop - clickable chat menu, config-driven stock, tiers gated by
 * how many rings the server has chartered. Parsing/validation lives in the
 * unit-tested core module; this class resolves items against the running pack
 * and hands them out.
 */
public final class Shop {
    private static volatile List<OfferDef> offers = List.of();

    private Shop() {
    }

    public static List<String> load() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(DistantFrontiers.MODID).resolve("shop.json");
        List<String> errors = new ArrayList<>();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, ShopConfigParser.DEFAULT_JSON);
            }
            ShopConfigParser.Result result = ShopConfigParser.parse(Files.readString(file));
            errors.addAll(result.errors());
            for (OfferDef offer : result.offers()) {
                for (ShopConfigParser.ItemDef item : offer.items()) {
                    if (resolveItem(item.itemId()) == null) {
                        errors.add("offer \"" + offer.id() + "\": item " + item.itemId()
                                + " does not exist in this pack");
                    }
                }
            }
            offers = result.offers();
            errors.forEach(e -> DistantFrontiers.LOGGER.warn("shop.json: {}", e));
            DistantFrontiers.LOGGER.info("Loaded {} shop offers", offers.size());
        } catch (Exception e) {
            errors.add("could not read shop.json: " + e.getMessage());
            DistantFrontiers.LOGGER.error("Failed to load shop.json", e);
        }
        return errors;
    }

    @Nullable
    private static Item resolveItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    public static void showShop(ServerPlayer player) {
        FrontiersState state = FrontiersState.get(player.serverLevel().getServer());
        int tier = state.chartered().size();
        player.sendSystemMessage(Component.literal("═══ Expedition Broker ═══")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.literal(
                        "◈ " + state.banked(player.getUUID()) + " banked · shop tier " + tier)
                .withStyle(ChatFormatting.DARK_AQUA));
        for (OfferDef offer : offers) {
            MutableComponent line;
            if (offer.tier() > tier) {
                line = Component.literal("✦ ◈" + offer.cost() + " " + offer.name()
                                + " — unlocks after " + offer.tier() + " charter"
                                + (offer.tier() == 1 ? "" : "s"))
                        .withStyle(ChatFormatting.DARK_GRAY);
            } else {
                String command = "/rings buy " + offer.id();
                line = Component.literal("[Buy] ").withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverFor(offer))))
                        .append(Component.literal("◈" + offer.cost() + " " + offer.name())
                                .withStyle(ChatFormatting.WHITE));
            }
            player.sendSystemMessage(line);
        }
    }

    private static Component hoverFor(OfferDef offer) {
        if ("cache_map".equals(offer.special())) {
            return Component.literal("Reveals a guarded supply cache in your current ring");
        }
        StringBuilder text = new StringBuilder("Contains:");
        for (ShopConfigParser.ItemDef item : offer.items()) {
            text.append("\n  ").append(item.count()).append("× ").append(item.itemId());
        }
        return Component.literal(text.toString());
    }

    public static void buy(ServerPlayer player, String offerId) {
        FrontiersState state = FrontiersState.get(player.serverLevel().getServer());
        OfferDef offer = offers.stream().filter(o -> o.id().equals(offerId)).findFirst().orElse(null);
        if (offer == null) {
            player.sendSystemMessage(Component.literal("No such offer.").withStyle(ChatFormatting.RED));
            return;
        }
        if (offer.tier() > state.chartered().size()) {
            player.sendSystemMessage(Component.literal("That offer is still locked.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // The cache map needs a valid target ring before we take any Marks.
        Ring cacheRing = null;
        if ("cache_map".equals(offer.special())) {
            RingManager mgr = RingManager.get();
            cacheRing = mgr == null ? null
                    : mgr.ringAt(player.serverLevel(), player.getX(), player.getZ());
            if (cacheRing == null || cacheRing.danger() <= 0) {
                player.sendSystemMessage(Component.literal(
                                "Stand in the ring you want mapped (not the Hearth) and buy again.")
                        .withStyle(ChatFormatting.RED));
                return;
            }
        }

        if (!state.spendBanked(player.getUUID(), offer.cost())) {
            player.sendSystemMessage(Component.literal("Not enough banked Marks (◈"
                            + state.banked(player.getUUID()) + "/" + offer.cost() + ").")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        if (cacheRing != null) {
            FrontiersState.Cache cache = ContractBoard.createCache(
                    player.serverLevel().getServer(), cacheRing, false, player);
            player.sendSystemMessage(Component.literal(
                            "The map marks a cache near (" + cache.x + ", " + cache.z + "). Good hunting.")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            for (ShopConfigParser.ItemDef offerItem : offer.items()) {
                Item item = resolveItem(offerItem.itemId());
                if (item == null) {
                    continue;
                }
                int remaining = offerItem.count();
                while (remaining > 0) {
                    ItemStack stack = new ItemStack(item);
                    int take = Math.min(remaining, stack.getMaxStackSize());
                    stack.setCount(take);
                    remaining -= take;
                    player.getInventory().placeItemBackInInventory(stack);
                }
            }
            player.sendSystemMessage(Component.literal("Purchased " + offer.name() + ".")
                    .withStyle(ChatFormatting.GREEN));
        }
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.8f, 0.8f);
    }
}
