package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModifierPickerTest {
    private static final List<EliteModifier> ALL = Arrays.asList(EliteModifier.values());

    @Test
    void singlePickComesFromPool() {
        List<EliteModifier> pool = List.of(EliteModifier.SWIFT, EliteModifier.CORROSIVE);
        for (long seed = 0; seed < 200; seed++) {
            List<EliteModifier> picked = ModifierPicker.pick(pool, 1, Rand.seeded(seed));
            assertEquals(1, picked.size());
            assertTrue(pool.contains(picked.get(0)));
        }
    }

    @Test
    void championPicksAlwaysDifferInCategory() {
        for (long seed = 0; seed < 2000; seed++) {
            List<EliteModifier> picked = ModifierPicker.pick(ALL, 2, Rand.seeded(seed));
            assertEquals(2, picked.size(), "full pool must always yield a champion pair");
            assertNotEquals(picked.get(0).category(), picked.get(1).category(),
                    "got same-category pair: " + picked);
        }
    }

    @Test
    void bannedPairNeverRolls() {
        List<EliteModifier> pool = List.of(
                EliteModifier.SUMMONER, EliteModifier.VENGEFUL, EliteModifier.SWIFT);
        for (long seed = 0; seed < 2000; seed++) {
            List<EliteModifier> picked = ModifierPicker.pick(pool, 2, Rand.seeded(seed));
            boolean bothBanned = picked.contains(EliteModifier.SUMMONER)
                    && picked.contains(EliteModifier.VENGEFUL);
            assertTrue(!bothBanned, "banned pair rolled with seed " + seed);
        }
    }

    @Test
    void teleportersNeverStack() {
        // WARPER, BLINKSTEP and SWIFT share MOBILITY, so no champion can have two.
        for (long seed = 0; seed < 2000; seed++) {
            List<EliteModifier> picked = ModifierPicker.pick(ALL, 2, Rand.seeded(seed));
            long mobility = picked.stream()
                    .filter(m -> m.category() == EliteModifier.Category.MOBILITY).count();
            assertTrue(mobility <= 1, "two mobility modifiers with seed " + seed + ": " + picked);
        }
    }

    @Test
    void singleCategoryPoolCannotFillChampion() {
        List<EliteModifier> pool = List.of(EliteModifier.SWIFT, EliteModifier.BLINKSTEP);
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(1, ModifierPicker.pick(pool, 2, Rand.seeded(seed)).size());
        }
    }

    @Test
    void emptyPoolYieldsNothing() {
        assertTrue(ModifierPicker.pick(List.of(), 2, Rand.seeded(1)).isEmpty());
    }

    @Test
    void everyModifierHasAnEpithetAndId() {
        for (EliteModifier modifier : EliteModifier.values()) {
            assertTrue(modifier.epithet().startsWith("the "), modifier + " epithet looks wrong");
            assertEquals(modifier, EliteModifier.byId(modifier.id()));
        }
    }
}
