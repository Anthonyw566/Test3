package dev.anthonyw.furtherout.core;

import java.util.List;

/** Ring resolution by distance over a sorted ring list (unbounded last). */
public final class RingLookup {
    private RingLookup() {
    }

    public static RingDef at(List<RingDef> sortedRings, double distance) {
        return at(sortedRings, distance, 1.0);
    }

    /**
     * Ring at a distance when every ring outside the safe zone is pulled in
     * to {@code scale} of its radius (at night). The safe zone never shrinks.
     */
    public static RingDef at(List<RingDef> sortedRings, double distance, double scale) {
        for (RingDef ring : sortedRings) {
            if (ring.unbounded() || distance <= outer(ring, scale)) {
                return ring;
            }
        }
        return sortedRings.isEmpty() ? null : sortedRings.get(sortedRings.size() - 1);
    }

    /** A ring's effective outer radius at the given scale (safe zones don't scale). */
    public static double outer(RingDef ring, double scale) {
        return ring.safeZone() ? ring.outerRadius() : ring.outerRadius() * scale;
    }

    public static RingDef byId(List<RingDef> rings, String id) {
        for (RingDef ring : rings) {
            if (ring.id().equals(id)) {
                return ring;
            }
        }
        return null;
    }

    /** Blocks until the current ring's outer boundary, or -1 in the unbounded ring. */
    public static double blocksToNext(List<RingDef> sortedRings, double distance) {
        return blocksToNext(sortedRings, distance, 1.0);
    }

    public static double blocksToNext(List<RingDef> sortedRings, double distance, double scale) {
        RingDef ring = at(sortedRings, distance, scale);
        if (ring == null || ring.unbounded()) {
            return -1;
        }
        return outer(ring, scale) - distance;
    }

    /** Inner radius of a ring = the previous bounded ring's outer radius. */
    public static double innerRadius(List<RingDef> sortedRings, String ringId) {
        double inner = 0;
        for (RingDef ring : sortedRings) {
            if (ring.id().equals(ringId)) {
                return inner;
            }
            if (!ring.unbounded()) {
                inner = ring.outerRadius();
            }
        }
        return inner;
    }
}
