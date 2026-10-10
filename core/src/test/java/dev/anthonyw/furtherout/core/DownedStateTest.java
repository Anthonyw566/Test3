package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownedStateTest {
    private static final MechanicsConfig.Downed CFG = MechanicsConfig.defaults().downed();
    // bleed 900 ticks, revive 80, give up 60, alone x4, 10 ticks lost per damage point

    private static DownedState.Result run(DownedState s, int ticks, boolean reviving, boolean crouch,
                                          boolean rescuer) {
        DownedState.Result result = DownedState.Result.CONTINUE;
        for (int t = 0; t < ticks && result == DownedState.Result.CONTINUE; t += 5) {
            result = s.tick(5, reviving, crouch, rescuer, CFG);
        }
        return result;
    }

    @Test
    void reviveTakesFourSeconds() {
        DownedState s = new DownedState(CFG.bleedOutTicks());
        assertEquals(DownedState.Result.CONTINUE, run(s, 75, true, false, true));
        assertEquals(DownedState.Result.REVIVED, s.tick(5, true, false, true, CFG));
    }

    @Test
    void bleedsOutWithoutHelp() {
        DownedState s = new DownedState(CFG.bleedOutTicks());
        assertEquals(DownedState.Result.CONTINUE, run(s, 895, false, false, true));
        assertEquals(DownedState.Result.BLED_OUT, s.tick(5, false, false, true, CFG));
    }

    @Test
    void aloneBleedsFourTimesFaster() {
        DownedState s = new DownedState(CFG.bleedOutTicks());
        assertEquals(DownedState.Result.BLED_OUT, run(s, 230, false, false, false));
    }

    @Test
    void bleedingPausesDuringRevive() {
        DownedState s = new DownedState(100);
        run(s, 60, true, false, true);
        assertEquals(100, s.bleedTicks());
    }

    @Test
    void reviveProgressDecaysWhenReviverLeaves() {
        DownedState s = new DownedState(CFG.bleedOutTicks());
        run(s, 40, true, false, true);
        assertEquals(0.5, s.reviveProgress(CFG), 1e-9);
        run(s, 10, false, false, true);
        assertEquals(0.25, s.reviveProgress(CFG), 1e-9);
    }

    @Test
    void hitsCostTimeAndResetRevive() {
        DownedState s = new DownedState(CFG.bleedOutTicks());
        run(s, 40, true, false, true);
        s.onDamage(6f, CFG); // 6 damage -> 60 ticks
        assertEquals(CFG.bleedOutTicks() - 60, s.bleedTicks());
        assertEquals(0.0, s.reviveProgress(CFG));
    }

    @Test
    void tinyHitsStillCostOneSecond() {
        DownedState s = new DownedState(CFG.bleedOutTicks());
        s.onDamage(0.1f, CFG);
        assertEquals(CFG.bleedOutTicks() - 20, s.bleedTicks());
    }

    @Test
    void crouchingGivesUp() {
        DownedState s = new DownedState(CFG.bleedOutTicks());
        assertEquals(DownedState.Result.GAVE_UP, run(s, 60, false, true, true));
    }

    @Test
    void releasingCrouchCancelsGiveUp() {
        DownedState s = new DownedState(CFG.bleedOutTicks());
        run(s, 40, false, true, true);
        s.tick(5, false, false, true, CFG);
        assertEquals(0.0, s.giveUpProgress(CFG));
    }

    @Test
    void beingRevivedOverridesGiveUp() {
        DownedState s = new DownedState(CFG.bleedOutTicks());
        assertEquals(DownedState.Result.REVIVED, run(s, 200, true, true, true));
    }
}
