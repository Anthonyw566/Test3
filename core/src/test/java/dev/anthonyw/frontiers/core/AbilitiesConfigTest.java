package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilitiesConfigTest {

    @Test
    void defaultsParseCleanly() {
        List<String> errors = new ArrayList<>();
        AbilitiesConfig config = AbilitiesConfig.parse(AbilitiesConfig.DEFAULT_JSON, errors);
        assertTrue(errors.isEmpty(), "default abilities config has errors: " + errors);
        assertEquals(0.25, config.warperProcChance());
        assertEquals(160, config.warperCooldownTicks());
        assertEquals(0.05, config.netherRiftChance());
        assertEquals(List.of("ashenfront"), config.netherRiftRings());
        assertTrue(config.siegerEnabled());
        assertEquals(5.0, config.siegerMaxHardness());
        assertEquals(32, config.siegerMaxBlocksPerMob());
        assertTrue(config.siegerDropsBlocks());
        assertFalse(config.siegerBreaksBlockEntities());
        assertTrue(config.siegerBlockBlacklist().contains("minecraft:obsidian"));
        assertTrue(config.hunterAlwaysSieger());
    }

    @Test
    void overridesAreHonored() {
        String json = """
                { "warper": { "procChance": 0.5, "netherRiftChance": 0.0,
                              "netherRiftRings": ["duskreach", "ashenfront"] },
                  "sieger": { "enabled": false, "maxHardness": 3.0 } }""";
        AbilitiesConfig config = AbilitiesConfig.parse(json, new ArrayList<>());
        assertEquals(0.5, config.warperProcChance());
        assertEquals(0.0, config.netherRiftChance());
        assertEquals(2, config.netherRiftRings().size());
        assertFalse(config.siegerEnabled());
        assertEquals(3.0, config.siegerMaxHardness());
    }

    @Test
    void invalidJsonFallsBackToDefaultsWithError() {
        List<String> errors = new ArrayList<>();
        AbilitiesConfig config = AbilitiesConfig.parse("{ nope", errors);
        assertFalse(errors.isEmpty());
        assertEquals(0.25, config.warperProcChance());
    }

    @Test
    void outOfRangeChancesAreCorrected() {
        List<String> errors = new ArrayList<>();
        AbilitiesConfig config = AbilitiesConfig.parse(
                "{ \"warper\": { \"procChance\": 7 } }", errors);
        assertEquals(0.25, config.warperProcChance());
        assertFalse(errors.isEmpty());
    }

    @Test
    void warpEffectRollRespectsWeights() {
        AbilitiesConfig config = AbilitiesConfig.defaults(); // 40/30/30
        int[] counts = new int[3];
        Rand random = Rand.seeded(7);
        for (int i = 0; i < 30_000; i++) {
            counts[config.rollWarpEffect(random)]++;
        }
        assertTrue(Math.abs(counts[0] / 30_000.0 - 0.40) < 0.02, "toss ratio off: " + counts[0]);
        assertTrue(Math.abs(counts[1] / 30_000.0 - 0.30) < 0.02, "swap ratio off: " + counts[1]);
        assertTrue(Math.abs(counts[2] / 30_000.0 - 0.30) < 0.02, "scatter ratio off: " + counts[2]);
    }

    @Test
    void zeroWeightsDefaultToToss() {
        AbilitiesConfig config = AbilitiesConfig.parse("""
                { "warper": { "tossWeight": 0, "swapWeight": 0, "scatterWeight": 0 } }""",
                new ArrayList<>());
        assertEquals(0, config.rollWarpEffect(Rand.seeded(1)));
    }
}
