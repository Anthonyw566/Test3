package dev.anthonyw.furtherout.core;

import java.util.List;

/**
 * The Warped Ender Pearl, a rare drop from Warping champions. Thrown, it
 * swaps you with whatever living thing is closest to where it lands - a
 * friend you want to pull out of trouble, or a skeleton. If nothing is close
 * enough it works like a normal pearl. The catch is that you don't get to
 * choose what's closest.
 */
public final class PearlRules {
    private PearlRules() {
    }

    public record Candidate<T>(T who, double distance) {
    }

    /** The closest candidate within {@code radius} of the landing spot, or null (plain pearl). */
    public static <T> T swapTarget(List<Candidate<T>> candidates, double radius) {
        Candidate<T> best = null;
        for (Candidate<T> c : candidates) {
            if (c.distance() <= radius && (best == null || c.distance() < best.distance())) {
                best = c;
            }
        }
        return best == null ? null : best.who();
    }

    /** Only Warping champions drop it, and only sometimes. */
    public static boolean drops(boolean champion, boolean warping, double roll, double chance) {
        return champion && warping && roll < chance;
    }
}
