package com.poketrade.api.capability;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityContractTest {

    @Test
    void entryRecordCarriesFields() {
        CapabilityEntry entry = new CapabilityEntry("vanilla_chest", "com.pokeemc.Storage", true);
        assertEquals("vanilla_chest", entry.id());
        assertEquals("com.pokeemc.Storage", entry.implementation());
        assertTrue(entry.active());
    }

    @Test
    void categoryCoversAllAdapterKinds() {
        assertEquals(3, AdapterCategory.values().length);
        assertNotNull(AdapterCategory.valueOf("CONTAINER"));
        assertNotNull(AdapterCategory.valueOf("PROTECTION"));
        assertNotNull(AdapterCategory.valueOf("ECONOMY"));
    }

    @Test
    void probeReturnsSnapshots() {
        CapabilityProbe probe = new StubProbe();
        assertEquals(1, probe.apiVersion());
        assertEquals(List.of(), probe.protectionProviders());
        assertEquals(List.of(new CapabilityEntry("pixelmon", "stub", true)), probe.economyBackends());
        assertEquals(List.of("vault"), probe.unadaptedMods());
    }

    static final class StubProbe implements CapabilityProbe {
        @Override
        public int apiVersion() {
            return 1;
        }

        @Override
        public List<CapabilityEntry> protectionProviders() {
            return List.of();
        }

        @Override
        public List<CapabilityEntry> economyBackends() {
            return List.of(new CapabilityEntry("pixelmon", "stub", true));
        }

        @Override
        public List<CapabilityEntry> storageAdapters() {
            return List.of();
        }

        @Override
        public List<String> unadaptedMods() {
            return List.of("vault");
        }
    }
}
