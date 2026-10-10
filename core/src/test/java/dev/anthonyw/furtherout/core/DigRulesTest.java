package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import static dev.anthonyw.furtherout.core.DigRules.Verdict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigRulesTest {
    private static final MechanicsConfig.Digging CFG = MechanicsConfig.defaults().digging();

    private static Verdict check(float hardness, boolean blockEntity, String id, boolean safe, int broken) {
        return DigRules.canBreak(new DigRules.Block(false, hardness, blockEntity, id, true),
                new DigRules.Place(safe, false, broken), CFG);
    }

    @Test
    void naturalTerrainIsFair() {
        assertEquals(Verdict.OK, check(0.5f, false, "minecraft:dirt", false, 0));
        assertEquals(Verdict.OK, check(1.5f, false, "minecraft:stone", false, 0));
        assertEquals(Verdict.OK, check(3.0f, false, "minecraft:deepslate", false, 0));
    }

    @Test
    void makeshiftShelterIsFairGame() {
        Verdict planks = DigRules.canBreak(new DigRules.Block(false, 2.0f, false, "minecraft:oak_planks", false),
                new DigRules.Place(false, false, 0), CFG);
        assertEquals(Verdict.OK, planks, "a plank hut with no bed or chest in it is just a box");
    }

    @Test
    void naturalOnlyModeLeavesBuildingsAlone() {
        MechanicsConfig.Digging gentle = MechanicsConfig.parse("{ \"digging\": { \"naturalBlocksOnly\": true } }",
                new java.util.ArrayList<>()).digging();
        Verdict planks = DigRules.canBreak(new DigRules.Block(false, 2.0f, false, "minecraft:oak_planks", false),
                new DigRules.Place(false, false, 0), gentle);
        assertEquals(Verdict.NOT_NATURAL, planks);
    }

    @Test
    void nothingIsDugNearABase() {
        Verdict nearChest = DigRules.canBreak(new DigRules.Block(false, 1.5f, false, "minecraft:stone", true),
                new DigRules.Place(false, true, 0), CFG);
        assertEquals(Verdict.NEAR_BASE, nearChest);
    }

    @Test
    void protectionsCanBeSwitchedOff() {
        MechanicsConfig.Digging loose = MechanicsConfig.parse(
                "{ \"digging\": { \"naturalBlocksOnly\": false, \"baseRadius\": 0 } }",
                new java.util.ArrayList<>()).digging();
        assertEquals(Verdict.OK, DigRules.canBreak(new DigRules.Block(false, 2.0f, false, "minecraft:oak_planks", false),
                new DigRules.Place(false, true, 0), loose));
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
        assertEquals(Verdict.BLACKLISTED, check(1f, false, "minecraft:respawn_anchor", false, 0));
    }

    @Test
    void budgetRunsOut() {
        int budget = CFG.maxBlocksPerMob();
        assertEquals(Verdict.OK, check(1.5f, false, "minecraft:stone", false, budget - 1));
        assertEquals(Verdict.BUDGET_SPENT, check(1.5f, false, "minecraft:stone", false, budget));
    }

    @Test
    void airIsNotATarget() {
        assertEquals(Verdict.AIR, DigRules.canBreak(new DigRules.Block(true, 0, false, "minecraft:air", false),
                new DigRules.Place(false, false, 0), CFG));
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
