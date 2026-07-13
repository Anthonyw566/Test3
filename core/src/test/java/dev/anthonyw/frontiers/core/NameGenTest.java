package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameGenTest {

    @Test
    void deterministicWithSeed() {
        assertEquals(NameGen.generate(Rand.seeded(42)), NameGen.generate(Rand.seeded(42)));
    }

    @Test
    void namesAreCapitalizedAndPlausible() {
        for (long seed = 0; seed < 500; seed++) {
            String name = NameGen.generate(Rand.seeded(seed));
            assertFalse(name.isBlank());
            assertTrue(Character.isUpperCase(name.charAt(0)));
            assertTrue(name.length() >= 4 && name.length() <= 10, "odd name: " + name);
        }
    }
}
