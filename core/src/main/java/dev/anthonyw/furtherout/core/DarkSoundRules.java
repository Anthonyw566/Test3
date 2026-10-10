package dev.anthonyw.furtherout.core;

/**
 * Alone in the dark, every so often, you hear something behind you that
 * nobody else hears: footsteps, a door, someone mining, a chest closing.
 * Nothing ever comes of it. It only happens to a player with no one else
 * nearby, in real darkness (a cave, not a moonlit field), away from spawn,
 * and many minutes apart.
 */
public final class DarkSoundRules {
    /** Relative weights of the sounds, in the order the mod lists them. */
    public static final int[] WEIGHTS = {3, 3, 3, 2, 2, 2, 1};

    private DarkSoundRules() {
    }

    public static boolean eligible(boolean alone, int brightness, boolean inSafeArea, boolean survival,
                                   MechanicsConfig.DarkSounds cfg) {
        return cfg.enabled() && alone && survival && !inSafeArea && brightness <= cfg.maxLight();
    }

    /** Ticks until the next one: somewhere between the min and max, never the same rhythm twice. */
    public static int nextDelay(Rand random, MechanicsConfig.DarkSounds cfg) {
        int span = Math.max(0, cfg.maxTicks() - cfg.minTicks());
        return cfg.minTicks() + (span == 0 ? 0 : random.nextInt(span + 1));
    }

    public static int pick(Rand random) {
        int total = 0;
        for (int w : WEIGHTS) {
            total += w;
        }
        int r = random.nextInt(total);
        for (int i = 0; i < WEIGHTS.length; i++) {
            r -= WEIGHTS[i];
            if (r < 0) {
                return i;
            }
        }
        return WEIGHTS.length - 1;
    }

    /**
     * {dx, dz} for a spot {@code distance} blocks behind someone looking along
     * {@code yawDegrees} (Minecraft yaw: 0 = +z, 90 = -x), nudged a little to one side.
     */
    public static double[] behind(float yawDegrees, double distance, double sideways) {
        double yaw = Math.toRadians(yawDegrees);
        double lookX = -Math.sin(yaw);
        double lookZ = Math.cos(yaw);
        return new double[]{-lookX * distance + lookZ * sideways, -lookZ * distance - lookX * sideways};
    }
}
