package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseRulesTest {
    private static final MechanicsConfig.Noise CFG = MechanicsConfig.defaults().noise();

    @Test
    void explosionsCarryFurtherThanFights() {
        assertTrue(NoiseRules.radius(NoiseRules.Kind.EXPLOSION, CFG) > NoiseRules.radius(NoiseRules.Kind.COMBAT, CFG));
        assertEquals(12, NoiseRules.radius(NoiseRules.Kind.COMBAT, CFG), 1e-9);
    }

    @Test
    void onlyIdleMonstersInRangeOutsideTheSafeAreaListen() {
        assertTrue(NoiseRules.hears(10, 12, false, false));
        assertFalse(NoiseRules.hears(13, 12, false, false), "too far");
        assertFalse(NoiseRules.hears(5, 12, true, false), "already busy with someone");
        assertFalse(NoiseRules.hears(5, 12, false, true), "the safe area is quiet");
    }

    @Test
    void fightsAreNoisyOnlyEveryFewSeconds() {
        assertTrue(NoiseRules.combatCooledDown(100, -1_000_000, CFG.combatCooldownTicks()));
        assertFalse(NoiseRules.combatCooledDown(150, 100, CFG.combatCooldownTicks()));
        assertTrue(NoiseRules.combatCooledDown(200, 100, CFG.combatCooldownTicks()));
    }
}
