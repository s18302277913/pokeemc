package com.pokeemc.thirdparty;

import com.poketrade.api.economy.EconomyAccount;
import com.poketrade.api.economy.EconomyBackend;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ThirdPartyProbeTest {

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

    private static ThirdPartyProbe probe(ProtectionRegistryImpl protection,
                                         EconomyRegistryImpl economy,
                                         StorageAdapterRegistryImpl storage) {
        return new ThirdPartyProbe(protection, economy, storage);
    }

    @Test
    void knownModIdsCoverExpectedThirdParties() {
        assertEquals(Set.of("griefdefense", "worldguard", "lockettepro", "vault", "ironchest"),
                ThirdPartyProbe.KNOWN_MOD_IDS);
    }

    @Test
    void isAdaptedReflectsRegistrations() {
        ProtectionRegistryImpl protection = new ProtectionRegistryImpl();
        EconomyRegistryImpl economy = new EconomyRegistryImpl();
        StorageAdapterRegistryImpl storage = new StorageAdapterRegistryImpl();
        ThirdPartyProbe probe = probe(protection, economy, storage);

        assertFalse(probe.isAdapted("vault"));
        economy.register(backend("vault"));
        assertTrue(probe.isAdapted("vault"));
        // 未知模组不猜测
        assertFalse(probe.isAdapted("some-unknown-mod"));
    }

    @Test
    void unadaptedModsFiltersAdaptedAndUnknown() {
        EconomyRegistryImpl economy = new EconomyRegistryImpl();
        economy.register(backend("vault"));
        ThirdPartyProbe probe = probe(new ProtectionRegistryImpl(), economy,
                new StorageAdapterRegistryImpl());
        assertEquals(List.of("griefdefense"),
                probe.unadaptedMods(Set.of("vault", "griefdefense", "unknown-mod")));
    }
}
