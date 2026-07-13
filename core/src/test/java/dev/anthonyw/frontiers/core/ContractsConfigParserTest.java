package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractsConfigParserTest {

    @Test
    void defaultContractsParseCleanly() {
        ContractsConfigParser.Result result = ContractsConfigParser.parse(ContractsConfigParser.DEFAULT_JSON);
        assertTrue(result.errors().isEmpty(), "default contracts config has errors: " + result.errors());
        assertEquals(9, result.data().bountyMobs().size());
        assertEquals("minecraft:chests/simple_dungeon", result.data().cacheLootTables().get("default"));
        assertEquals("minecraft:chests/ancient_city", result.data().cacheLootTables().get("ashenfront"));
    }

    @Test
    void charterTargetsScaleWithDanger() {
        ContractsConfigParser.Data data = ContractsConfigParser.parse(ContractsConfigParser.DEFAULT_JSON).data();
        assertEquals(5, data.charterSlayTarget(1));   // verge: 3 + 2*1
        assertEquals(7, data.charterSlayTarget(2));   // wildmarch
        assertEquals(11, data.charterSlayTarget(4));  // ashenfront
    }

    @Test
    void emptyMobListFallsBackToDefaults() {
        ContractsConfigParser.Result result = ContractsConfigParser.parse("{ \"bountyMobs\": [] }");
        assertEquals(ContractsConfigParser.defaultMobs(), result.data().bountyMobs());
    }

    @Test
    void malformedMobIdIsReported() {
        ContractsConfigParser.Result result = ContractsConfigParser.parse(
                "{ \"bountyMobs\": [\"zombie\"] }");
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void defaultLootTableIsAlwaysPresent() {
        ContractsConfigParser.Result result = ContractsConfigParser.parse(
                "{ \"cacheLootTables\": { \"verge\": \"minecraft:chests/village/village_armorer\" } }");
        assertTrue(result.data().cacheLootTables().containsKey("default"));
    }

    @Test
    void invalidJsonFallsBackWithError() {
        ContractsConfigParser.Result result = ContractsConfigParser.parse("###");
        assertFalse(result.errors().isEmpty());
        assertFalse(result.data().bountyMobs().isEmpty());
    }
}
