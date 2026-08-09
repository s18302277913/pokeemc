package com.pokeemc.thirdparty.protection;

import com.poketrade.api.permission.ProtectionAction;
import com.poketrade.api.permission.ProtectionCapability;
import com.poketrade.api.permission.ProtectionContext;
import com.poketrade.api.permission.ProtectionProvider;
import com.poketrade.api.permission.ProtectionResult;
import com.poketrade.api.storage.StorageId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtectionChainTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final StorageId STORAGE =
            new StorageId("minecraft:overworld", "vanilla_chest", "0;64;0");
    private static final ProtectionContext CONTEXT =
            new ProtectionContext(ACTOR, STORAGE, ProtectionAction.BREAK);

    private static ProtectionProvider provider(String modId, ProtectionResult result) {
        return new ProtectionProvider() {
            @Override
            public String modId() {
                return modId;
            }

            @Override
            public Set<ProtectionCapability> capabilities() {
                return Set.of(ProtectionCapability.LOCK_PROTECTION);
            }

            @Override
            public ProtectionResult check(ProtectionContext context) {
                return result;
            }
        };
    }

    private static ProtectionProvider throwing(String modId) {
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
                throw new IllegalStateException("boom");
            }
        };
    }

    /** 记录审计回调调用的内部桩。 */
    static final class RecordingAudit implements ProtectionAudit {
        final List<String> denied = new ArrayList<>();
        final List<String> allowed = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void onDenied(String modId, ProtectionContext context) {
            denied.add(modId);
        }

        @Override
        public void onAllowed(String modId, ProtectionContext context) {
            allowed.add(modId);
        }

        @Override
        public void onProviderError(String modId, ProtectionContext context, RuntimeException error) {
            errors.add(modId);
        }
    }

    @Test
    void emptyChainAllows() {
        assertTrue(new ProtectionChain(List.of(), new RecordingAudit()).allows(CONTEXT));
    }

    @Test
    void allNotApplicableAllows() {
        ProtectionChain chain = new ProtectionChain(List.of(
                provider("a", ProtectionResult.NOT_APPLICABLE),
                provider("b", ProtectionResult.NOT_APPLICABLE)), new RecordingAudit());
        assertTrue(chain.allows(CONTEXT));
    }

    @Test
    void anyDenyRejects() {
        RecordingAudit audit = new RecordingAudit();
        ProtectionChain chain = new ProtectionChain(List.of(
                provider("a", ProtectionResult.NOT_APPLICABLE),
                provider("b", ProtectionResult.DENY)), audit);
        assertFalse(chain.allows(CONTEXT));
        assertEquals(List.of("b"), audit.denied);
    }

    @Test
    void allowShortCircuits() {
        RecordingAudit audit = new RecordingAudit();
        ProtectionChain chain = new ProtectionChain(List.of(
                provider("a", ProtectionResult.ALLOW),
                provider("b", ProtectionResult.DENY)), audit);
        assertTrue(chain.allows(CONTEXT));
        assertEquals(List.of("a"), audit.allowed);
        assertTrue(audit.denied.isEmpty());
    }

    @Test
    void denyBeforeAllowRejects() {
        ProtectionChain chain = new ProtectionChain(List.of(
                provider("a", ProtectionResult.DENY),
                provider("b", ProtectionResult.ALLOW)), new RecordingAudit());
        assertFalse(chain.allows(CONTEXT));
    }

    @Test
    void exceptionTreatedAsNotApplicable() {
        RecordingAudit audit = new RecordingAudit();
        ProtectionChain chain = new ProtectionChain(List.of(
                throwing("a"),
                provider("b", ProtectionResult.NOT_APPLICABLE)), audit);
        assertTrue(chain.allows(CONTEXT));
        assertEquals(List.of("a"), audit.errors);
    }
}
