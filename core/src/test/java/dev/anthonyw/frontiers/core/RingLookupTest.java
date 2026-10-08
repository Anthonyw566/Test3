package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RingLookupTest {
    private static final List<RingDef> RINGS =
            RingsConfig.parse(RingsConfig.DEFAULT_JSON).config().rings();

    @Test
    void originIsTheHearth() {
        assertEquals("hearth", RingLookup.at(RINGS, 0).id());
    }

    @Test
    void boundaryDistanceBelongsToInnerRing() {
        assertEquals("hearth", RingLookup.at(RINGS, 400).id());
        assertEquals("verge", RingLookup.at(RINGS, 400.01).id());
        assertEquals("verge", RingLookup.at(RINGS, 1200).id());
        assertEquals("wildmarch", RingLookup.at(RINGS, 1201).id());
    }

    @Test
    void farDistancesFallIntoUnboundedRing() {
        assertEquals("ashenfront", RingLookup.at(RINGS, 5601).id());
        assertEquals("ashenfront", RingLookup.at(RINGS, 1_000_000).id());
    }

    @Test
    void blocksToNextCountsDown() {
        assertEquals(100, RingLookup.blocksToNext(RINGS, 300), 0.001);
        assertEquals(-1, RingLookup.blocksToNext(RINGS, 999_999));
    }

    @Test
    void innerRadiusIsPreviousOuter() {
        assertEquals(0, RingLookup.innerRadius(RINGS, "hearth"));
        assertEquals(400, RingLookup.innerRadius(RINGS, "verge"));
        assertEquals(2800, RingLookup.innerRadius(RINGS, "duskreach"));
        assertEquals(5600, RingLookup.innerRadius(RINGS, "ashenfront"));
    }

    @Test
    void byIdFindsRingsAndRejectsUnknown() {
        assertEquals("The Duskreach", RingLookup.byId(RINGS, "duskreach").name());
        assertNull(RingLookup.byId(RINGS, "atlantis"));
    }
}
