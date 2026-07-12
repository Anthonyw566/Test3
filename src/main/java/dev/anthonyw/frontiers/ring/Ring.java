package dev.anthonyw.frontiers.ring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * One configured difficulty ring. Immutable after config load.
 * Radii are measured from the configured origin on the X/Z plane;
 * a ring covers distances up to {@code outerRadius}, where the previous
 * ring ends. {@code outerRadius < 0} marks the unbounded outermost ring.
 */
public record Ring(
        String id,
        String name,
        ChatFormatting color,
        int danger,
        double outerRadius,
        String entryMessage,
        boolean suppressHostileSpawns,
        double healthMult,
        double damageMult,
        double speedMult,
        double eliteChance,
        double championChance,
        List<String> modifiers,
        double heatGainPerMinute
) {
    public boolean unbounded() {
        return outerRadius < 0;
    }

    public static Ring fromJson(JsonObject o, List<String> errors) {
        String id = getString(o, "id", null);
        if (id == null || id.isBlank()) {
            errors.add("ring is missing an \"id\"");
            id = "unnamed";
        }

        String colorName = getString(o, "color", "white");
        ChatFormatting color = ChatFormatting.getByName(colorName);
        if (color == null || !color.isColor()) {
            errors.add("ring \"" + id + "\": unknown color \"" + colorName + "\"");
            color = ChatFormatting.WHITE;
        }

        JsonObject mobs = o.has("mobs") ? o.getAsJsonObject("mobs") : new JsonObject();
        JsonObject elites = o.has("elites") ? o.getAsJsonObject("elites") : new JsonObject();
        JsonObject heat = o.has("heat") ? o.getAsJsonObject("heat") : new JsonObject();

        List<String> modifiers = new ArrayList<>();
        if (elites.has("modifiers")) {
            JsonArray arr = elites.getAsJsonArray("modifiers");
            for (JsonElement e : arr) {
                modifiers.add(e.getAsString());
            }
        }

        return new Ring(
                id,
                getString(o, "name", id),
                color,
                (int) getDouble(o, "danger", 0),
                getDouble(o, "outerRadius", -1),
                getString(o, "entryMessage", ""),
                getBool(o, "suppressHostileSpawns", false),
                getDouble(mobs, "healthMult", 1.0),
                getDouble(mobs, "damageMult", 1.0),
                getDouble(mobs, "speedMult", 1.0),
                getDouble(elites, "eliteChance", 0.0),
                getDouble(elites, "championChance", 0.0),
                List.copyOf(modifiers),
                getDouble(heat, "gainPerMinute", 0.0)
        );
    }

    private static String getString(JsonObject o, String key, String def) {
        return o.has(key) ? o.get(key).getAsString() : def;
    }

    private static double getDouble(JsonObject o, String key, double def) {
        return o.has(key) ? o.get(key).getAsDouble() : def;
    }

    private static boolean getBool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }
}
