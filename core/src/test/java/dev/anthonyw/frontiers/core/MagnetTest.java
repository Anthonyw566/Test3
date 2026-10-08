package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MagnetTest {

    @Test
    void pullsTowardTheMobAtFullStrength() {
        double[] v = Magnet.pull(8, 0, 0, 1.1, 0.35);
        assertEquals(1.1, v[0], 1e-9);
        assertEquals(0.0, v[2], 1e-9);
        assertEquals(0.35, v[1], 1e-9);
    }

    @Test
    void diagonalPullIsNormalized() {
        double[] v = Magnet.pull(-6, 0, 6, 1.0, 0.0);
        assertEquals(1.0, Math.hypot(v[0], v[2]), 1e-9);
        assertTrue(v[0] < 0 && v[2] > 0);
    }

    @Test
    void verticalComponentIsClamped() {
        assertEquals(0.35 + 0.4, Magnet.pull(5, 30, 0, 1, 0.35)[1], 1e-9);
        assertEquals(0.35 - 0.2, Magnet.pull(5, -30, 0, 1, 0.35)[1], 1e-9);
    }

    @Test
    void standingOnTopOfItJustLifts() {
        double[] v = Magnet.pull(0.1, 0, 0.1, 1.1, 0.35);
        assertEquals(0.0, v[0]);
        assertEquals(0.0, v[2]);
    }
}
