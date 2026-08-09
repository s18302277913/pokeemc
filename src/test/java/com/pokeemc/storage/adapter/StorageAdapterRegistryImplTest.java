package com.pokeemc.storage.adapter;

import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageHandle;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageAdapterRegistryImplTest {

    private static final class FakeAdapter implements StorageAdapter {
        private final String typeId;

        FakeAdapter(String typeId) {
            this.typeId = typeId;
        }

        @Override
        public String typeId() {
            return typeId;
        }

        @Override
        public Set<StorageCapability> capabilities() {
            return Set.of(StorageCapability.SNAPSHOT, StorageCapability.INSERT);
        }

        @Override
        public boolean supports(StorageAdapterContext context) {
            return true;
        }

        @Override
        public Optional<StorageHandle> open(StorageAdapterContext context) {
            return Optional.empty();
        }
    }

    @Test
    void registersAndQueriesAdaptersInOrder() {
        StorageAdapterRegistryImpl registry = new StorageAdapterRegistryImpl();
        StorageAdapter chest = new FakeAdapter("vanilla_chest");
        StorageAdapter barrel = new FakeAdapter("vanilla_barrel");
        registry.register(chest);
        registry.register(barrel);
        assertEquals(2, registry.size());
        assertEquals(chest, registry.byTypeId("vanilla_chest").orElseThrow());
        assertEquals(barrel, registry.byTypeId("vanilla_barrel").orElseThrow());
        assertTrue(registry.byTypeId("vanilla_double_chest").isEmpty());
        assertTrue(registry.isRegistered("vanilla_chest"));
        assertFalse(registry.isRegistered("unknown"));
        assertEquals(Set.of("vanilla_chest", "vanilla_barrel"), registry.typeIds());
    }

    @Test
    void duplicateTypeIdIsRejected() {
        StorageAdapterRegistryImpl registry = new StorageAdapterRegistryImpl();
        registry.register(new FakeAdapter("vanilla_chest"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new FakeAdapter("vanilla_chest")));
        assertEquals(1, registry.size(), "duplicate must not be added");
    }

    @Test
    void blankTypeIdIsRejected() {
        StorageAdapterRegistryImpl registry = new StorageAdapterRegistryImpl();
        assertThrows(IllegalArgumentException.class, () -> registry.register(new FakeAdapter("")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new FakeAdapter("  ")));
        assertEquals(0, registry.size());
    }

    @Test
    void canonicalizeDelegatesToStorageAdapterExt() {
        StorageAdapterRegistryImpl registry = new StorageAdapterRegistryImpl();
        registry.register(new FakeAdapter("vanilla_chest")); // 非 StorageAdapterExt
        // 非扩展适配器：原样返回
        com.pokeemc.storage.StorageKey key = com.pokeemc.storage.StorageKey.of(
                "minecraft:overworld", "vanilla_chest", "10;64;20");
        assertEquals(key, registry.canonicalize(key));
    }

    @Test
    void typeIdsReturnsSnapshot() {
        StorageAdapterRegistryImpl registry = new StorageAdapterRegistryImpl();
        registry.register(new FakeAdapter("vanilla_chest"));
        Set<String> ids = registry.typeIds();
        assertThrows(UnsupportedOperationException.class, () -> ids.add("x"));
    }
}
