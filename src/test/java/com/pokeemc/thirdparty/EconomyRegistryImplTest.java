package com.pokeemc.thirdparty;

import com.poketrade.api.economy.EconomyAccount;
import com.poketrade.api.economy.EconomyBackend;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EconomyRegistryImplTest {

    private static EconomyBackend backend(String id) {
        return new EconomyBackend() {
            @Override
            public String backendId() {
                return id;
            }

            @Override
            public Optional<EconomyAccount> account(UUID playerId) {
                return Optional.empty();
            }
        };
    }

    @Test
    void activeBackendEmptyWhenNothingRegistered() {
        assertFalse(new EconomyRegistryImpl().activeBackend().isPresent());
    }

    @Test
    void firstRegisteredBecomesActive() {
        EconomyRegistryImpl registry = new EconomyRegistryImpl();
        EconomyBackend pixelmon = backend("pixelmon");
        registry.register(pixelmon);
        assertEquals(Optional.of(pixelmon), registry.activeBackend());
    }

    @Test
    void laterRegistrationsDoNotReplaceActive() {
        EconomyRegistryImpl registry = new EconomyRegistryImpl();
        EconomyBackend first = backend("pixelmon");
        EconomyBackend second = backend("vault");
        registry.register(first);
        registry.register(second);
        assertEquals(Optional.of(first), registry.activeBackend());
        assertTrue(registry.backends().contains(second));
    }

    @Test
    void rejectsDuplicateBackendId() {
        EconomyRegistryImpl registry = new EconomyRegistryImpl();
        registry.register(backend("pixelmon"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(backend("pixelmon")));
    }

    @Test
    void rejectsApiVersionMismatch() {
        EconomyRegistryImpl registry = new EconomyRegistryImpl();
        EconomyBackend wrongVersion = new EconomyBackend() {
            @Override
            public String backendId() {
                return "future-backend";
            }

            @Override
            public int apiVersion() {
                return 999;
            }

            @Override
            public Optional<EconomyAccount> account(UUID playerId) {
                return Optional.empty();
            }
        };
        assertThrows(IllegalArgumentException.class, () -> registry.register(wrongVersion));
    }
}
