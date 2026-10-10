package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RingLookupTest {
    private static final List<RingDef> RINGS =
            RingsConfig.parse(RingsConfig.DEFAULT_JSON).config().rings();

    @Test
    void originIsSafe() {
        assertEquals("safe", RingLookup.at(RINGS, 0).id());
    }

    @Test
    void boundaryDistanceBelongsToInnerRing() {
        assertEquals("safe", RingLookup.at(RINGS, 400).id());
        assertEquals("level1", RingLookup.at(RINGS, 400.01).id());
        assertEquals("level1", RingLookup.at(RINGS, 1200).id());
        assertEquals("level2", RingLookup.at(RINGS, 1201).id());
    }

    @Test
    void farDistancesFallIntoUnboundedRing() {
        assertEquals("level4", RingLookup.at(RINGS, 5601).id());
        assertEquals("level4", RingLookup.at(RINGS, 1_000_000).id());
    }

    @Test
    void blocksToNextCountsDown() {
        assertEquals(100, RingLookup.blocksToNext(RINGS, 300), 0.001);
        assertEquals(-1, RingLookup.blocksToNext(RINGS, 999_999));
    }

    @Test
    void innerRadiusIsPreviousOuter() {
        assertEquals(0, RingLookup.innerRadius(RINGS, "safe"));
        assertEquals(400, RingLookup.innerRadius(RINGS, "level1"));
        assertEquals(2800, RingLookup.innerRadius(RINGS, "level3"));
        assertEquals(5600, RingLookup.innerRadius(RINGS, "level4"));
    }

    @Test
    void byIdFindsRingsAndRejectsUnknown() {
        assertEquals(3, RingLookup.byId(RINGS, "level3").danger());
        assertNull(RingLookup.byId(RINGS, "atlantis"));
    }
}
