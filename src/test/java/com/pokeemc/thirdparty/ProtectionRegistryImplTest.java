package com.pokeemc.thirdparty;

import com.poketrade.api.permission.ProtectionCapability;
import com.poketrade.api.permission.ProtectionContext;
import com.poketrade.api.permission.ProtectionProvider;
import com.poketrade.api.permission.ProtectionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtectionRegistryImplTest {

    private static ProtectionProvider provider(String modId) {
        return new ProtectionProvider() {
            @Override
            public String modId() {
                return modId;
            }

            @Override
            public Set<ProtectionCapability> capabilities() {
                return Set.of(ProtectionCapability.CLAIM_PROTECTION);
            }

            @Override
            public ProtectionResult check(ProtectionContext context) {
                return ProtectionResult.NOT_APPLICABLE;
            }
        };
    }

    @Test
    void registersAndPreservesOrder() {
        ProtectionRegistryImpl registry = new ProtectionRegistryImpl();
        ProtectionProvider a = provider("griefdefense");
        ProtectionProvider b = provider("lockettepro");
        registry.register(a);
        registry.register(b);
        assertEquals(List.of(a, b), registry.providers());
        assertEquals(2, registry.size());
    }

    @Test
    void rejectsDuplicateModId() {
        ProtectionRegistryImpl registry = new ProtectionRegistryImpl();
        registry.register(provider("griefdefense"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(provider("griefdefense")));
    }

    @Test
    void rejectsApiVersionMismatch() {
        ProtectionRegistryImpl registry = new ProtectionRegistryImpl();
        ProtectionProvider wrongVersion = new ProtectionProvider() {
            @Override
            public String modId() {
                return "future-mod";
            }

            @Override
            public Set<ProtectionCapability> capabilities() {
                return Set.of();
            }

            @Override
            public int apiVersion() {
                return 999;
            }

            @Override
            public ProtectionResult check(ProtectionContext context) {
                return ProtectionResult.NOT_APPLICABLE;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> registry.register(wrongVersion));
    }

    @Test
    void looksUpByModId() {
        ProtectionRegistryImpl registry = new ProtectionRegistryImpl();
        ProtectionProvider a = provider("griefdefense");
        registry.register(a);
        assertEquals(Optional.of(a), registry.byModId("griefdefense"));
        assertFalse(registry.byModId("unknown").isPresent());
    }
}
