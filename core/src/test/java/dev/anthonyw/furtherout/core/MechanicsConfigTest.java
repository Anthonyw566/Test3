package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MechanicsConfigTest {

    @Test
    void shippedDefaultIsClean() {
        List<String> errors = new ArrayList<>();
        MechanicsConfig c = MechanicsConfig.parse(MechanicsConfig.DEFAULT_JSON, errors);
        assertTrue(errors.isEmpty(), "default mechanics.json must have zero errors: " + errors);
        assertEquals(240, c.warper().cooldownTicks());
        assertEquals(400, c.warper().riftTicks());
        assertEquals(900, c.downed().bleedOutTicks());
        assertEquals(80, c.downed().reviveTicks());
        assertEquals(10.0, c.downed().ticksLostPerDamage(), 1e-9);
        assertEquals(3000, c.marked().durationTicks());
        assertTrue(c.digging().naturalBlocksOnly());
        assertTrue(c.digging().baseRadius() > 0);
        assertTrue(c.digging().diggers().contains("minecraft:zombie"));
        assertTrue(c.digging().blockBlacklist().contains("minecraft:obsidian"));
    }

    @Test
    void emptyFileMeansDefaults() {
        List<String> errors = new ArrayList<>();
        assertEquals(MechanicsConfig.defaults(), MechanicsConfig.parse("{}", errors));
        assertTrue(errors.isEmpty());
    }

    @Test
    void partialOverrideKeepsOtherDefaults() {
        List<String> errors = new ArrayList<>();
        MechanicsConfig c = MechanicsConfig.parse("{ \"warper\": { \"procChance\": 0.5 } }", errors);
        assertTrue(errors.isEmpty());
        assertEquals(0.5, c.warper().procChance());
        assertEquals(MechanicsConfig.defaults().warper().swapWeight(), c.warper().swapWeight());
        assertEquals(MechanicsConfig.defaults().marked(), c.marked());
    }

    @Test
    void badValuesAreReportedAndDefaulted() {
        List<String> errors = new ArrayList<>();
        MechanicsConfig c = MechanicsConfig.parse("""
                { "warper": { "procChance": 4 }, "downed": { "enabled": "yes" },
                  "marked": { "ambushMobs": ["zombie"] } }""", errors);
        assertEquals(3, errors.size(), errors.toString());
        assertEquals(1.0, c.warper().procChance());
        assertTrue(c.downed().enabled());
        assertTrue(c.marked().ambushMobs().isEmpty(), "malformed ids are dropped, not kept");
    }

    @Test
    void invalidJsonFallsBackToDefaults() {
        List<String> errors = new ArrayList<>();
        assertEquals(MechanicsConfig.defaults(), MechanicsConfig.parse("{{{", errors));
        assertFalse(errors.isEmpty());
    }

    @Test
    void annoyanceGuardsAreOnByDefault() {
        MechanicsConfig c = MechanicsConfig.defaults();
        assertTrue(c.warper().cooldownTicks() >= 200, "warps need a real recharge");
        assertTrue(c.volatileAbility().maxDamage() <= 10, "a volatile blast must never one-shot");
        assertTrue(c.warded().groupRange() > 0, "warded must not wall off solo players");
        assertTrue(c.digging().maxBlocksPerMob() <= 16, "diggers breach, they don't excavate");
        assertTrue(c.magnetic().needsLineOfSight(), "you can always duck a magnet");
    }

    @Test
    void resourcePackDefaultsToOfferedButOptional() {
        MechanicsConfig.Pack pack = MechanicsConfig.defaults().pack();
        assertTrue(pack.enabled());
        assertFalse(pack.required(), "declining the pack must never lock anyone out by default");
        assertEquals("", pack.url());
        assertEquals("", pack.sha1());
    }

    @Test
    void customPackNeedsBothUrlAndHash() {
        List<String> errors = new ArrayList<>();
        MechanicsConfig.Pack pack = MechanicsConfig.parse(
                "{ \"resourcePack\": { \"url\": \"https://example.com/p.zip\" } }", errors).pack();
        assertFalse(errors.isEmpty());
        assertEquals("", pack.url(), "half a custom pack falls back to the built-in one");
    }

    @Test
    void customPackIsValidated() {
        List<String> errors = new ArrayList<>();
        String sha = "0123456789abcdef0123456789abcdef01234567";
        MechanicsConfig.Pack ok = MechanicsConfig.parse("{ \"resourcePack\": { \"url\": \"https://example.com/p.zip\", "
                + "\"sha1\": \"" + sha.toUpperCase() + "\", \"required\": true } }", errors).pack();
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(sha, ok.sha1());
        assertTrue(ok.required());
        MechanicsConfig.parse("{ \"resourcePack\": { \"url\": \"ftp://x\", \"sha1\": \"nothex\" } }", errors);
        assertTrue(errors.size() >= 2);
    }

    @Test
    void warpRollFollowsWeights() {
        MechanicsConfig.Warper w = MechanicsConfig.defaults().warper(); // 30 / 40 / 30
        int[] counts = new int[3];
        Rand r = Rand.seeded(11);
        for (int i = 0; i < 30_000; i++) {
            counts[w.roll(r).ordinal()]++;
        }
        assertEquals(0.30, counts[0] / 30_000.0, 0.02);
        assertEquals(0.40, counts[1] / 30_000.0, 0.02);
        assertEquals(0.30, counts[2] / 30_000.0, 0.02);
    }

    @Test
    void zeroWeightsMeanScatter() {
        MechanicsConfig.Warper w = MechanicsConfig.parse("""
                { "warper": { "tossWeight": 0, "swapWeight": 0, "scatterWeight": 0 } }""",
                new ArrayList<>()).warper();
        assertEquals(MechanicsConfig.Warper.Effect.SCATTER, w.roll(Rand.seeded(3)));
    }

    @Test
    void riftOnlyInConfiguredRings() {
        MechanicsConfig.Warper w = MechanicsConfig.parse(
                "{ \"warper\": { \"netherRiftChance\": 1.0 } }", new ArrayList<>()).warper();
        assertTrue(w.rollRift(Rand.seeded(1), "level4"));
        assertFalse(w.rollRift(Rand.seeded(1), "level1"));
    }
}
