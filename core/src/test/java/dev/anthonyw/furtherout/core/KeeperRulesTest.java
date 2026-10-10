package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeeperRulesTest {
    private static final MechanicsConfig.Keeper CFG = MechanicsConfig.defaults().keeper();

    @Test
    void deathsAwayFromSpawnGetAKeeper() {
        assertTrue(KeeperRules.shouldKeep(CFG, 12, false, false));
    }

    @Test
    void nothingToKeepMeansNoKeeper() {
        assertFalse(KeeperRules.shouldKeep(CFG, 0, false, false), "keepInventory, or a grave mod took it");
    }

    @Test
    void homeAndTheVoidAreLeftAlone() {
        assertFalse(KeeperRules.shouldKeep(CFG, 5, true, false), "no monsters at home by default");
        assertFalse(KeeperRules.shouldKeep(CFG, 5, false, true), "nothing to stand on in the void");
        MechanicsConfig.Keeper everywhere = MechanicsConfig.parse("{ \"keeper\": { \"inSafeArea\": true } }",
                new ArrayList<>()).keeper();
        assertTrue(KeeperRules.shouldKeep(everywhere, 5, true, false));
    }

    @Test
    void canBeSwitchedOff() {
        MechanicsConfig.Keeper off = MechanicsConfig.parse("{ \"keeper\": { \"enabled\": false } }",
                new ArrayList<>()).keeper();
        assertFalse(KeeperRules.shouldKeep(off, 5, false, false));
    }

    @Test
    void staysNearWhereYouDied() {
        assertEquals(KeeperRules.Leash.STAY, KeeperRules.leash(3, false, 8));
        assertEquals(KeeperRules.Leash.STAY, KeeperRules.leash(12, true, 8), "a short chase is fine");
        assertEquals(KeeperRules.Leash.WALK_HOME, KeeperRules.leash(12, false, 8));
        assertEquals(KeeperRules.Leash.GIVE_UP_CHASE, KeeperRules.leash(17, true, 8));
    }

    @Test
    void defaultKeeperIsWeak() {
        assertTrue(CFG.health() <= 12, "you come back with nothing; it must be beatable bare-handed");
        assertTrue(CFG.attackDamage() <= 2);
    }
}
