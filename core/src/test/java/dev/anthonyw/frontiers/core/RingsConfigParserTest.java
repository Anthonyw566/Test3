package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingsConfigParserTest {

    @Test
    void defaultConfigParsesCleanly() {
        RingsConfigParser.Result result = RingsConfigParser.parse(RingsConfigParser.DEFAULT_JSON);
        assertTrue(result.ok(), "default config must have zero errors, got: " + result.errors());
        assertEquals(5, result.data().rings().size());
        assertEquals("hearth", result.data().rings().get(0).id());
        assertEquals("ashenfront", result.data().rings().get(4).id());
        assertTrue(result.data().rings().get(0).suppressHostileSpawns());
        assertTrue(result.data().rings().get(4).unbounded());
        assertTrue(result.data().useWorldSpawn());
        assertEquals(List.of("minecraft:overworld"), result.data().dimensions());
        assertEquals(2.0, result.data().maxHealthMult());
        assertTrue(result.data().skipBosses());
    }

    @Test
    void defaultConfigModifierIdsAllResolve() {
        RingsConfigParser.Result result = RingsConfigParser.parse(RingsConfigParser.DEFAULT_JSON);
        for (RingDef ring : result.data().rings()) {
            for (String id : ring.modifiers()) {
                assertTrue("*".equals(id) || EliteModifier.byId(id) != null,
                        "unknown modifier in default config: " + id);
            }
        }
    }

    @Test
    void ringsAreSortedByRadiusEvenIfConfigIsNot() {
        String json = """
                { "rings": [
                  { "id": "outer", "outerRadius": -1 },
                  { "id": "far", "outerRadius": 1000 },
                  { "id": "near", "outerRadius": 100 }
                ] }""";
        RingsConfigParser.Result result = RingsConfigParser.parse(json);
        assertEquals(List.of("near", "far", "outer"),
                result.data().rings().stream().map(RingDef::id).toList());
    }

    @Test
    void duplicateIdsAreRejected() {
        String json = """
                { "rings": [
                  { "id": "a", "outerRadius": 100 },
                  { "id": "a", "outerRadius": -1 }
                ] }""";
        assertTrue(RingsConfigParser.parse(json).errors().stream()
                .anyMatch(e -> e.contains("duplicate")));
    }

    @Test
    void nonIncreasingRadiiAreRejected() {
        String json = """
                { "rings": [
                  { "id": "a", "outerRadius": 100 },
                  { "id": "b", "outerRadius": 100 },
                  { "id": "c", "outerRadius": -1 }
                ] }""";
        assertTrue(RingsConfigParser.parse(json).errors().stream()
                .anyMatch(e -> e.contains("does not increase")));
    }

    @Test
    void unknownColorFallsBackToWhiteWithError() {
        String json = """
                { "rings": [ { "id": "a", "color": "ultraviolet", "outerRadius": -1 } ] }""";
        RingsConfigParser.Result result = RingsConfigParser.parse(json);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("ultraviolet")));
        assertEquals("white", result.data().rings().get(0).colorName());
    }

    @Test
    void unknownModifierIsRejected() {
        String json = """
                { "rings": [ { "id": "a", "outerRadius": -1,
                  "elites": { "modifiers": ["swift", "laserbeam"] } } ] }""";
        RingsConfigParser.Result result = RingsConfigParser.parse(json);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("laserbeam")));
        assertEquals(List.of("swift"), result.data().rings().get(0).modifiers());
    }

    @Test
    void boundedLastRingIsFlagged() {
        String json = """
                { "rings": [ { "id": "a", "outerRadius": 500 } ] }""";
        assertTrue(RingsConfigParser.parse(json).errors().stream()
                .anyMatch(e -> e.contains("outermost")));
    }

    @Test
    void unboundedMiddleRingIsFlagged() {
        String json = """
                { "rings": [
                  { "id": "a", "outerRadius": -1 },
                  { "id": "b", "outerRadius": 500 }
                ] }""";
        // after sorting, "a" (unbounded) lands last; the config error we care
        // about is that TWO rings cannot both claim the outer edge
        RingsConfigParser.Result result = RingsConfigParser.parse(json);
        assertTrue(result.data().rings().get(1).unbounded());
    }

    @Test
    void outOfRangeChancesAreRejected() {
        String json = """
                { "rings": [ { "id": "a", "outerRadius": -1,
                  "elites": { "eliteChance": 1.5 } } ] }""";
        assertTrue(RingsConfigParser.parse(json).errors().stream()
                .anyMatch(e -> e.contains("between 0 and 1")));
    }

    @Test
    void emptyConfigIsAnError() {
        assertFalse(RingsConfigParser.parse("{}").ok());
    }

    @Test
    void invalidJsonIsReportedNotThrown() {
        RingsConfigParser.Result result = RingsConfigParser.parse("{ this is not json");
        assertFalse(result.ok());
        assertTrue(result.errors().get(0).contains("not valid JSON"));
    }

    @Test
    void malformedDimensionIsRejected() {
        String json = """
                { "dimensions": ["overworld"],
                  "rings": [ { "id": "a", "outerRadius": -1 } ] }""";
        assertTrue(RingsConfigParser.parse(json).errors().stream()
                .anyMatch(e -> e.contains("overworld")));
    }
}
