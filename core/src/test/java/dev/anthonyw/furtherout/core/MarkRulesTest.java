package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkRulesTest {

    @Test
    void ticksDownOutsideAndStopsAtHome() {
        assertEquals(80, MarkRules.tick(100, false, 20));
        assertEquals(100, MarkRules.tick(100, true, 20), "you can't wait it out at home");
        assertEquals(0, MarkRules.tick(10, false, 20));
        assertEquals(0, MarkRules.tick(0, false, 20));
    }

    @Test
    void regainingNeverShortens() {
        assertEquals(3000, MarkRules.onGain(0, 3000));
        assertEquals(6000, MarkRules.onGain(6000, 3000));
    }

    @Test
    void passingRules() {
        assertTrue(MarkRules.canPass(100, 0, false, false, 50, 0));
        assertFalse(MarkRules.canPass(0, 0, false, false, 50, 0), "not marked");
        assertFalse(MarkRules.canPass(100, 0, true, false, 50, 0), "self");
        assertFalse(MarkRules.canPass(100, 0, false, true, 50, 0), "spectator");
        assertFalse(MarkRules.canPass(100, 30, false, false, 50, 0), "already marked");
        assertFalse(MarkRules.canPass(100, 0, false, false, 50, 60), "no tag-backs");
        assertTrue(MarkRules.canPass(100, 0, false, false, 60, 60), "immunity just ended");
    }

    @Test
    void jumpKeepsAtLeastTheMinimum() {
        assertEquals(900, MarkRules.jumpDuration(100, 900));
        assertEquals(3000, MarkRules.jumpDuration(3000, 900));
    }

    @Test
    void nearestWithinRange() {
        List<MarkRules.Candidate<String>> c = List.of(
                new MarkRules.Candidate<>("far", 40),
                new MarkRules.Candidate<>("near", 12),
                new MarkRules.Candidate<>("outOfRange", 5000));
        assertEquals("near", MarkRules.nearest(c, 48));
        assertNull(MarkRules.nearest(c, 10));
        assertNull(MarkRules.nearest(List.<MarkRules.Candidate<String>>of(), 48));
    }

    @Test
    void ambushesStaySmall() {
        assertEquals(2, MarkRules.ambushSize(2, 0));
        assertEquals(2, MarkRules.ambushSize(2, 1));
        assertEquals(3, MarkRules.ambushSize(2, 2));
        assertEquals(4, MarkRules.ambushSize(2, 4));
        assertEquals(0, MarkRules.ambushSize(0, -3));
    }

    @Test
    void ambushesNeverHitTheSafeAreaOrTheLastSeconds() {
        assertTrue(MarkRules.ambushDue(100, 100, 2000, false, 2));
        assertFalse(MarkRules.ambushDue(99, 100, 2000, false, 2), "not yet");
        assertFalse(MarkRules.ambushDue(100, 100, 2000, true, 0), "safe area");
        assertFalse(MarkRules.ambushDue(100, 100, 2000, false, 0), "danger 0");
        assertFalse(MarkRules.ambushDue(100, 100, MarkRules.NO_AMBUSH_TAIL_TICKS, false, 2), "about to fade");
    }

    @Test
    void defaultMarkKeepsTheAmbushesComing() {
        MechanicsConfig.Marked cfg = MechanicsConfig.defaults().marked();
        int remaining = cfg.durationTicks();
        long now = 0;
        long due = cfg.firstAmbushTicks();
        int ambushes = 0;
        while (remaining > 0) {
            if (MarkRules.ambushDue(now, due, remaining, false, 4)) {
                ambushes++;
                due = now + cfg.ambushEveryTicks();
            }
            now += 20;
            remaining = MarkRules.tick(remaining, false, 20);
        }
        assertTrue(ambushes >= 3 && ambushes <= 5, "ambushes per mark: " + ambushes);
    }

    @Test
    void rewardOnlyOutsideTheSafeArea() {
        assertTrue(MarkRules.earnsReward(false));
        assertFalse(MarkRules.earnsReward(true));
    }

    @Test
    void progressIsClamped() {
        assertEquals(1f, MarkRules.progress(3000, 3000));
        assertEquals(0.5f, MarkRules.progress(1500, 3000));
        assertEquals(1f, MarkRules.progress(6000, 3000));
        assertEquals(0f, MarkRules.progress(10, 0));
    }

    @Test
    void formatsTime() {
        assertEquals("4:00", MarkRules.formatTicks(4800));
        assertEquals("0:01", MarkRules.formatTicks(1));
        assertEquals("1:05", MarkRules.formatTicks(1300));
        assertEquals("0:00", MarkRules.formatTicks(0));
    }
}
