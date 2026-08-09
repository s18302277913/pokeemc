package com.poketrade.api.market;

import com.poketrade.api.TradeItemId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketQuoteTest {

    private static final TradeItemId BALL = TradeItemId.parse("pixelmon:poke_ball");

    @Test
    void ofComputesTotals() {
        MarketQuote q = MarketQuote.of(BALL, 3, 2000, 650);
        assertEquals(6000, q.totalCost());
        assertEquals(1950, q.totalValue());
        assertTrue(q.buyAvailable());
        assertTrue(q.sellAvailable());
    }

    @Test
    void ofRejectsNonPositiveCount() {
        assertThrows(IllegalArgumentException.class, () -> MarketQuote.of(BALL, 0, 2000, 650));
        assertThrows(IllegalArgumentException.class, () -> MarketQuote.of(BALL, -3, 2000, 650));
    }

    @Test
    void ofRejectsNullItemId() {
        assertThrows(NullPointerException.class, () -> MarketQuote.of(null, 1, 2000, 650));
    }

    @Test
    void ofRejectsNegativePrice() {
        assertThrows(IllegalArgumentException.class, () -> MarketQuote.of(BALL, 1, -1, 650));
        assertThrows(IllegalArgumentException.class, () -> MarketQuote.of(BALL, 1, 2000, -1));
    }

    @Test
    void ofRejectsZeroBothPrices() {
        assertThrows(IllegalArgumentException.class, () -> MarketQuote.of(BALL, 1, 0, 0));
    }

    @Test
    void ofOverflowThrows() {
        assertThrows(ArithmeticException.class,
                () -> MarketQuote.of(BALL, Integer.MAX_VALUE, Long.MAX_VALUE, 1));
    }

    @Test
    void valueOverflowThrows() {
        assertThrows(ArithmeticException.class, () -> MarketQuote.of(BALL, 2, 1, Long.MAX_VALUE));
    }

    @Test
    void sellUnavailableQuote() {
        MarketQuote q = MarketQuote.of(BALL, 1, 2000, 0);
        assertFalse(q.sellAvailable());
        assertEquals(0, q.totalValue());
    }

    @Test
    void buyUnavailableQuote() {
        MarketQuote q = MarketQuote.of(BALL, 1, 0, 650);
        assertFalse(q.buyAvailable());
        assertEquals(0, q.totalCost());
        assertTrue(q.sellAvailable());
    }
}
