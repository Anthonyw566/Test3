package dev.anthonyw.frontiers.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lenient config reading: missing keys fall back to defaults, wrong types and
 * out-of-range numbers are reported as readable errors (with the JSON path)
 * and replaced by the default, so one typo never takes the whole config down.
 */
final class Json {
    private Json() {
    }

    static JsonObject obj(JsonObject parent, String key) {
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    static double num(JsonObject o, String key, double def, double min, double max,
                      String path, List<String> errors) {
        JsonElement e = o.get(key);
        if (e == null) {
            return def;
        }
        try {
            double value = e.getAsDouble();
            if (value < min || value > max || Double.isNaN(value)) {
                errors.add(path + "." + key + " = " + value + " is outside " + min + ".." + max
                        + " (using " + def + ")");
                return def;
            }
            return value;
        } catch (RuntimeException ex) {
            errors.add(path + "." + key + " should be a number (using " + def + ")");
            return def;
        }
    }

    static int integer(JsonObject o, String key, int def, int min, int max,
                       String path, List<String> errors) {
        return (int) Math.round(num(o, key, def, min, max, path, errors));
    }

    static boolean bool(JsonObject o, String key, boolean def, String path, List<String> errors) {
        JsonElement e = o.get(key);
        if (e == null) {
            return def;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        errors.add(path + "." + key + " should be true or false (using " + def + ")");
        return def;
    }

    static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    /** A list of namespaced ids ("minecraft:zombie"); malformed entries are reported and dropped. */
    static List<String> ids(JsonObject o, String key, List<String> def, String path, List<String> errors) {
        JsonElement e = o.get(key);
        if (e == null) {
            return def;
        }
        if (!e.isJsonArray()) {
            errors.add(path + "." + key + " should be a list");
            return def;
        }
        List<String> out = new ArrayList<>();
        JsonArray array = e.getAsJsonArray();
        for (JsonElement item : array) {
            String id = item.getAsString();
            if (isId(id)) {
                out.add(id);
            } else {
                errors.add(path + "." + key + ": \"" + id + "\" should look like minecraft:zombie");
            }
        }
        return List.copyOf(out);
    }

    static boolean isId(String id) {
        return id != null && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    }
}
