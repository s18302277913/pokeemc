package com.poketrade.example;

import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeQuote;
import com.poketrade.api.TradeResult;
import com.poketrade.api.TradeService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuotePrinterTest {
    @Test
    void printsPublicQuoteWithoutGameTypes() {
        TradeService service = new TradeService() {
            @Override
            public Optional<TradeQuote> quote(TradeItemId itemId, int quantity) {
                return Optional.of(TradeQuote.of(itemId, quantity, 8_192));
            }

            @Override
            public TradeResult purchase(UUID playerId, TradeQuote quote) {
                return TradeResult.SUCCESS;
            }
        };

        assertEquals("minecraft:diamond x3 = 24576 PKM", QuotePrinter.format(service, "minecraft:diamond", 3));
    }
}
