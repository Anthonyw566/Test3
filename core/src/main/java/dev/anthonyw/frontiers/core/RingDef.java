package dev.anthonyw.frontiers.core;

import java.util.List;

/**
 * One configured difficulty ring, engine-agnostic. A ring covers distances up
 * to {@code outerRadius}; the previous ring's outer radius is its inner edge.
 * {@code outerRadius < 0} marks the unbounded outermost ring.
 */
public record RingDef(
        String id,
        String name,
        String colorName,
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
}
