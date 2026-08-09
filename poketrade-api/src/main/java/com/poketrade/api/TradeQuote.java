package com.poketrade.api;

import java.util.Objects;

public record TradeQuote(TradeItemId itemId, int quantity, long unitValue, long totalCost) {
    public static TradeQuote of(TradeItemId itemId, int quantity, long unitValue) {
        Objects.requireNonNull(itemId, "itemId");
        if (quantity <= 0 || unitValue <= 0) {
            throw new IllegalArgumentException("Quantity and unit value must be positive");
        }
        return new TradeQuote(itemId, quantity, unitValue, Math.multiplyExact(unitValue, quantity));
    }
}
