package com.poketrade.api.testkit;

import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeQuote;
import com.poketrade.api.TradeResult;
import com.poketrade.api.TradeService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class TradeServiceContract {
    protected abstract TradeService createService();

    protected abstract TradeItemId knownItem();

    protected abstract UUID fundedPlayer();

    @Test
    void rejectsNonPositiveQuoteQuantity() {
        assertTrue(createService().quote(knownItem(), 0).isEmpty());
    }

    @Test
    void returnsUnknownItemForUnpricedQuote() {
        TradeQuote quote = TradeQuote.of(TradeItemId.parse("example:missing"), 1, 1);
        assertEquals(TradeResult.UNKNOWN_ITEM, createService().purchase(fundedPlayer(), quote));
    }

    @Test
    void purchasesAnIssuedQuoteForFundedPlayer() {
        TradeService service = createService();
        TradeQuote quote = service.quote(knownItem(), 1).orElseThrow();
        assertEquals(TradeResult.SUCCESS, service.purchase(fundedPlayer(), quote));
    }
}
