package com.pokeemc.trade.service;

import com.pokeemc.PokeEMC;
import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.trade.asset.DeliveryResult;
import com.pokeemc.trade.asset.ItemEscrowGateway;
import com.pokeemc.trade.asset.OperationLedger;
import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.asset.PkmEscrowGateway;
import com.pokeemc.trade.asset.PlayerInventoryStore;
import com.pokeemc.trade.asset.PokemonEscrowGateway;
import com.pokeemc.trade.asset.PokemonLocation;
import com.pokeemc.trade.asset.PokemonStoragePort;
import com.pokeemc.trade.asset.WalletPort;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.persistence.TradeSavedData;
import com.pokeemc.thirdparty.ThirdPartyServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.time.Clock;
import java.util.UUID;

/**
 * 生产装配（Task 11 步骤 3）：服务端启动时把所有 port / 策略 / 恢复器接成
 * {@link TradeRuntime} 可用的实例。默认零手续费（{@link NoFeePolicy}）；
 * 需要 PKM 百分比手续费时替换为 {@link PkmPercentageFeePolicy}（要求钱包支持
 * 幂等操作，生产默认 Pixelmon 钱包不启用）。
 */
public final class TradeProduction {

    private TradeProduction() {
    }

    public static void install(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        TradeSavedData savedData = overworld.getDataStorage()
                .computeIfAbsent(TradeSavedData.factory(), TradeSavedData.DATA_NAME);
        SavedDataTradeRepository repo = new SavedDataTradeRepository(savedData);
        ServerPlayerStorageResolver resolver = new ServerPlayerStorageResolver(
                ThirdPartyServices.walletBridge().walletPort());
        ThreadChecker checker = new MinecraftServerThreadChecker();
        Clock clock = Clock.systemUTC();
        TradeCapabilityService capability = new TradeCapabilityServiceImpl(
                resolver, repo, new ConfigTradeCapabilitySettings());
        TradeFeePolicy feePolicy = feePolicy();
        TradeService service = new TradeServiceImpl(repo, resolver,
                itemEscrow(), pkmEscrow(), pokemonEscrow(),
                feePolicy, checker, clock, capability);
        TradeRecoveryService recovery = new TradeRecoveryService(service, repo, clock, checker);
        TradeRuntime.install(resolver);
        TradeRuntime.install(service);
        TradeRuntime.install(recovery);
    }

    /**
     * 手续费策略：默认零手续费（{@link NoFeePolicy}）。配置 {@code trade.feePercent}
     * 为预留项——当前生产钱包（Pixelmon）不支持幂等操作，大于 0 时仅记录警告
     * 并继续零手续费，避免结算重入导致重复扣费。
     */
    private static TradeFeePolicy feePolicy() {
        if (PokeTradeConfig.feePercent() > 0) {
            PokeEMC.LOGGER.warn(
                    "PokeTrade: trade.feePercent > 0 is reserved; production wallet "
                            + "(Pixelmon) has no idempotent ops, fee stays disabled");
        }
        return new NoFeePolicy();
    }

    private static ItemEscrowPort itemEscrow() {
        return new ItemEscrowPort() {
            @Override
            public Outcome<ItemEscrowGateway.PreparedItem> prepare(PlayerInventoryStore store, int slot,
                                                                    int count, UUID owner) {
                return ItemEscrowGateway.prepare(store, slot, count, owner);
            }

            @Override
            public Outcome<ItemEscrowGateway.EscrowedItem> remove(PlayerInventoryStore store,
                                                                  ItemEscrowGateway.PreparedItem prepared,
                                                                  UUID owner) {
                return ItemEscrowGateway.remove(store, prepared, owner);
            }

            @Override
            public Outcome<Void> cancel(PlayerInventoryStore store, ItemEscrowGateway.PreparedItem prepared) {
                return ItemEscrowGateway.cancel(store, prepared);
            }

            @Override
            public DeliveryResult deliver(PlayerInventoryStore store, ItemAsset asset,
                                          DeliveryPreference.ItemDestination destination) {
                return ItemEscrowGateway.deliver(store, asset, destination);
            }
        };
    }

    private static PkmEscrowPort pkmEscrow() {
        return new PkmEscrowPort() {
            @Override
            public Outcome<PkmAsset> escrow(WalletPort port, OperationLedger ledger, UUID tradeId,
                                            UUID owner, long amount, String operationId, long now) {
                return PkmEscrowGateway.escrow(port, ledger, tradeId, owner, amount, operationId, now);
            }

            @Override
            public Outcome<Void> settle(WalletPort port, OperationLedger ledger, PkmAsset asset, UUID recipient,
                                        UUID tradeId, String operationId, long now) {
                return PkmEscrowGateway.settle(port, ledger, asset, recipient, tradeId, operationId, now);
            }

            @Override
            public Outcome<Void> refund(WalletPort port, OperationLedger ledger, PkmAsset asset,
                                        UUID tradeId, String operationId, long now) {
                return PkmEscrowGateway.refund(port, ledger, asset, tradeId, operationId, now);
            }
        };
    }

    private static PokemonEscrowPort pokemonEscrow() {
        return new PokemonEscrowPort() {
            @Override
            public Outcome<PokemonEscrowGateway.PreparedPokemon> prepare(PokemonStoragePort port,
                                                                         PokemonLocation location,
                                                                         UUID owner, boolean alreadyEscrowed) {
                return PokemonEscrowGateway.prepare(port, location, owner, alreadyEscrowed);
            }

            @Override
            public Outcome<PokemonEscrowGateway.EscrowedPokemon> remove(PokemonStoragePort port,
                                                                        PokemonEscrowGateway.PreparedPokemon prepared,
                                                                        UUID owner) {
                return PokemonEscrowGateway.remove(port, prepared, owner);
            }

            @Override
            public DeliveryResult deliver(PokemonStoragePort port, PokemonAsset asset,
                                          DeliveryPreference.PokemonDestination destination) {
                return PokemonEscrowGateway.deliver(port, asset, destination);
            }
        };
    }
}
