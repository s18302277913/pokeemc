package com.poketrade.api.market;

import com.poketrade.api.TradeItemId;
import java.util.Objects;

/** 一次交易的完整报价：单价与总价分离，客户端只展示、服务端结算前重新报价。 */
public record MarketQuote(TradeItemId itemId, int count, long unitBuyPrice, long unitSellPrice,
                          long totalCost, long totalValue) {

    public MarketQuote {
        Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        if (unitBuyPrice < 0 || unitSellPrice < 0) {
            throw new IllegalArgumentException("prices must be >= 0");
        }
        if (unitBuyPrice == 0 && unitSellPrice == 0) {
            throw new IllegalArgumentException("at least one of unitBuyPrice/unitSellPrice must be > 0");
        }
    }

    public static MarketQuote of(TradeItemId itemId, int count, long unitBuyPrice, long unitSellPrice) {
        long cost = Math.multiplyExact(unitBuyPrice, count);
        long value = Math.multiplyExact(unitSellPrice, count);
        return new MarketQuote(itemId, count, unitBuyPrice, unitSellPrice, cost, value);
    }

    public boolean buyAvailable() {
        return unitBuyPrice > 0;
    }

    public boolean sellAvailable() {
        return unitSellPrice > 0;
    }
}
