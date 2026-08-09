package com.poketrade.api.storage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageAdapterRegistryTest {

    private static final StorageAdapter CHEST_ADAPTER = new StubAdapter(
            "vanilla_chest", Set.of(StorageCapability.SNAPSHOT, StorageCapability.INSERT,
            StorageCapability.EXTRACT, StorageCapability.SELL_SOURCE));

    private static final StorageAdapter DUP_ADAPTER = new StubAdapter(
            "vanilla_chest", Set.of(StorageCapability.SNAPSHOT));

    @Test
    void registryRejectsDuplicateTypeIds() {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.register(CHEST_ADAPTER);
        assertThrows(IllegalArgumentException.class, () -> registry.register(DUP_ADAPTER));
    }

    @Test
    void registryLooksUpByTypeId() {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.register(CHEST_ADAPTER);
        assertEquals(Optional.of(CHEST_ADAPTER), registry.byTypeId("vanilla_chest"));
        assertFalse(registry.byTypeId("unknown").isPresent());
        assertEquals(Set.of("vanilla_chest"), registry.typeIds());
    }

    /** 最小测试实现，供契约验证使用。 */
    static final class InMemoryRegistry implements StorageAdapterRegistry {
        private final Map<String, StorageAdapter> byId = new LinkedHashMap<>();

        @Override
        public void register(StorageAdapter adapter) {
            if (byId.containsKey(adapter.typeId())) {
                throw new IllegalArgumentException("Duplicate adapter typeId: " + adapter.typeId());
            }
            byId.put(adapter.typeId(), adapter);
        }

        @Override
        public Optional<StorageAdapter> byTypeId(String typeId) {
            return Optional.ofNullable(byId.get(typeId));
        }

        @Override
        public Set<String> typeIds() {
            return Set.copyOf(byId.keySet());
        }
    }

    static final class StubAdapter implements StorageAdapter {
        private final String typeId;
        private final Set<StorageCapability> capabilities;

        StubAdapter(String typeId, Set<StorageCapability> capabilities) {
            this.typeId = typeId;
            this.capabilities = Set.copyOf(capabilities);
        }

        @Override
        public String typeId() {
            return typeId;
        }

        @Override
        public Set<StorageCapability> capabilities() {
            return capabilities;
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
}
