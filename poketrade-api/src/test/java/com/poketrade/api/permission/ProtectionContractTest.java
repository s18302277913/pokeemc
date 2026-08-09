package com.poketrade.api.permission;

import com.poketrade.api.storage.StorageId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtectionContractTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final StorageId STORAGE = new StorageId("minecraft:overworld", "vanilla_chest", "0;64;0");
    private static final ProtectionProvider PROVIDER = new StubProvider("griefdefense",
            Set.of(ProtectionCapability.CLAIM_PROTECTION));

    @Test
    void resultHasThreeStates() {
        assertEquals(3, ProtectionResult.values().length);
        assertSame(ProtectionResult.DENY, ProtectionResult.valueOf("DENY"));
        assertSame(ProtectionResult.ALLOW, ProtectionResult.valueOf("ALLOW"));
        assertSame(ProtectionResult.NOT_APPLICABLE, ProtectionResult.valueOf("NOT_APPLICABLE"));
    }

    @Test
    void contextRequiresNonNullParts() {
        assertThrows(NullPointerException.class, () -> new ProtectionContext(null, STORAGE, ProtectionAction.BREAK));
        assertThrows(NullPointerException.class, () -> new ProtectionContext(ACTOR, null, ProtectionAction.BREAK));
        assertThrows(NullPointerException.class, () -> new ProtectionContext(ACTOR, STORAGE, null));
    }

    @Test
    void registryRejectsDuplicateModIds() {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.register(PROVIDER);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new StubProvider("griefdefense",
                        Set.of(ProtectionCapability.CLAIM_PROTECTION))));
    }

    @Test
    void registryListsAndLooksUpProviders() {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.register(PROVIDER);
        assertEquals(List.of(PROVIDER), registry.providers());
        assertEquals(Optional.of(PROVIDER), registry.byModId("griefdefense"));
        assertFalse(registry.byModId("unknown").isPresent());
    }

    /** 最小测试实现，供契约验证使用。 */
    static final class InMemoryRegistry implements ProtectionRegistry {
        private final Map<String, ProtectionProvider> byId = new LinkedHashMap<>();

        @Override
        public void register(ProtectionProvider provider) {
            if (provider.modId() == null || provider.modId().isBlank()) {
                throw new IllegalArgumentException("provider modId must be non-blank");
            }
            if (byId.containsKey(provider.modId())) {
                throw new IllegalArgumentException("Duplicate provider modId: " + provider.modId());
            }
            byId.put(provider.modId(), provider);
        }

        @Override
        public List<ProtectionProvider> providers() {
            return List.copyOf(byId.values());
        }

        @Override
        public Optional<ProtectionProvider> byModId(String modId) {
            return Optional.ofNullable(byId.get(modId));
        }
    }

    static final class StubProvider implements ProtectionProvider {
        private final String modId;
        private final Set<ProtectionCapability> capabilities;

        StubProvider(String modId, Set<ProtectionCapability> capabilities) {
            this.modId = modId;
            this.capabilities = Set.copyOf(capabilities);
        }

        @Override
        public String modId() {
            return modId;
        }

        @Override
        public Set<ProtectionCapability> capabilities() {
            return capabilities;
        }

        @Override
        public ProtectionResult check(ProtectionContext context) {
            return ProtectionResult.NOT_APPLICABLE;
        }
    }
}
