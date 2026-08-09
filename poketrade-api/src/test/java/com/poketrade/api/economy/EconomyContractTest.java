package com.poketrade.api.economy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EconomyContractTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final EconomyBackend PIXELMON = new StubBackend("pixelmon");

    @Test
    void accountDebitsWithoutPartial() {
        InMemoryAccount account = new InMemoryAccount(100);
        assertTrue(account.debit(60));
        assertEquals(40, account.balance());
        assertFalse(account.debit(100)); // 余额不足 → false，不部分扣款
        assertEquals(40, account.balance());
        assertTrue(account.credit(10));
        assertEquals(50, account.balance());
    }

    @Test
    void accountDoesNotSupportIdempotencyByDefault() {
        EconomyAccount account = new InMemoryAccount(100);
        assertFalse(account.supportsIdempotency());
    }

    @Test
    void registryRejectsDuplicateBackendIds() {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.register(PIXELMON);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new StubBackend("pixelmon")));
    }

    @Test
    void registryReportsActiveBackend() {
        InMemoryRegistry registry = new InMemoryRegistry();
        assertFalse(registry.activeBackend().isPresent());
        registry.register(PIXELMON);
        assertEquals(Optional.of(PIXELMON), registry.activeBackend());
    }

    /** 最小测试实现，供契约验证使用。 */
    static final class InMemoryRegistry implements EconomyRegistry {
        private final Map<String, EconomyBackend> byId = new LinkedHashMap<>();

        @Override
        public void register(EconomyBackend backend) {
            if (backend.backendId() == null || backend.backendId().isBlank()) {
                throw new IllegalArgumentException("backend backendId must be non-blank");
            }
            if (byId.containsKey(backend.backendId())) {
                throw new IllegalArgumentException("Duplicate backend backendId: " + backend.backendId());
            }
            byId.put(backend.backendId(), backend);
        }

        @Override
        public Optional<EconomyBackend> activeBackend() {
            return byId.values().stream().findFirst();
        }
    }

    static final class StubBackend implements EconomyBackend {
        private final String backendId;

        StubBackend(String backendId) {
            this.backendId = backendId;
        }

        @Override
        public String backendId() {
            return backendId;
        }

        @Override
        public Optional<EconomyAccount> account(UUID playerId) {
            return Optional.of(new InMemoryAccount(0));
        }
    }

    static final class InMemoryAccount implements EconomyAccount {
        private long balance;

        InMemoryAccount(long balance) {
            this.balance = balance;
        }

        @Override
        public long balance() {
            return balance;
        }

        @Override
        public boolean debit(long amount) {
            if (amount <= 0) {
                return true;
            }
            if (balance < amount) {
                return false;
            }
            balance -= amount;
            return true;
        }

        @Override
        public boolean credit(long amount) {
            if (amount <= 0) {
                return true;
            }
            balance += amount;
            return true;
        }

        @Override
        public boolean supportsIdempotency() {
            return false;
        }
    }
}
