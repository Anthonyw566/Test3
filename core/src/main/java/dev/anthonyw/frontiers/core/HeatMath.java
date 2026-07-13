package dev.anthonyw.frontiers.core;

/**
 * All Heat numbers in one place: thresholds, labels, the cash-out bonus and
 * the death rule. Keeping this pure means the extraction economy is fully
 * unit-tested.
 */
public final class HeatMath {
    public static final float MAX = 100;
    public static final float RESTLESS = 25;
    public static final float HUNTED = 50;
    public static final float MARKED = 75;

    /** Fraction of field Marks that survive a death. */
    public static final double DEATH_BANK_FRACTION = 0.5;

    public enum Level {
        CALM("Calm"), RESTLESS("Restless"), HUNTED("Hunted"), MARKED("Marked");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private HeatMath() {
    }

    public static float clamp(float heat) {
        return Math.max(0f, Math.min(MAX, heat));
    }

    public static Level levelFor(float heat) {
        if (heat >= MARKED) {
            return Level.MARKED;
        }
        if (heat >= HUNTED) {
            return Level.HUNTED;
        }
        if (heat >= RESTLESS) {
            return Level.RESTLESS;
        }
        return Level.CALM;
    }

    /** Cash-out multiplier: 1.0 at zero Heat, up to 1.5 fully Marked. */
    public static double bankMultiplier(float heat) {
        return 1.0 + clamp(heat) / 200.0;
    }

    /** Heat spike for killing an elite (tier 1) or champion (tier 2+). */
    public static float killHeat(int tier) {
        return tier >= 2 ? 10 : 5;
    }

    /** Per-second gain from a ring's per-minute config rate. */
    public static float gainPerSecond(double gainPerMinute) {
        return (float) (gainPerMinute / 60.0);
    }
}
