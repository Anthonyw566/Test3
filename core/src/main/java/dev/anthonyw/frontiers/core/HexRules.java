package dev.anthonyw.frontiers.core;

import java.util.List;

/**
 * The Hex: a curse that falls on whoever kills an elite. While hexed you glow,
 * every hostile nearby comes for you, ambushes keep arriving, and everything
 * you kill drops double. Hold it to the end for a payout - or punch a friend
 * to make it their problem. If you die with it, it jumps to the nearest
 * player.
 *
 * Rules here are pure so they can be tested; the mod layer applies effects.
 */
public final class HexRules {
    private HexRules() {
    }

    /** Remaining ticks after {@code dt}; the timer is frozen inside the safe zone. */
    public static int tick(int remaining, boolean inSafeZone, int dt) {
        if (remaining <= 0 || inSafeZone) {
            return Math.max(0, remaining);
        }
        return Math.max(0, remaining - dt);
    }

    /** Gaining the Hex again never shortens it. */
    public static int onGain(int current, int duration) {
        return Math.max(current, duration);
    }

    /**
     * Can a hexed attacker pass the curse to the player they just hit?
     * Not to themselves, not to spectators, not to someone already hexed, and
     * not back to someone who passed it within the immunity window.
     */
    public static boolean canPass(int attackerRemaining, int targetRemaining, boolean sameplayer,
                                  boolean targetSpectator, long now, long targetImmuneUntil) {
        return attackerRemaining > 0
                && !sameplayer
                && !targetSpectator
                && targetRemaining <= 0
                && now >= targetImmuneUntil;
    }

    /** When the holder dies, the Hex jumps with at least {@code minJump} ticks left. */
    public static int jumpDuration(int remaining, int minJump) {
        return Math.max(remaining, minJump);
    }

    public record Candidate<T>(T who, double distance) {
    }

    /** Nearest candidate within range, or null. */
    public static <T> T nearest(List<Candidate<T>> candidates, double range) {
        Candidate<T> best = null;
        for (Candidate<T> c : candidates) {
            if (c.distance() <= range && (best == null || c.distance() < best.distance())) {
                best = c;
            }
        }
        return best == null ? null : best.who();
    }

    /** Ambush pack size for a ring's danger level. */
    public static int ambushSize(int baseSize, int danger) {
        return Math.max(0, baseSize + Math.max(0, danger));
    }

    /** "3:05" */
    public static String formatTicks(int ticks) {
        int seconds = Math.max(0, (ticks + 19) / 20);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
