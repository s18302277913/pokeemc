package com.poketrade.api.id;

import com.poketrade.api.TradeItemId;

public final class RegistryIdMigration {
    public static TradeItemId toCurrent(TradeItemId id) {
        if (!PokeTradeIds.LEGACY_NAMESPACE.equals(id.namespace()) || !PokeTradeIds.allApprovedPaths().contains(id.path())) {
            return id;
        }
        return PokeTradeIds.current(id.path());
    }

    private RegistryIdMigration() {
    }
}
