package dev.anthonyw.frontiers.core;

import java.util.List;

/**
 * One difficulty ring. A ring covers distances up to {@code outerRadius} from
 * the origin; the previous ring's outer radius is its inner edge, and
 * {@code outerRadius < 0} marks the endless outermost ring.
 *
 * @param safeZone      no natural hostile spawns, no block digging, Hex paused
 * @param digChance     chance a digger-type mob spawned here can tunnel to you
 * @param eliteLoot     loot table rolled when a player kills an elite from this ring ("" = none)
 */
public record RingDef(
        String id,
        String name,
        String colorName,
        int danger,
        double outerRadius,
        String entryMessage,
        boolean safeZone,
        double healthMult,
        double damageMult,
        double digChance,
        double eliteChance,
        double championChance,
        List<String> modifiers,
        String eliteLoot
) {
    public boolean unbounded() {
        return outerRadius < 0;
    }
}
