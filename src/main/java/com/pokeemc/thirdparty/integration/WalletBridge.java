package com.pokeemc.thirdparty.integration;

import com.poketrade.api.economy.EconomyAccount;
import com.poketrade.api.economy.EconomyBackend;
import com.pokeemc.thirdparty.DefaultEconomyBackend;
import com.pokeemc.thirdparty.EconomyRegistryImpl;
import com.pokeemc.trade.asset.WalletAccount;
import com.pokeemc.trade.asset.WalletPort;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 经济后端选择器：activeBackend 存在且能提供账户 → 用它；否则回退内置 Pixelmon。
 * 把 {@link EconomyAccount} 适配回 {@link WalletAccount}，维持现有交易代码零改动。
 */
public final class WalletBridge {

    private final EconomyRegistryImpl registry;
    private final DefaultEconomyBackend fallback;

    public WalletBridge(EconomyRegistryImpl registry, DefaultEconomyBackend fallback) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /** 返回适配后的 {@link WalletPort}：供交易链路直接使用。 */
    public WalletPort walletPort() {
        return playerId -> resolveAccount(playerId).map(WalletAccountAdapter::new);
    }

    private Optional<EconomyAccount> resolveAccount(UUID playerId) {
        EconomyBackend active = registry.activeBackend().orElse(null);
        if (active != null) {
            Optional<EconomyAccount> account = active.account(playerId);
            if (account.isPresent()) {
                return account;
            }
            // 后端存在但离线/无实现：不猜测，回退内置
        }
        return fallback.account(playerId);
    }

    private static final class WalletAccountAdapter implements WalletAccount {
        private final EconomyAccount delegate;

        private WalletAccountAdapter(EconomyAccount delegate) {
            this.delegate = delegate;
        }

        @Override public long balance() { return delegate.balance(); }
        @Override public boolean debit(long amount) { return delegate.debit(amount); }
        @Override public boolean credit(long amount) { return delegate.credit(amount); }
        @Override public boolean supportsIdempotency() { return delegate.supportsIdempotency(); }
    }
}
