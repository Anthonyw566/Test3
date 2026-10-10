package dev.anthonyw.furtherout.core;

/**
 * Noise draws monsters. An explosion, or a fight, makes idle monsters nearby
 * come over to look - a little background pressure: blasting through a cave
 * or fighting a crowd pulls in the neighbours. Monsters already busy with a
 * target ignore it, the safe area is silent, and each player's fights only
 * make noise every few seconds.
 */
public final class NoiseRules {
    public enum Kind { EXPLOSION, COMBAT }

    /** How many listeners get a groan as the "something's coming" cue. */
    public static final int CUES_PER_NOISE = 2;

    private NoiseRules() {
    }

    public static double radius(Kind kind, MechanicsConfig.Noise cfg) {
        return kind == Kind.EXPLOSION ? cfg.explosionRadius() : cfg.combatRadius();
    }

    /** Does a monster this far away come and look? */
    public static boolean hears(double distance, double radius, boolean busy, boolean inSafeArea) {
        return !busy && !inSafeArea && distance <= radius;
    }

    /** Fights are noisy at most once per cooldown per player, so a long fight isn't a magnet. */
    public static boolean combatCooledDown(long now, long lastNoise, int cooldownTicks) {
        return now - lastNoise >= cooldownTicks;
    }
}
