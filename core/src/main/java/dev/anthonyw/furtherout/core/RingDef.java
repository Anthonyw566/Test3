package dev.anthonyw.furtherout.core;

import java.util.List;

/**
 * One distance band. A ring covers distances up to {@code outerRadius} from
 * the origin; the previous ring's outer radius is its inner edge, and
 * {@code outerRadius < 0} marks the endless outermost ring. Players only ever
 * see the danger number ("Danger level 2") or "Safe area".
 *
 * @param safeZone      no natural hostile spawns and no digging
 * @param digChance     chance a digger-type mob spawned here can tunnel to you
 * @param eliteLoot     loot table rolled when a player kills an elite from this ring ("" = none)
 */
public record RingDef(
        String id,
        int danger,
        double outerRadius,
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
