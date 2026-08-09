package com.poketrade.api;

import com.poketrade.api.capability.CapabilityProbe;
import com.poketrade.api.economy.EconomyRegistry;
import com.poketrade.api.permission.ProtectionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PokeTradeApiServiceLocatorTest {

    private static final PokeTradeApi STUB = new PokeTradeApi() {
        @Override
        public ProtectionRegistry protectionRegistry() {
            return null;
        }

        @Override
        public EconomyRegistry economyRegistry() {
            return null;
        }

        @Override
        public CapabilityProbe capabilityProbe() {
            return null;
        }
    };

    @AfterEach
    void resetInstance() {
        PokeTradeApi.set(null);
    }

    @Test
    void uninitializedReturnsNull() {
        assertNull(PokeTradeApi.get());
    }

    @Test
    void installedInstanceIsReturned() {
        PokeTradeApi.set(STUB);
        assertSame(STUB, PokeTradeApi.get());
    }
}
