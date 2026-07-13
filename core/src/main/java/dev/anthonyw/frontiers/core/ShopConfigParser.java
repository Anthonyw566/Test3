package dev.anthonyw.frontiers.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses shop.json into engine-free offer definitions. Item ids are validated
 * structurally here (namespace:path shape); whether they exist in the running
 * pack is checked by the mod layer against the item registry.
 */
public final class ShopConfigParser {
    public record ItemDef(String itemId, int count) {
    }

    public record OfferDef(String id, String name, int cost, int tier,
                           String special, List<ItemDef> items) {
    }

    public record Result(List<OfferDef> offers, List<String> errors) {
    }

    private ShopConfigParser() {
    }

    public static Result parse(String json) {
        List<OfferDef> offers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            errors.add("shop.json is not valid JSON: " + e.getMessage());
            return new Result(offers, errors);
        }
        if (!root.has("offers")) {
            errors.add("shop.json has no \"offers\" array");
            return new Result(offers, errors);
        }
        for (JsonElement e : root.getAsJsonArray("offers")) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("id")) {
                errors.add("an offer is missing its \"id\"");
                continue;
            }
            String id = o.get("id").getAsString();
            List<ItemDef> items = new ArrayList<>();
            if (o.has("items")) {
                for (JsonElement itemElement : o.getAsJsonArray("items")) {
                    JsonObject item = itemElement.getAsJsonObject();
                    String itemId = item.has("id") ? item.get("id").getAsString() : "";
                    if (!itemId.contains(":")) {
                        errors.add("offer \"" + id + "\": item id \"" + itemId
                                + "\" should look like minecraft:iron_ingot");
                        continue;
                    }
                    int count = item.has("count") ? item.get("count").getAsInt() : 1;
                    if (count < 1) {
                        errors.add("offer \"" + id + "\": count must be at least 1");
                        continue;
                    }
                    items.add(new ItemDef(itemId, count));
                }
            }
            int cost = o.has("cost") ? o.get("cost").getAsInt() : 10;
            if (cost < 0) {
                errors.add("offer \"" + id + "\": cost cannot be negative");
                cost = 0;
            }
            String special = o.has("special") ? o.get("special").getAsString() : null;
            if (special == null && items.isEmpty()) {
                errors.add("offer \"" + id + "\" has no items and no special - it would sell nothing");
            }
            offers.add(new OfferDef(
                    id,
                    o.has("name") ? o.get("name").getAsString() : id,
                    cost,
                    o.has("tier") ? o.get("tier").getAsInt() : 0,
                    special,
                    List.copyOf(items)));
        }
        return new Result(offers, errors);
    }

    public static final String DEFAULT_JSON = """
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
