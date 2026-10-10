package dev.anthonyw.furtherout.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Chooses ability sets for elites (1 ability) and champions (2), and parses
 * ability lists typed into admin commands.
 */
public final class ModifierPicker {
    private ModifierPicker() {
    }

    /** Draws up to {@code count} distinct abilities from the pool, never a banned pair. */
    public static List<EliteModifier> pick(List<EliteModifier> pool, int count, Rand random) {
        List<EliteModifier> remaining = new ArrayList<>(pool);
        List<EliteModifier> out = new ArrayList<>();
        while (out.size() < count && !remaining.isEmpty()) {
            EliteModifier candidate = remaining.remove(random.nextInt(remaining.size()));
            if (out.contains(candidate) || out.stream().anyMatch(e -> banned(e, candidate))) {
                continue;
            }
            out.add(candidate);
        }
        return out;
    }

    /**
     * A thief that also warps would steal your item and then teleport you
     * away from it - an unwinnable chase, so the pair never rolls.
     */
    public static boolean banned(EliteModifier a, EliteModifier b) {
        return (a == EliteModifier.WARPER && b == EliteModifier.THIEF)
                || (a == EliteModifier.THIEF && b == EliteModifier.WARPER);
    }

    /** Resolves a ring's configured pool; "*" means every ability. */
    public static List<EliteModifier> resolvePool(List<String> ids) {
        List<EliteModifier> pool = new ArrayList<>();
        for (String id : ids) {
            if ("*".equals(id)) {
                return Arrays.asList(EliteModifier.values());
            }
            EliteModifier modifier = EliteModifier.byId(id);
            if (modifier != null && !pool.contains(modifier)) {
                pool.add(modifier);
            }
        }
        return pool;
    }

    public record Parsed(List<EliteModifier> modifiers, List<String> unknown) {
    }

    /** Parses "warper thief" / "warper,volatile" from a command. */
    public static Parsed parse(String input) {
        List<EliteModifier> mods = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        if (input != null) {
            for (String token : input.trim().split("[\\s,]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                EliteModifier modifier = EliteModifier.byId(token);
                if (modifier == null) {
                    unknown.add(token);
                } else if (!mods.contains(modifier)) {
                    mods.add(modifier);
                }
            }
        }
        return new Parsed(List.copyOf(mods), List.copyOf(unknown));
    }
}
