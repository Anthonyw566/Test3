package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnulusPointTest {

    @Test
    void pointsStayInsideTheRingWithMargin() {
        double inner = 400;
        double outer = 1200; // width 800 -> margin 150 -> radius in [550, 1050]
        for (long seed = 0; seed < 2000; seed++) {
            double[] offset = AnnulusPoint.roll(Rand.seeded(seed), inner, outer);
            double radius = Math.hypot(offset[0], offset[1]);
            assertTrue(radius >= 549.99, "too close to inner edge: " + radius);
            assertTrue(radius <= 1050.01, "too close to outer edge: " + radius);
        }
    }

    @Test
    void narrowRingsUseQuarterWidthMargin() {
        double inner = 100;
        double outer = 200; // width 100 -> margin 25 -> radius in [125, 175]
        for (long seed = 0; seed < 500; seed++) {
            double radius = Math.hypot(
                    AnnulusPoint.roll(Rand.seeded(seed), inner, outer)[0],
                    AnnulusPoint.roll(Rand.seeded(seed), inner, outer)[1]);
            assertTrue(radius >= 124.99 && radius <= 175.01, "radius out of band: " + radius);
        }
    }

    @Test
    void anglesCoverTheWholeCircle() {
        boolean posX = false;
        boolean negX = false;
        boolean posZ = false;
        boolean negZ = false;
        for (long seed = 0; seed < 500; seed++) {
            double[] offset = AnnulusPoint.roll(Rand.seeded(seed), 400, 1200);
            posX |= offset[0] > 0;
            negX |= offset[0] < 0;
            posZ |= offset[1] > 0;
            negZ |= offset[1] < 0;
        }
        assertTrue(posX && negX && posZ && negZ, "caches cluster in one direction");
    }
}
