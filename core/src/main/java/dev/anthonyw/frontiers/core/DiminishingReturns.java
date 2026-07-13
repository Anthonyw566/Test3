package dev.anthonyw.frontiers.core;

/**
 * Daily elite-kill payout curve: full value for the first 10, half for the
 * next 20, then nothing. Farms stop paying; expeditions never stop being
 * worth it.
 */
public final class DiminishingReturns {
    public static final int FULL_UNTIL = 10;
    public static final int HALF_UNTIL = 30;

    private DiminishingReturns() {
    }

    /** @param killsToday this kill's 1-based position in today's count */
    public static double multiplier(int killsToday) {
        if (killsToday <= FULL_UNTIL) {
            return 1.0;
        }
        if (killsToday <= HALF_UNTIL) {
            return 0.5;
        }
        return 0.0;
    }
}
