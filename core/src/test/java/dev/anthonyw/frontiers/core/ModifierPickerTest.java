package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModifierPickerTest {
    private static final List<EliteModifier> ALL = Arrays.asList(EliteModifier.values());

    @Test
    void championsGetTwoDistinctAbilities() {
        for (long seed = 0; seed < 3000; seed++) {
            List<EliteModifier> picked = ModifierPicker.pick(ALL, 2, Rand.seeded(seed));
            assertEquals(2, picked.size());
            assertTrue(picked.get(0) != picked.get(1));
        }
    }

    @Test
    void thiefNeverWarps() {
        for (long seed = 0; seed < 3000; seed++) {
            List<EliteModifier> picked = ModifierPicker.pick(ALL, 2, Rand.seeded(seed));
            assertFalse(picked.contains(EliteModifier.THIEF) && picked.contains(EliteModifier.WARPER),
                    "banned pair rolled with seed " + seed);
        }
    }

    @Test
    void everyAbilityCanAppear() {
        Set<EliteModifier> seen = new HashSet<>();
        for (long seed = 0; seed < 500; seed++) {
            seen.addAll(ModifierPicker.pick(ALL, 1, Rand.seeded(seed)));
        }
        assertEquals(EliteModifier.values().length, seen.size());
    }

    @Test
    void bannedPairPoolYieldsOne() {
        List<EliteModifier> pool = List.of(EliteModifier.THIEF, EliteModifier.WARPER);
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(1, ModifierPicker.pick(pool, 2, Rand.seeded(seed)).size());
        }
    }

    @Test
    void emptyPoolYieldsNothing() {
        assertTrue(ModifierPicker.pick(List.of(), 2, Rand.seeded(1)).isEmpty());
    }

    @Test
    void starPoolMeansEverything() {
        assertEquals(ALL, ModifierPicker.resolvePool(List.of("*")));
        assertEquals(List.of(EliteModifier.THIEF), ModifierPicker.resolvePool(List.of("thief", "thief", "bogus")));
    }

    @Test
    void parsesCommandInput() {
        ModifierPicker.Parsed p = ModifierPicker.parse("Warper, volatile  nonsense warper");
        assertEquals(List.of(EliteModifier.WARPER, EliteModifier.VOLATILE), p.modifiers());
        assertEquals(List.of("nonsense"), p.unknown());
        assertTrue(ModifierPicker.parse("").modifiers().isEmpty());
        assertTrue(ModifierPicker.parse(null).modifiers().isEmpty());
    }

    @Test
    void everyAbilityHasNameAndTip() {
        for (EliteModifier m : EliteModifier.values()) {
            assertTrue(m.epithet().startsWith("the "), m + " epithet");
            assertTrue(m.tip().length() > 20, m + " tip");
            assertEquals(m, EliteModifier.byId(m.id()));
            assertEquals(m, EliteModifier.byId(" " + m.id().toUpperCase() + " "));
        }
        assertNull(EliteModifier.byId("swift"));
    }
}
