package dev.anthonyw.furtherout.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MimicRulesTest {
    private static final MechanicsConfig.Mimic CFG = MechanicsConfig.defaults().mimic();

    @Test
    void baitIsRareAndOnlyFarOut() {
        assertTrue(CFG.chance() <= 0.01, "rare: well under 1 in 100 cave spawns");
        assertTrue(MimicRules.replaces(CFG, 3, false, true, 0.0));
        assertFalse(MimicRules.replaces(CFG, 3, false, true, 0.5), "the roll has to hit");
        assertFalse(MimicRules.replaces(CFG, 1, false, true, 0.0), "not that close to spawn");
        assertFalse(MimicRules.replaces(CFG, 3, true, true, 0.0), "never in the safe area");
        assertFalse(MimicRules.replaces(CFG, 3, false, false, 0.0), "no point where nobody will find it");
    }

    @Test
    void itSpringsWhenYouReachForIt() {
        assertTrue(MimicRules.springs(2.0, CFG.triggerRadius()));
        assertFalse(MimicRules.springs(4.0, CFG.triggerRadius()));
    }

    @Test
    void baitsParse() {
        List<String> errors = new ArrayList<>();
        List<MimicRules.Bait> baits = MimicRules.parseBaits(
                List.of("minecraft:diamond", "minecraft:gold_ingot*3", "Gold", "minecraft:emerald*0", "x:y*abc"), errors);
        assertEquals(List.of(new MimicRules.Bait("minecraft:diamond", 1), new MimicRules.Bait("minecraft:gold_ingot", 3)),
                baits);
        assertEquals(3, errors.size(), errors.toString());
    }

    @Test
    void defaultsAreTempting() {
        assertTrue(CFG.baits().stream().anyMatch(b -> b.itemId().equals("minecraft:diamond")));
        assertFalse(CFG.mobs().contains("minecraft:creeper"), "a creeper would blow up the bait it's meant to drop");
    }
}
