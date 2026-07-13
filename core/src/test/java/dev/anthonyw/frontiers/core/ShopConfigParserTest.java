package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopConfigParserTest {

    @Test
    void defaultShopParsesCleanly() {
        ShopConfigParser.Result result = ShopConfigParser.parse(ShopConfigParser.DEFAULT_JSON);
        assertTrue(result.errors().isEmpty(), "default shop has errors: " + result.errors());
        assertEquals(7, result.offers().size());
        ShopConfigParser.OfferDef cacheMap = result.offers().stream()
                .filter(o -> o.id().equals("cache_map")).findFirst().orElseThrow();
        assertEquals("cache_map", cacheMap.special());
        assertEquals(1, cacheMap.tier());
    }

    @Test
    void tiersNeverExceedRingCount() {
        // 4 danger rings -> max meaningful tier is 4; a tier-9 offer could never unlock
        for (ShopConfigParser.OfferDef offer : ShopConfigParser.parse(ShopConfigParser.DEFAULT_JSON).offers()) {
            assertTrue(offer.tier() <= 4, offer.id() + " can never unlock (tier " + offer.tier() + ")");
        }
    }

    @Test
    void badItemIdIsReported() {
        String json = """
                { "offers": [ { "id": "kit", "items": [ { "id": "ironingot" } ] } ] }""";
        ShopConfigParser.Result result = ShopConfigParser.parse(json);
        assertFalse(result.errors().isEmpty());
        // the offer survives but with no items -> also flagged as selling nothing
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("sell nothing")
                || e.contains("ironingot")));
    }

    @Test
    void missingIdIsReported() {
        ShopConfigParser.Result result = ShopConfigParser.parse(
                "{ \"offers\": [ { \"cost\": 5 } ] }");
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("missing")));
        assertTrue(result.offers().isEmpty());
    }

    @Test
    void negativeCostIsClampedAndReported() {
        ShopConfigParser.Result result = ShopConfigParser.parse("""
                { "offers": [ { "id": "freebie", "cost": -5,
                  "items": [ { "id": "minecraft:dirt", "count": 1 } ] } ] }""");
        assertEquals(0, result.offers().get(0).cost());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void invalidJsonIsReportedNotThrown() {
        assertFalse(ShopConfigParser.parse("oops").errors().isEmpty());
    }
}
