package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingsConfigTest {
    private static RingsConfig.Result parse(String json) {
        return RingsConfig.parse(json);
    }

    private static boolean hasError(RingsConfig.Result r, String fragment) {
        return r.errors().stream().anyMatch(e -> e.contains(fragment));
    }

    @Test
    void shippedDefaultIsClean() {
        RingsConfig.Result r = parse(RingsConfig.DEFAULT_JSON);
        assertTrue(r.ok(), "default rings.json must have zero errors: " + r.errors());
        RingsConfig c = r.config();
        assertEquals(List.of("safe", "level1", "level2", "level3", "level4"),
                c.rings().stream().map(RingDef::id).toList());
        assertTrue(c.rings().get(0).safeZone());
        assertTrue(c.rings().get(4).unbounded());
        assertEquals("level3", c.dimensionRings().get("minecraft:the_nether"));
        assertEquals(List.of("minecraft:overworld"), c.radialDimensions());
        assertTrue(c.excludedSpawnTypes().contains("SPAWNER"));
        assertTrue(c.excludedSpawnTypes().contains("TRIAL_SPAWNER"));
        assertTrue(c.excludedSpawnTypes().contains("EVENT"), "raids must never be touched");
        assertTrue(c.excludedSpawnTypes().containsAll(RingsConfig.DEFAULT_EXCLUDED_SPAWN_TYPES),
                "shipped JSON and code defaults must agree");
        assertFalse(c.excludedSpawnTypes().contains("NATURAL"));
    }

    @Test
    void difficultyRisesWithEveryRing() {
        List<RingDef> rings = parse(RingsConfig.DEFAULT_JSON).config().rings();
        for (int i = 1; i < rings.size(); i++) {
            RingDef inner = rings.get(i - 1);
            RingDef outer = rings.get(i);
            assertTrue(outer.danger() > inner.danger(), outer.id() + " danger");
            assertTrue(outer.digChance() >= inner.digChance(), outer.id() + " digChance");
            assertTrue(outer.eliteChance() >= inner.eliteChance(), outer.id() + " eliteChance");
            assertTrue(outer.healthMult() >= inner.healthMult(), outer.id() + " healthMult");
        }
    }

    @Test
    void defaultsNeverExceedCaps() {
        RingsConfig c = parse(RingsConfig.DEFAULT_JSON).config();
        for (RingDef r : c.rings()) {
            assertTrue(r.healthMult() <= c.maxHealthMult(), r.id());
            assertTrue(r.damageMult() <= c.maxDamageMult(), r.id());
        }
    }

    @Test
    void ringsSortByRadius() {
        RingsConfig.Result r = parse("""
                { "rings": [ { "id": "c", "outerRadius": -1 }, { "id": "b", "outerRadius": 900 },
                             { "id": "a", "outerRadius": 100 } ] }""");
        assertEquals(List.of("a", "b", "c"), r.config().rings().stream().map(RingDef::id).toList());
    }

    @Test
    void duplicateIdsRejected() {
        assertTrue(hasError(parse("""
                { "rings": [ { "id": "a", "outerRadius": 10 }, { "id": "a", "outerRadius": -1 } ] }"""),
                "duplicate"));
    }

    @Test
    void equalRadiiRejected() {
        assertTrue(hasError(parse("""
                { "rings": [ { "id": "a", "outerRadius": 10 }, { "id": "b", "outerRadius": 10 },
                             { "id": "c", "outerRadius": -1 } ] }"""), "must be larger"));
    }

    @Test
    void missingEndlessRingRejected() {
        assertTrue(hasError(parse("{ \"rings\": [ { \"id\": \"a\", \"outerRadius\": 10 } ] }"), "no edge"));
    }

    @Test
    void twoEndlessRingsRejected() {
        assertTrue(hasError(parse("""
                { "rings": [ { "id": "a", "outerRadius": -1 }, { "id": "b", "outerRadius": -1 } ] }"""),
                "only one ring"));
    }

    @Test
    void unknownAbilityReported() {
        RingsConfig.Result r = parse("""
                { "rings": [ { "id": "a", "outerRadius": -1,
                  "elites": { "modifiers": ["warper", "laserbeam"] } } ] }""");
        assertTrue(hasError(r, "laserbeam"));
        assertEquals(List.of("warper"), r.config().rings().get(0).modifiers());
    }

    @Test
    void oldNameKeysAreHarmless() {
        RingsConfig.Result r = parse("""
                { "rings": [ { "id": "a", "outerRadius": -1, "name": "The Old Name", "color": "red",
                  "entryMessage": "Old flavour text" } ] }""");
        assertTrue(r.ok(), r.errors().toString());
    }

    @Test
    void chanceOutOfRangeReportedAndDefaulted() {
        RingsConfig.Result r = parse("""
                { "rings": [ { "id": "a", "outerRadius": -1, "mobs": { "digChance": 3 } } ] }""");
        assertTrue(hasError(r, "digChance"));
        assertEquals(0.0, r.config().rings().get(0).digChance());
    }

    @Test
    void safeZoneWithElitesIsContradiction() {
        assertTrue(hasError(parse("""
                { "rings": [ { "id": "a", "outerRadius": -1, "safeZone": true,
                  "elites": { "eliteChance": 0.5 } } ] }"""), "safe zone"));
    }

    @Test
    void dimensionRingMustPointAtRealRing() {
        assertTrue(hasError(parse("""
                { "dimensionRings": { "minecraft:the_nether": "narnia" },
                  "rings": [ { "id": "a", "outerRadius": -1 } ] }"""), "narnia"));
    }

    @Test
    void radialDimensionCannotAlsoBePinned() {
        assertTrue(hasError(parse("""
                { "dimensionRings": { "minecraft:overworld": "a" },
                  "rings": [ { "id": "a", "outerRadius": -1 } ] }"""), "already a radial"));
    }

    @Test
    void unknownSpawnTypeReported() {
        assertTrue(hasError(parse("""
                { "exclusions": { "spawnTypes": ["SPAWNER", "WIZARD"] },
                  "rings": [ { "id": "a", "outerRadius": -1 } ] }"""), "WIZARD"));
    }

    @Test
    void badLootIdReported() {
        RingsConfig.Result r = parse("""
                { "rings": [ { "id": "a", "outerRadius": -1, "elites": { "loot": "dungeon chest" } } ] }""");
        assertTrue(hasError(r, "elites.loot"));
        assertEquals("", r.config().rings().get(0).eliteLoot());
    }

    @Test
    void nightMultiplierIsBounded() {
        RingsConfig.Result r = parse("""
                { "night": { "radiusMultiplier": 0.1 }, "rings": [ { "id": "a", "outerRadius": -1 } ] }""");
        assertTrue(hasError(r, "radiusMultiplier"));
        assertEquals(0.8, r.config().nightRadiusMultiplier(), 1e-9);
        assertEquals(1.0, parse("""
                { "night": { "radiusMultiplier": 1.0 }, "rings": [ { "id": "a", "outerRadius": -1 } ] }""")
                .config().nightRadiusMultiplier(), 1e-9, "1.0 turns it off");
    }

    @Test
    void invalidJsonIsAnErrorNotACrash() {
        RingsConfig.Result r = parse("{ nope");
        assertFalse(r.ok());
        assertNull(r.config());
    }
}
