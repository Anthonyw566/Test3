package dev.anthonyw.frontiers.core;

/** Velocity for a Magnetic elite's pull: toward the mob, with a little lift so players actually fly. */
public final class Magnet {
    private Magnet() {
    }

    /**
     * @param dx,dy,dz vector from the player to the mob
     * @return {vx, vy, vz}
     */
    public static double[] pull(double dx, double dy, double dz, double strength, double lift) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vy = lift + Math.max(-0.2, Math.min(0.4, dy * 0.1));
        if (horizontal < 0.5) {
            return new double[]{0, vy, 0};
        }
        return new double[]{dx / horizontal * strength, vy, dz / horizontal * strength};
    }
}
