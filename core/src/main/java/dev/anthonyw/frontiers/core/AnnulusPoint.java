package dev.anthonyw.frontiers.core;

/**
 * Rolls a random point inside a ring's annulus (between inner and outer
 * radius), keeping a margin from both boundaries so caches never sit exactly
 * on a ring line. Returns offsets from the origin: {dx, dz}.
 */
public final class AnnulusPoint {
    private AnnulusPoint() {
    }

    public static double[] roll(Rand random, double innerRadius, double outerRadius) {
        double width = outerRadius - innerRadius;
        double margin = Math.min(150, width / 4);
        double radius = innerRadius + margin + random.nextDouble() * (width - 2 * margin);
        double angle = random.nextDouble() * Math.PI * 2;
        return new double[]{Math.cos(angle) * radius, Math.sin(angle) * radius};
    }
}
