package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeatMathTest {

    @Test
    void levelsMatchThresholds() {
        assertEquals(HeatMath.Level.CALM, HeatMath.levelFor(0));
        assertEquals(HeatMath.Level.CALM, HeatMath.levelFor(24.9f));
        assertEquals(HeatMath.Level.RESTLESS, HeatMath.levelFor(25));
        assertEquals(HeatMath.Level.RESTLESS, HeatMath.levelFor(49.9f));
        assertEquals(HeatMath.Level.HUNTED, HeatMath.levelFor(50));
        assertEquals(HeatMath.Level.MARKED, HeatMath.levelFor(75));
        assertEquals(HeatMath.Level.MARKED, HeatMath.levelFor(100));
    }

    @Test
    void clampKeepsHeatInRange() {
        assertEquals(0, HeatMath.clamp(-5));
        assertEquals(100, HeatMath.clamp(140));
        assertEquals(42, HeatMath.clamp(42));
    }

    @Test
    void bankMultiplierScalesToPlusFiftyPercent() {
        assertEquals(1.0, HeatMath.bankMultiplier(0), 1e-9);
        assertEquals(1.25, HeatMath.bankMultiplier(50), 1e-9);
        assertEquals(1.5, HeatMath.bankMultiplier(100), 1e-9);
        assertEquals(1.5, HeatMath.bankMultiplier(400), 1e-9, "over-max heat must clamp");
    }

    @Test
    void killHeatByTier() {
        assertEquals(5, HeatMath.killHeat(1));
        assertEquals(10, HeatMath.killHeat(2));
        assertEquals(10, HeatMath.killHeat(3));
    }

    @Test
    void gainPerSecondConverts() {
        assertEquals(0.05f, HeatMath.gainPerSecond(3.0), 1e-6);
        assertEquals(0f, HeatMath.gainPerSecond(0), 1e-9);
    }

    @Test
    void deathRuleBanksHalf() {
        assertEquals(0.5, HeatMath.DEATH_BANK_FRACTION);
    }
}
