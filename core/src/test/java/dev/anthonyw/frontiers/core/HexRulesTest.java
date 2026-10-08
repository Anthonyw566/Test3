package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexRulesTest {

    @Test
    void ticksDownOutsideAndFreezesAtHome() {
        assertEquals(80, HexRules.tick(100, false, 20));
        assertEquals(100, HexRules.tick(100, true, 20));
        assertEquals(0, HexRules.tick(10, false, 20));
        assertEquals(0, HexRules.tick(0, false, 20));
    }

    @Test
    void regainingNeverShortens() {
        assertEquals(4800, HexRules.onGain(0, 4800));
        assertEquals(6000, HexRules.onGain(6000, 4800));
    }

    @Test
    void passingRules() {
        assertTrue(HexRules.canPass(100, 0, false, false, 50, 0));
        assertFalse(HexRules.canPass(0, 0, false, false, 50, 0), "not hexed");
        assertFalse(HexRules.canPass(100, 0, true, false, 50, 0), "self");
        assertFalse(HexRules.canPass(100, 0, false, true, 50, 0), "spectator");
        assertFalse(HexRules.canPass(100, 30, false, false, 50, 0), "already hexed");
        assertFalse(HexRules.canPass(100, 0, false, false, 50, 60), "no tag-backs");
        assertTrue(HexRules.canPass(100, 0, false, false, 60, 60), "immunity just ended");
    }

    @Test
    void jumpKeepsAtLeastAMinute() {
        assertEquals(1200, HexRules.jumpDuration(100, 1200));
        assertEquals(3000, HexRules.jumpDuration(3000, 1200));
    }

    @Test
    void nearestWithinRange() {
        List<HexRules.Candidate<String>> c = List.of(
                new HexRules.Candidate<>("far", 40),
                new HexRules.Candidate<>("near", 12),
                new HexRules.Candidate<>("outOfRange", 5000));
        assertEquals("near", HexRules.nearest(c, 48));
        assertNull(HexRules.nearest(c, 10));
        assertNull(HexRules.nearest(List.<HexRules.Candidate<String>>of(), 48));
    }

    @Test
    void ambushGrowsWithDanger() {
        assertEquals(2, HexRules.ambushSize(2, 0));
        assertEquals(6, HexRules.ambushSize(2, 4));
        assertEquals(0, HexRules.ambushSize(0, -3));
    }

    @Test
    void formatsTime() {
        assertEquals("4:00", HexRules.formatTicks(4800));
        assertEquals("0:01", HexRules.formatTicks(1));
        assertEquals("1:05", HexRules.formatTicks(1300));
        assertEquals("0:00", HexRules.formatTicks(0));
    }
}
