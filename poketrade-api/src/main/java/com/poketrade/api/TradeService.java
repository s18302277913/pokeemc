package com.poketrade.api;

import java.util.Optional;
import java.util.UUID;

public interface TradeService {
    Optional<TradeQuote> quote(TradeItemId itemId, int quantity);

    TradeResult purchase(UUID playerId, TradeQuote quote);
}
