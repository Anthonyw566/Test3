package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import static dev.anthonyw.frontiers.core.DigRules.Verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigRulesTest {
    private static final MechanicsConfig.Digging CFG = MechanicsConfig.defaults().digging();

    private static Verdict check(float hardness, boolean blockEntity, String id, boolean safe, int broken) {
        return DigRules.canBreak(false, hardness, blockEntity, id, safe, broken, CFG);
    }

    @Test
    void ordinaryBuildingBlocksAreFair() {
        assertEquals(Verdict.OK, check(0.5f, false, "minecraft:dirt", false, 0));
        assertEquals(Verdict.OK, check(1.5f, false, "minecraft:stone", false, 0));
        assertEquals(Verdict.OK, check(2.0f, false, "minecraft:oak_planks", false, 0));
        assertEquals(Verdict.OK, check(3.0f, false, "minecraft:deepslate", false, 0));
    }

    @Test
    void safeZoneIsInviolate() {
        assertEquals(Verdict.SAFE_ZONE, check(0.5f, false, "minecraft:dirt", true, 0));
    }

    @Test
    void chestsAndMachinesAreSafe() {
        assertEquals(Verdict.BLOCK_ENTITY, check(2.5f, true, "minecraft:chest", false, 0));
    }

    @Test
    void obsidianAndBedrockHold() {
        assertEquals(Verdict.TOO_HARD, check(50f, false, "minecraft:obsidian", false, 0));
        assertEquals(Verdict.UNBREAKABLE, check(-1f, false, "minecraft:bedrock", false, 0));
        assertEquals(Verdict.BLACKLISTED, check(5f, false, "minecraft:respawn_anchor", false, 0));
    }

    @Test
    void budgetRunsOut() {
        assertEquals(Verdict.OK, check(1.5f, false, "minecraft:stone", false, 31));
        assertEquals(Verdict.BUDGET_SPENT, check(1.5f, false, "minecraft:stone", false, 32));
    }

    @Test
    void airIsNotATarget() {
        assertEquals(Verdict.AIR, DigRules.canBreak(true, 0, false, "minecraft:air", false, 0, CFG));
    }

    @Test
    void breakTimesScaleWithHardness() {
        assertEquals(20, DigRules.breakTicks(0.5f, 1.0));
        assertEquals(45, DigRules.breakTicks(1.5f, 1.0));
        assertEquals(90, DigRules.breakTicks(3.0f, 1.0));
        assertEquals(300, DigRules.breakTicks(50f, 1.0));
        assertEquals(23, DigRules.breakTicks(1.5f, 2.0));
    }

    @Test
    void whoDigs() {
        assertTrue(DigRules.shouldDig("minecraft:zombie", false, 0.5, 0.2, CFG));
        assertFalse(DigRules.shouldDig("minecraft:zombie", false, 0.5, 0.7, CFG));
        assertFalse(DigRules.shouldDig("minecraft:spider", false, 1.0, 0.0, CFG), "spiders climb instead");
        assertTrue(DigRules.shouldDig("minecraft:spider", true, 0.0, 0.99, CFG), "elites always dig");
        assertFalse(DigRules.shouldDig("minecraft:zombie", false, 0.0, 0.0, CFG), "0% ring never digs");
    }
}
