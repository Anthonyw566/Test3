package dev.anthonyw.furtherout.core;

/**
 * Your things wait with a zombie. Die away from spawn and, instead of
 * scattering on the floor and vanishing after five minutes, everything you
 * dropped is carried by a weak zombie wearing your head and your name. It
 * never despawns and never wanders far from where you died; kill it (or have
 * a friend kill it) and it all spills out, safe from lava and despawning.
 */
public final class KeeperRules {
    public enum Leash { STAY, WALK_HOME, GIVE_UP_CHASE }

    private KeeperRules() {
    }

    /** Does this death get a keeper? Not with nothing to keep, not in the void, not at home (by default). */
    public static boolean shouldKeep(MechanicsConfig.Keeper cfg, int dropCount, boolean inSafeArea, boolean belowWorld) {
        return cfg.enabled()
                && dropCount > 0
                && !belowWorld
                && (!inSafeArea || cfg.inSafeArea());
    }

    /**
     * Keeps the keeper near the spot where you died so it's easy to find:
     * it may chase you a little way, but never more than twice its leash.
     */
    public static Leash leash(double distanceFromHome, boolean chasing, double radius) {
        if (distanceFromHome > radius * 2) {
            return Leash.GIVE_UP_CHASE;
        }
        if (!chasing && distanceFromHome > radius) {
            return Leash.WALK_HOME;
        }
        return Leash.STAY;
    }
}
