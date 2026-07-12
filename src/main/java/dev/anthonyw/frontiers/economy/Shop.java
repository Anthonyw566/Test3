package dev.anthonyw.frontiers.economy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.contract.ContractBoard;
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
 * how many rings the server has chartered. Prices and contents live in
 * config/distantfrontiers/shop.json so tuning never needs a rebuild.
 *
 * Stock follows the reward principles: acceleration and convenience, never
 * pack-progression skips. The Cache Map is the special offer that lets
 * players buy their own adventure.
 */
public final class Shop {
    private static final List<Offer> OFFERS = new ArrayList<>();

    private Shop() {
    }

    public record OfferItem(String itemId, int count) {
    }

    public record Offer(String id, String name, int cost, int tier,
                        @Nullable String special, List<OfferItem> items) {
    }

    public static void load() {
        Path file = FMLPaths.CONFIGDIR.get().resolve(DistantFrontiers.MODID).resolve("shop.json");
        OFFERS.clear();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, DEFAULT_CONFIG);
            }
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("offers")) {
                JsonObject o = e.getAsJsonObject();
                List<OfferItem> items = new ArrayList<>();
                if (o.has("items")) {
                    for (JsonElement itemElement : o.getAsJsonArray("items")) {
                        JsonObject item = itemElement.getAsJsonObject();
                        String id = item.get("id").getAsString();
                        if (resolveItem(id) == null) {
                            DistantFrontiers.LOGGER.warn("shop.json: unknown item id {}", id);
                            continue;
                        }
                        items.add(new OfferItem(id, item.has("count") ? item.get("count").getAsInt() : 1));
                    }
                }
                OFFERS.add(new Offer(
                        o.get("id").getAsString(),
                        o.has("name") ? o.get("name").getAsString() : o.get("id").getAsString(),
                        o.has("cost") ? o.get("cost").getAsInt() : 10,
                        o.has("tier") ? o.get("tier").getAsInt() : 0,
                        o.has("special") ? o.get("special").getAsString() : null,
                        items));
            }
            DistantFrontiers.LOGGER.info("Loaded {} shop offers", OFFERS.size());
        } catch (Exception e) {
            DistantFrontiers.LOGGER.error("Failed to load shop.json", e);
        }
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
        for (Offer offer : OFFERS) {
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

    private static Component hoverFor(Offer offer) {
        if ("cache_map".equals(offer.special())) {
            return Component.literal("Reveals a guarded supply cache in your current ring");
        }
        StringBuilder text = new StringBuilder("Contains:");
        for (OfferItem item : offer.items()) {
            text.append("\n  ").append(item.count()).append("× ").append(item.itemId());
        }
        return Component.literal(text.toString());
    }

    public static void buy(ServerPlayer player, String offerId) {
        FrontiersState state = FrontiersState.get(player.serverLevel().getServer());
        Offer offer = OFFERS.stream().filter(o -> o.id().equals(offerId)).findFirst().orElse(null);
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
            for (OfferItem offerItem : offer.items()) {
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

    private static final String DEFAULT_CONFIG = """
            {
              "offers": [
                { "id": "repair_kit", "name": "Repair Kit", "cost": 10, "tier": 0,
                  "items": [ { "id": "minecraft:iron_ingot", "count": 16 },
                             { "id": "minecraft:gold_ingot", "count": 8 },
                             { "id": "minecraft:diamond", "count": 3 } ] },
                { "id": "field_rations", "name": "Field Rations", "cost": 8, "tier": 0,
                  "items": [ { "id": "minecraft:cooked_beef", "count": 32 },
                             { "id": "minecraft:golden_apple", "count": 2 },
                             { "id": "minecraft:torch", "count": 64 } ] },
                { "id": "enchanters_satchel", "name": "Enchanter's Satchel", "cost": 25, "tier": 1,
                  "items": [ { "id": "minecraft:lapis_lazuli", "count": 32 },
                             { "id": "minecraft:experience_bottle", "count": 24 },
                             { "id": "minecraft:book", "count": 8 } ] },
                { "id": "cache_map", "name": "Cache Map (your current ring)", "cost": 20, "tier": 1,
                  "special": "cache_map", "items": [] },
                { "id": "voyagers_kit", "name": "Voyager's Kit", "cost": 30, "tier": 2,
                  "items": [ { "id": "minecraft:ender_pearl", "count": 8 },
                             { "id": "minecraft:golden_carrot", "count": 16 },
                             { "id": "minecraft:firework_rocket", "count": 48 } ] },
                { "id": "hearth_feast", "name": "Hearth Feast", "cost": 40, "tier": 3,
                  "items": [ { "id": "minecraft:cake", "count": 3 },
                             { "id": "minecraft:golden_apple", "count": 8 },
                             { "id": "minecraft:emerald", "count": 16 } ] },
                { "id": "ashen_keepsake", "name": "Ashen Keepsake (trophy)", "cost": 100, "tier": 4,
                  "items": [ { "id": "minecraft:wither_skeleton_skull", "count": 1 },
                             { "id": "minecraft:gold_block", "count": 4 } ] }
              ]
            }
            """;
}
