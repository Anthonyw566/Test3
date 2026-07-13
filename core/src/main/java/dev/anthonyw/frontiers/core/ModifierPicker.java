package dev.anthonyw.frontiers.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Draws elite/champion modifier sets: categories must differ and banned pairs
 * never roll, so champions are spicy but always fair.
 */
public final class ModifierPicker {
    private ModifierPicker() {
    }

    public static List<EliteModifier> pick(List<EliteModifier> pool, int count, Rand random) {
        List<EliteModifier> remaining = new ArrayList<>(pool);
        List<EliteModifier> out = new ArrayList<>();
        EnumSet<EliteModifier.Category> usedCategories = EnumSet.noneOf(EliteModifier.Category.class);
        while (out.size() < count && !remaining.isEmpty()) {
            EliteModifier candidate = remaining.remove(random.nextInt(remaining.size()));
            if (usedCategories.contains(candidate.category())) {
                continue;
            }
            if (out.stream().anyMatch(existing -> banned(existing, candidate))) {
                continue;
            }
            out.add(candidate);
            usedCategories.add(candidate.category());
        }
        return out;
    }

    /** Pairs that are individually fine but miserable together. */
    public static boolean banned(EliteModifier a, EliteModifier b) {
        return (a == EliteModifier.SUMMONER && b == EliteModifier.VENGEFUL)
                || (a == EliteModifier.VENGEFUL && b == EliteModifier.SUMMONER);
    }
}
