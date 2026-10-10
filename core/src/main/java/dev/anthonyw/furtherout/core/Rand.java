package dev.anthonyw.furtherout.core;

import java.util.Random;

/**
 * Tiny randomness seam so core logic never touches Minecraft's RandomSource.
 * The mod adapts RandomSource to this; tests use {@link #seeded(long)}.
 */
public interface Rand {
    int nextInt(int bound);

    double nextDouble();

    static Rand seeded(long seed) {
        Random random = new Random(seed);
        return new Rand() {
            @Override
            public int nextInt(int bound) {
                return random.nextInt(bound);
            }

            @Override
            public double nextDouble() {
                return random.nextDouble();
            }
        };
    }
}
