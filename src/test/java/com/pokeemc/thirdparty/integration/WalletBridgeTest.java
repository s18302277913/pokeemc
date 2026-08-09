package com.pokeemc.thirdparty.integration;

import com.poketrade.api.economy.EconomyAccount;
import com.poketrade.api.economy.EconomyBackend;
import com.pokeemc.thirdparty.DefaultEconomyBackend;
import com.pokeemc.thirdparty.EconomyRegistryImpl;
import com.pokeemc.trade.asset.WalletAccount;
import com.pokeemc.trade.asset.WalletPort;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WalletBridgeTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static WalletPort pixelmon(long balance) {
        return playerId -> Optional.of(new WalletAccount() {
            @Override public long balance() { return balance; }
            @Override public boolean debit(long amount) { return amount <= balance; }
            @Override public boolean credit(long amount) { return true; }
            @Override public boolean supportsIdempotency() { return false; }
        });
    }

    private static EconomyBackend backend(String id, long balance) {
        return new EconomyBackend() {
            @Override public String backendId() { return id; }
            @Override public Optional<EconomyAccount> account(UUID playerId) {
                return Optional.of(new EconomyAccount() {
                    @Override public long balance() { return balance; }
                    @Override public boolean debit(long amount) { return amount <= balance; }
                    @Override public boolean credit(long amount) { return true; }
                    @Override public boolean supportsIdempotency() { return false; }
                });
            }
        };
    }

    private static EconomyBackend backendWithoutAccount(String id) {
        return new EconomyBackend() {
            @Override public String backendId() { return id; }
            @Override public Optional<EconomyAccount> account(UUID playerId) { return Optional.empty(); }
        };
    }

    private static WalletBridge bridge(EconomyRegistryImpl registry, long pixelmonBalance) {
        return new WalletBridge(registry, new DefaultEconomyBackend(pixelmon(pixelmonBalance)));
    }

    @Test
    void usesPixelmonFallbackWhenNoBackendRegistered() {
        WalletPort port = bridge(new EconomyRegistryImpl(), 7).walletPort();
        assertEquals(7, port.find(PLAYER).orElseThrow().balance());
    }

    @Test
    void prefersActiveBackendOverFallback() {
        EconomyRegistryImpl registry = new EconomyRegistryImpl();
        registry.register(backend("vault", 500));
        WalletPort port = bridge(registry, 7).walletPort();
        assertEquals(500, port.find(PLAYER).orElseThrow().balance());
    }

    @Test
    void fallsBackWhenActiveBackendHasNoAccount() {
        EconomyRegistryImpl registry = new EconomyRegistryImpl();
        registry.register(backendWithoutAccount("vault"));
        WalletPort port = bridge(registry, 7).walletPort();
        assertEquals(7, port.find(PLAYER).orElseThrow().balance());
    }

    @Test
    void delegatesDebitThroughActiveBackend() {
        EconomyRegistryImpl registry = new EconomyRegistryImpl();
        registry.register(backend("vault", 100));
        WalletPort port = bridge(registry, 7).walletPort();
        assertTrue(port.find(PLAYER).orElseThrow().debit(50));
        assertFalse(port.find(PLAYER).orElseThrow().debit(200));
    }

    @Test
    void returnsEmptyWhenBothUnavailable() {
        EconomyRegistryImpl registry = new EconomyRegistryImpl();
        registry.register(backendWithoutAccount("vault"));
        WalletBridge bridge = new WalletBridge(registry,
                new DefaultEconomyBackend(playerId -> Optional.empty()));
        assertFalse(bridge.walletPort().find(PLAYER).isPresent());
    }
}
