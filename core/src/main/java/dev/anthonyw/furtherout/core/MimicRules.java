package dev.anthonyw.furtherout.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bait. Deep in a dark cave, very rarely, something worth picking up is
 * lying on the floor - a diamond, a few gold ingots - except it twitches now
 * and then, and when you reach for it, it turns into a monster. The monster
 * is holding the bait, and drops it when it dies. A surprise, a short fight,
 * and you do get the diamond in the end.
 */
public final class MimicRules {
    public record Bait(String itemId, int count) {
    }

    private MimicRules() {
    }

    /**
     * Does this natural monster spawn become bait instead? Only far enough
     * out, only where a player is close enough to find it, and rarely.
     */
    public static boolean replaces(MechanicsConfig.Mimic cfg, int danger, boolean inSafeArea, boolean playerNear,
                                   double roll) {
        return cfg.enabled() && !inSafeArea && danger >= cfg.minDanger() && playerNear
                && !cfg.baits().isEmpty() && !cfg.mobs().isEmpty() && roll < cfg.chance();
    }

    public static boolean springs(double playerDistance, double triggerRadius) {
        return playerDistance <= triggerRadius;
    }

    /** "minecraft:gold_ingot*3" -> gold ingot x3. Bad entries are reported and skipped. */
    public static List<Bait> parseBaits(List<String> entries, List<String> errors) {
        List<Bait> out = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            String id = entry;
            int count = 1;
            int star = entry.indexOf('*');
            if (star >= 0) {
                id = entry.substring(0, star);
                try {
                    count = Integer.parseInt(entry.substring(star + 1));
                } catch (NumberFormatException e) {
                    count = -1;
                }
            }
            if (!Json.isId(id) || count < 1 || count > 64) {
                errors.add("mimic.baits: \"" + raw + "\" should look like minecraft:diamond or minecraft:gold_ingot*3");
                continue;
            }
            out.add(new Bait(id, count));
        }
        return List.copyOf(out);
    }
}
