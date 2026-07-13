package dev.anthonyw.frontiers.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Parses contracts.json (bounty mob pool, cache loot tables, charter targets). */
public final class ContractsConfigParser {
    public record Data(List<String> bountyMobs, Map<String, String> cacheLootTables,
                       int charterSlayBase, int charterSlayPerDanger) {
        public int charterSlayTarget(int danger) {
            return charterSlayBase + charterSlayPerDanger * danger;
        }
    }

    public record Result(Data data, List<String> errors) {
    }

    private ContractsConfigParser() {
    }

    public static Result parse(String json) {
        List<String> errors = new ArrayList<>();
        List<String> mobs = new ArrayList<>();
        Map<String, String> tables = new HashMap<>();
        int slayBase = 3;
        int slayPerDanger = 2;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("bountyMobs")) {
                for (JsonElement e : root.getAsJsonArray("bountyMobs")) {
                    String id = e.getAsString();
                    if (!id.contains(":")) {
                        errors.add("bounty mob id \"" + id + "\" should look like minecraft:zombie");
                    } else {
                        mobs.add(id);
                    }
                }
            }
            if (root.has("cacheLootTables")) {
                JsonObject obj = root.getAsJsonObject("cacheLootTables");
                for (String key : obj.keySet()) {
                    tables.put(key, obj.get(key).getAsString());
                }
            }
            if (root.has("charterSlayBase")) {
                slayBase = root.get("charterSlayBase").getAsInt();
            }
            if (root.has("charterSlayPerDanger")) {
                slayPerDanger = root.get("charterSlayPerDanger").getAsInt();
            }
        } catch (JsonParseException | IllegalStateException e) {
            errors.add("contracts.json is not valid JSON: " + e.getMessage());
        }
        if (mobs.isEmpty()) {
            mobs = defaultMobs();
        }
        if (!tables.containsKey("default")) {
            tables.put("default", "minecraft:chests/simple_dungeon");
        }
        if (slayBase < 1) {
            errors.add("charterSlayBase must be at least 1");
            slayBase = 1;
        }
        return new Result(new Data(List.copyOf(mobs), Map.copyOf(tables), slayBase, slayPerDanger), errors);
    }

    public static List<String> defaultMobs() {
        return List.of("minecraft:zombie", "minecraft:husk", "minecraft:skeleton", "minecraft:stray",
                "minecraft:spider", "minecraft:creeper", "minecraft:witch", "minecraft:pillager",
                "minecraft:vindicator");
    }

    public static final String DEFAULT_JSON = """
            {
              "bountyMobs": [
                "minecraft:zombie", "minecraft:husk", "minecraft:skeleton", "minecraft:stray",
                "minecraft:spider", "minecraft:creeper", "minecraft:witch", "minecraft:pillager",
                "minecraft:vindicator"
              ],
              "cacheLootTables": {
                "default": "minecraft:chests/simple_dungeon",
                "duskreach": "minecraft:chests/stronghold_corridor",
                "ashenfront": "minecraft:chests/ancient_city"
              },
              "charterSlayBase": 3,
              "charterSlayPerDanger": 2
            }
            """;
}
