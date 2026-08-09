package com.poketrade.api.price;

import com.poketrade.api.TradeItemId;
import java.util.Objects;

/** 单物品买卖双价报价。买价/卖价独立，禁止单一数值双向交易。 */
public record PriceQuote(TradeItemId itemId, long buyPrice, long sellPrice, PriceSource source) {
    public PriceQuote {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(source, "source");
        if (buyPrice < 0 || sellPrice < 0) {
            throw new IllegalArgumentException("prices must be >= 0");
        }
        if (buyPrice == 0 && sellPrice == 0) {
            throw new IllegalArgumentException("at least one of buy/sell price must be > 0");
        }
    }

    public static PriceQuote of(TradeItemId itemId, long buyPrice, long sellPrice, PriceSource source) {
        return new PriceQuote(itemId, buyPrice, sellPrice, source);
    }

    /** 是否可购买（买入价 > 0）。 */
    public boolean buyAvailable() {
        return buyPrice > 0;
    }

    /** 是否可出售（回收价 > 0）。 */
    public boolean sellAvailable() {
        return sellPrice > 0;
    }
}
