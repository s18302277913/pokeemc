package com.poketrade.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeQuoteTest {
    private static final TradeItemId DIAMOND = TradeItemId.parse("minecraft:diamond");

    @Test
    void calculatesExactCost() {
        assertEquals(24_576L, TradeQuote.of(DIAMOND, 3, 8_192L).totalCost());
    }

    @Test
    void exposesEveryComponentOfAnIssuedQuote() {
        TradeQuote quote = TradeQuote.of(DIAMOND, 4, 8_192L);
        assertEquals(DIAMOND, quote.itemId());
        assertEquals(4, quote.quantity());
        assertEquals(8_192L, quote.unitValue());
        assertEquals(32_768L, quote.totalCost());
    }

    @Test
    void rejectsInvalidValuesAndOverflow() {
        assertThrows(NullPointerException.class, () -> TradeQuote.of(null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> TradeQuote.of(DIAMOND, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> TradeQuote.of(DIAMOND, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> TradeQuote.of(DIAMOND, -1, 1));
        assertThrows(ArithmeticException.class, () -> TradeQuote.of(DIAMOND, 2, Long.MAX_VALUE));
    }

    @Test
    void exposesStableResultCodes() {
        assertEquals("success", TradeResult.SUCCESS.code());
        assertEquals("unknown_item", TradeResult.UNKNOWN_ITEM.code());
        assertEquals("invalid_quantity", TradeResult.INVALID_QUANTITY.code());
        assertEquals("insufficient_funds", TradeResult.INSUFFICIENT_FUNDS.code());
        assertEquals("output_blocked", TradeResult.OUTPUT_BLOCKED.code());
        assertEquals("internal_error", TradeResult.INTERNAL_ERROR.code());
    }
}
