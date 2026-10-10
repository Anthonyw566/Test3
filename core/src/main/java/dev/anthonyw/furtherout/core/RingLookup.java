package dev.anthonyw.furtherout.core;

import java.util.List;

/** Ring resolution by distance over a sorted ring list (unbounded last). */
public final class RingLookup {
    private RingLookup() {
    }

    public static RingDef at(List<RingDef> sortedRings, double distance) {
        for (RingDef ring : sortedRings) {
            if (ring.unbounded() || distance <= ring.outerRadius()) {
                return ring;
            }
        }
        return sortedRings.isEmpty() ? null : sortedRings.get(sortedRings.size() - 1);
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
        RingDef ring = at(sortedRings, distance);
        if (ring == null || ring.unbounded()) {
            return -1;
        }
        return ring.outerRadius() - distance;
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
