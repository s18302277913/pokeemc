package com.pokeemc.thirdparty;

import com.poketrade.api.economy.EconomyAccount;
import com.pokeemc.trade.asset.WalletAccount;
import com.pokeemc.trade.asset.WalletPort;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DefaultEconomyBackendTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static WalletPort wallet(long balance) {
        return playerId -> Optional.of(new WalletAccount() {
            private long current = balance;

            @Override
            public long balance() {
                return current;
            }

            @Override
            public boolean debit(long amount) {
                if (amount <= 0) {
                    return true;
                }
                if (amount > current) {
                    return false; // 余额不足 → 不部分扣款
                }
                current -= amount;
                return true;
            }

            @Override
            public boolean credit(long amount) {
                current += amount;
                return true;
            }

            @Override
            public boolean supportsIdempotency() {
                return false;
            }
        });
    }

    @Test
    void exposesPixelmonBackendId() {
        DefaultEconomyBackend backend = new DefaultEconomyBackend(wallet(100));
        assertEquals("pixelmon", backend.backendId());
    }

    @Test
    void delegatesBalanceToWallet() {
        DefaultEconomyBackend backend = new DefaultEconomyBackend(wallet(42));
        EconomyAccount account = backend.account(PLAYER).orElseThrow();
        assertEquals(42, account.balance());
        assertFalse(account.supportsIdempotency());
    }

    @Test
    void returnsEmptyWhenWalletUnavailable() {
        DefaultEconomyBackend backend = new DefaultEconomyBackend(playerId -> Optional.empty());
        assertFalse(backend.account(PLAYER).isPresent());
    }

    @Test
    void delegatesDebitToWallet() {
        DefaultEconomyBackend backend = new DefaultEconomyBackend(wallet(100));
        EconomyAccount account = backend.account(PLAYER).orElseThrow();
        assertTrue(account.debit(60));
        assertEquals(40, account.balance());
        assertFalse(account.debit(100)); // 余额不足 → false，不部分扣款
    }
}
