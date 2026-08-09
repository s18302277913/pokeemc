package com.pokeemc.thirdparty;

import com.poketrade.api.economy.EconomyAccount;
import com.poketrade.api.economy.EconomyBackend;
import com.pokeemc.trade.asset.WalletAccount;
import com.pokeemc.trade.asset.WalletPort;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 内置 Pixelmon 经济兜底：把 {@link WalletPort} 适配为 {@link EconomyBackend}。
 * 无第三方后端注册时，调用方通过本实现保持现有 Pixelmon 行为。
 */
public final class DefaultEconomyBackend implements EconomyBackend {

    private final WalletPort wallet;

    public DefaultEconomyBackend(WalletPort wallet) {
        this.wallet = Objects.requireNonNull(wallet, "wallet");
    }

    @Override
    public String backendId() {
        return "pixelmon";
    }

    @Override
    public Optional<EconomyAccount> account(UUID playerId) {
        return wallet.find(playerId).map(WalletAccountAdapter::new);
    }

    private static final class WalletAccountAdapter implements EconomyAccount {
        private final WalletAccount delegate;

        private WalletAccountAdapter(WalletAccount delegate) {
            this.delegate = delegate;
        }

        @Override
        public long balance() {
            return delegate.balance();
        }

        @Override
        public boolean debit(long amount) {
            return delegate.debit(amount);
        }

        @Override
        public boolean credit(long amount) {
            return delegate.credit(amount);
        }

        @Override
        public boolean supportsIdempotency() {
            return delegate.supportsIdempotency();
        }
    }
}
