package dev.anthonyw.frontiers.config;

import dev.anthonyw.frontiers.DistantFrontiers;
import dev.anthonyw.frontiers.core.MechanicsConfig;
import dev.anthonyw.frontiers.core.RingsConfig;
import dev.anthonyw.frontiers.ring.RingManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads config/distantfrontiers/rings.json and mechanics.json (writing the
 * defaults on first run). Parsing and validation live in the unit-tested core
 * module; this class only does file IO and checks ids against the running
 * pack so typos show up in the log and in /rings reload.
 */
public final class Configs {
    private static volatile MechanicsConfig mechanics = MechanicsConfig.defaults();

    private Configs() {
    }

    public static MechanicsConfig mechanics() {
        return mechanics;
    }

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve(DistantFrontiers.MODID);
    }

    /** Loads everything; returns readable problems (empty = all good). */
    public static List<String> loadAll() {
        List<String> problems = new ArrayList<>();

        String rings = readOrCreate("rings.json", RingsConfig.DEFAULT_JSON, problems);
        if (rings != null) {
            problems.addAll(RingManager.load(rings));
        }

        String mech = readOrCreate("mechanics.json", MechanicsConfig.DEFAULT_JSON, problems);
        if (mech != null) {
            List<String> errors = new ArrayList<>();
            mechanics = MechanicsConfig.parse(mech, errors);
            errors.forEach(e -> problems.add("mechanics.json: " + e));
        }

        problems.addAll(checkIdsExist(mechanics));
        problems.forEach(p -> DistantFrontiers.LOGGER.warn("[config] {}", p));
        return problems;
    }

    /** Used by in-game tests to run a scenario with specific numbers. */
    public static void setMechanicsForTesting(MechanicsConfig config) {
        mechanics = config;
    }

    private static String readOrCreate(String name, String defaults, List<String> problems) {
        Path file = dir().resolve(name);
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, defaults);
                DistantFrontiers.LOGGER.info("Wrote default {}", file);
            }
            return Files.readString(file);
        } catch (IOException e) {
            problems.add("could not read " + name + ": " + e.getMessage() + " (keeping previous settings)");
            return null;
        }
    }

    private static List<String> checkIdsExist(MechanicsConfig config) {
        List<String> problems = new ArrayList<>();
        for (String id : config.digging().diggers()) {
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(id))) {
                problems.add("mechanics.json: digging.diggers lists " + id + ", which isn't in this pack");
            }
        }
        for (String id : config.hex().ambushMobs()) {
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(id))) {
                problems.add("mechanics.json: hex.ambushMobs lists " + id + ", which isn't in this pack");
            }
        }
        for (String id : config.digging().blockBlacklist()) {
            if (!BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(id))) {
                problems.add("mechanics.json: digging.blockBlacklist lists " + id + ", which isn't in this pack");
            }
        }
        return problems;
    }
}
