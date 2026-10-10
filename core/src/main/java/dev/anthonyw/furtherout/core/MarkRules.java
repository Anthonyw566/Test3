package dev.anthonyw.furtherout.core;

import java.util.List;

/**
 * Being marked: killing an elite can mark you (champions always do). While
 * marked you glow, nearby monsters come for you, a couple of small ambushes
 * find you, and everything you kill drops double. Wait it out, or hit another
 * player to hand it over. If you die marked, it moves to the nearest player.
 *
 * Rules here are pure so they can be tested; the mod layer applies effects.
 */
public final class MarkRules {
    /** No ambush in the last stretch of a mark: it would land as the mark ends. */
    public static final int NO_AMBUSH_TAIL_TICKS = 200;

    private MarkRules() {
    }

    /** Remaining ticks after {@code dt}. The timer always runs, safe area included. */
    public static int tick(int remaining, int dt) {
        return Math.max(0, remaining - dt);
    }

    /** Being marked again never shortens it. */
    public static int onGain(int current, int duration) {
        return Math.max(current, duration);
    }

    /**
     * Can a marked attacker pass the mark to the player they just hit?
     * Not to themselves, not to spectators, not to someone already marked, and
     * not back to someone who passed it within the immunity window.
     */
    public static boolean canPass(int attackerRemaining, int targetRemaining, boolean samePlayer,
                                  boolean targetSpectator, long now, long targetImmuneUntil) {
        return attackerRemaining > 0
                && !samePlayer
                && !targetSpectator
                && targetRemaining <= 0
                && now >= targetImmuneUntil;
    }

    /** When the holder dies, the mark moves on with at least {@code minJump} ticks left. */
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

    /** Ambush size: the base, plus one more for every two danger levels. */
    public static int ambushSize(int baseSize, int danger) {
        return Math.max(0, baseSize + Math.max(0, danger) / 2);
    }

    /**
     * Is an ambush due? Only outside the safe area, never in the last few
     * seconds of the mark, and only once the scheduled time has come.
     */
    public static boolean ambushDue(long now, long due, int remaining, boolean safe, int danger) {
        return !safe && danger > 0 && remaining > NO_AMBUSH_TAIL_TICKS && now >= due;
    }

    /** The mark only pays out if it runs out while you're outside the safe area. */
    public static boolean earnsReward(boolean endedInSafeArea) {
        return !endedInSafeArea;
    }

    /** Boss bar fill, 1 = just marked, 0 = about to fade. */
    public static float progress(int remaining, int total) {
        if (total <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, remaining / (float) total));
    }

    /** "3:05" */
    public static String formatTicks(int ticks) {
        int seconds = Math.max(0, (ticks + 19) / 20);
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }
}
