package dev.anthonyw.frontiers.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiminishingReturnsTest {

    @Test
    void firstTenPayFull() {
        assertEquals(1.0, DiminishingReturns.multiplier(1));
        assertEquals(1.0, DiminishingReturns.multiplier(10));
    }

    @Test
    void nextTwentyPayHalf() {
        assertEquals(0.5, DiminishingReturns.multiplier(11));
        assertEquals(0.5, DiminishingReturns.multiplier(30));
    }

    @Test
    void beyondThirtyPaysNothing() {
        assertEquals(0.0, DiminishingReturns.multiplier(31));
        assertEquals(0.0, DiminishingReturns.multiplier(9999));
    }
}
