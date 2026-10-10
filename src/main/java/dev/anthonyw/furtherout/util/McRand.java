package dev.anthonyw.furtherout.util;

import dev.anthonyw.furtherout.core.Rand;
import net.minecraft.util.RandomSource;

/** Adapts Minecraft's RandomSource to the core module's Rand seam. */
public record McRand(RandomSource source) implements Rand {
    @Override
    public int nextInt(int bound) {
        return source.nextInt(bound);
    }

    @Override
    public double nextDouble() {
        return source.nextDouble();
    }
}
