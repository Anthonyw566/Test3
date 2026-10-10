package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PearlRulesTest {
    @Test
    void swapsWithWhateverIsClosest() {
        List<PearlRules.Candidate<String>> near = List.of(
                new PearlRules.Candidate<>("friend", 2.5),
                new PearlRules.Candidate<>("skeleton", 1.2));
        assertEquals("skeleton", PearlRules.swapTarget(near, 4), "that's the catch");
    }

    @Test
    void nothingCloseMeansAPlainPearl() {
        assertNull(PearlRules.swapTarget(List.of(new PearlRules.Candidate<>("far", 9.0)), 4));
        assertNull(PearlRules.swapTarget(List.<PearlRules.Candidate<String>>of(), 4));
    }

    @Test
    void onlyWarpingChampionsDropIt() {
        double chance = MechanicsConfig.defaults().elites().warpedPearlChance();
        assertTrue(chance > 0 && chance < 1);
        assertTrue(PearlRules.drops(true, true, 0.0, chance));
        assertFalse(PearlRules.drops(false, true, 0.0, chance), "plain elites don't");
        assertFalse(PearlRules.drops(true, false, 0.0, chance), "other champions don't");
        assertFalse(PearlRules.drops(true, true, 0.99, chance), "and not every time");
    }
}
