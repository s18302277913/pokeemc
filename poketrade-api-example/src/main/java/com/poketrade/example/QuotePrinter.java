package com.poketrade.example;

import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeQuote;
import com.poketrade.api.TradeService;

public final class QuotePrinter {
    public static String format(TradeService service, String itemId, int quantity) {
        TradeQuote quote = service.quote(TradeItemId.parse(itemId), quantity).orElseThrow();
        return quote.itemId() + " x" + quote.quantity() + " = " + quote.totalCost() + " PKM";
    }

    private QuotePrinter() {
    }
}
