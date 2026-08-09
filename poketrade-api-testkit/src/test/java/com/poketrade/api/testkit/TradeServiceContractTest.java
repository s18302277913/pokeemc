package com.poketrade.api.testkit;

import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeQuote;
import com.poketrade.api.TradeResult;
import com.poketrade.api.TradeService;

import java.util.Optional;
import java.util.UUID;

class TradeServiceContractTest extends TradeServiceContract {
    private static final TradeItemId DIAMOND = TradeItemId.parse("minecraft:diamond");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Override
    protected TradeService createService() {
        return new TradeService() {
            @Override
            public Optional<TradeQuote> quote(TradeItemId itemId, int quantity) {
                if (!DIAMOND.equals(itemId) || quantity <= 0) {
                    return Optional.empty();
                }
                return Optional.of(TradeQuote.of(itemId, quantity, 8_192));
            }

            @Override
            public TradeResult purchase(UUID playerId, TradeQuote quote) {
                if (!DIAMOND.equals(quote.itemId())) {
                    return TradeResult.UNKNOWN_ITEM;
                }
                return PLAYER.equals(playerId) ? TradeResult.SUCCESS : TradeResult.INSUFFICIENT_FUNDS;
            }
        };
    }

    @Override
    protected TradeItemId knownItem() {
        return DIAMOND;
    }

    @Override
    protected UUID fundedPlayer() {
        return PLAYER;
    }
}
