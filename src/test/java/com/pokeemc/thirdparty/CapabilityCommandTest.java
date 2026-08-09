package com.pokeemc.thirdparty;

import com.poketrade.api.economy.EconomyAccount;
import com.poketrade.api.economy.EconomyBackend;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityCommandTest {

    @Test
    void reportContainsAllSections() {
        CapabilityProbeImpl probe = new CapabilityProbeImpl(
                new ProtectionRegistryImpl(), new EconomyRegistryImpl(),
                new StorageAdapterRegistryImpl(), Set.of());
        String report = CapabilityCommand.buildReport(probe);
        assertTrue(report.contains("apiVersion: 1"));
        assertTrue(report.contains("容器适配器 (0)"));
        assertTrue(report.contains("保护 Provider (0)"));
        assertTrue(report.contains("经济后端 (0)"));
        assertTrue(report.contains("未适配第三方 (0)"));
    }

    @Test
    void reportListsEntriesAndUnadaptedMods() {
        EconomyRegistryImpl economy = new EconomyRegistryImpl();
        economy.register(new EconomyBackend() {
            @Override
            public String backendId() {
                return "vault";
            }

            @Override
            public Optional<EconomyAccount> account(UUID playerId) {
                return Optional.empty();
            }
        });
        CapabilityProbeImpl probe = new CapabilityProbeImpl(
                new ProtectionRegistryImpl(), economy,
                new StorageAdapterRegistryImpl(), Set.of("vault", "griefdefense"));
        String report = CapabilityCommand.buildReport(probe);
        assertTrue(report.contains("经济后端 (1)"));
        assertTrue(report.contains("- vault ("));
        assertTrue(report.contains("未适配第三方 (1)"));
        assertTrue(report.contains("- griefdefense"));
    }
}
