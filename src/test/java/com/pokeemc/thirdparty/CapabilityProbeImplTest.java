package com.pokeemc.thirdparty;

import com.poketrade.api.PokeTradeApi;
import com.poketrade.api.capability.CapabilityEntry;
import com.poketrade.api.economy.EconomyAccount;
import com.poketrade.api.economy.EconomyBackend;
import com.poketrade.api.permission.ProtectionCapability;
import com.poketrade.api.permission.ProtectionContext;
import com.poketrade.api.permission.ProtectionProvider;
import com.poketrade.api.permission.ProtectionResult;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageHandle;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityProbeImplTest {

    private final ProtectionRegistryImpl protection = new ProtectionRegistryImpl();
    private final EconomyRegistryImpl economy = new EconomyRegistryImpl();
    private final StorageAdapterRegistryImpl storage = new StorageAdapterRegistryImpl();

    private static ProtectionProvider provider(String modId) {
        return new ProtectionProvider() {
            @Override
            public String modId() {
                return modId;
            }

            @Override
            public Set<ProtectionCapability> capabilities() {
                return Set.of();
            }

            @Override
            public ProtectionResult check(ProtectionContext context) {
                return ProtectionResult.NOT_APPLICABLE;
            }
        };
    }

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

    private static StorageAdapter adapter(String typeId) {
        return new StorageAdapter() {
            @Override
            public String typeId() {
                return typeId;
            }

            @Override
            public Set<StorageCapability> capabilities() {
                return Set.of();
            }

            @Override
            public boolean supports(StorageAdapterContext context) {
                return false;
            }

            @Override
            public Optional<StorageHandle> open(StorageAdapterContext context) {
                return Optional.empty();
            }
        };
    }

    private CapabilityProbeImpl probe(Set<String> loaded) {
        return new CapabilityProbeImpl(protection, economy, storage, loaded);
    }

    @Test
    void apiVersionMatchesApiConstant() {
        assertEquals(PokeTradeApi.API_VERSION, probe(Set.of()).apiVersion());
    }

    @Test
    void aggregatesAllRegistries() {
        protection.register(provider("griefdefense"));
        economy.register(backend("vault"));
        storage.register(adapter("test_chest"));

        CapabilityProbeImpl probe = probe(Set.of());
        List<CapabilityEntry> providers = probe.protectionProviders();
        assertEquals(1, providers.size());
        assertEquals("griefdefense", providers.get(0).id());
        assertTrue(providers.get(0).active());

        List<CapabilityEntry> backends = probe.economyBackends();
        assertEquals(1, backends.size());
        assertEquals("vault", backends.get(0).id());

        assertTrue(probe.storageAdapters().stream()
                .map(CapabilityEntry::id)
                .anyMatch("test_chest"::equals));
    }

    @Test
    void unadaptedModsExcludesAdaptedAndUnloaded() {
        economy.register(backend("vault"));
        assertEquals(List.of("griefdefense"),
                probe(Set.of("vault", "griefdefense", "unknown-mod")).unadaptedMods());
    }
}
