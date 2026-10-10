package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DarkSoundRulesTest {
    private static final MechanicsConfig.DarkSounds CFG = MechanicsConfig.defaults().darkSounds();

    @Test
    void onlyAloneInRealDarknessAwayFromSpawn() {
        assertTrue(DarkSoundRules.eligible(true, 0, false, true, CFG));
        assertFalse(DarkSoundRules.eligible(false, 0, false, true, CFG), "a friend nearby breaks the spell");
        assertFalse(DarkSoundRules.eligible(true, 4, false, true, CFG), "moonlight is too bright");
        assertFalse(DarkSoundRules.eligible(true, 0, true, true, CFG), "never at home");
        assertFalse(DarkSoundRules.eligible(true, 0, false, false, CFG), "not for creative or spectators");
    }

    @Test
    void theyAreMinutesApart() {
        Rand r = Rand.seeded(5);
        for (int i = 0; i < 1000; i++) {
            int d = DarkSoundRules.nextDelay(r, CFG);
            assertTrue(d >= CFG.minTicks() && d <= CFG.maxTicks());
        }
        assertTrue(CFG.minTicks() >= 20 * 60 * 5, "rare: at least five minutes apart by default");
    }

    @Test
    void everySoundCanComeUp() {
        int[] seen = new int[DarkSoundRules.WEIGHTS.length];
        Rand r = Rand.seeded(9);
        for (int i = 0; i < 5000; i++) {
            seen[DarkSoundRules.pick(r)]++;
        }
        for (int count : seen) {
            assertTrue(count > 0);
        }
        assertTrue(seen[seen.length - 1] < seen[0], "the scariest one is the rarest");
    }

    @Test
    void behindMeansBehind() {
        for (float yaw : new float[]{0, 90, 180, -45, 271}) {
            double[] d = DarkSoundRules.behind(yaw, 6, 0);
            double lookX = -Math.sin(Math.toRadians(yaw));
            double lookZ = Math.cos(Math.toRadians(yaw));
            assertEquals(-6, d[0] * lookX + d[1] * lookZ, 1e-9, "yaw " + yaw);
        }
    }

    @Test
    void canBeSwitchedOff() {
        MechanicsConfig.DarkSounds off = MechanicsConfig.parse("{ \"darkSounds\": { \"enabled\": false } }",
                new ArrayList<>()).darkSounds();
        assertFalse(DarkSoundRules.eligible(true, 0, false, true, off));
    }
}
