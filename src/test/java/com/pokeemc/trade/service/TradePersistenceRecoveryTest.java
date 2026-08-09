package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.DeliveryResult;
import com.pokeemc.trade.asset.ItemEscrowGateway;
import com.pokeemc.trade.asset.ItemSnapshot;
import com.pokeemc.trade.asset.OperationLedger;
import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.asset.PlayerInventoryStore;
import com.pokeemc.trade.asset.PkmEscrowGateway;
import com.pokeemc.trade.asset.PokemonEscrowGateway;
import com.pokeemc.trade.asset.PokemonLocation;
import com.pokeemc.trade.asset.PokemonStoragePort;
import com.pokeemc.trade.asset.WalletPort;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeAsset;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.persistence.InboxEntry;
import com.pokeemc.trade.persistence.TradeNbtCodec;
import com.pokeemc.trade.persistence.TradeSavedData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 12 步骤 3：重启恢复 GameTest（JVM 化）。
 * 每个事务边界（COMMITTING / CANCELLING / LOCKED 到期）写入 SavedData NBT，
 * 模拟重启加载后运行恢复器，断言最终收件箱与交易状态唯一确定、资产不丢失不复制。
 */
class TradePersistenceRecoveryTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final long NOW = 1_000_000L;

    private TradeSavedData data;
    private SavedDataTradeRepository repo;
    private TradeServiceTest.FakeResolver resolver;
    private TradeServiceTest.FakeClock clock;
    private TradeServiceImpl service;

    @BeforeEach
    void setUp() {
        data = new TradeSavedData();
        repo = new SavedDataTradeRepository(data);
        resolver = new TradeServiceTest.FakeResolver();
        clock = new TradeServiceTest.FakeClock(NOW);
        resolver.online.add(A);
        resolver.online.add(B);
        service = new TradeServiceImpl(repo, resolver, itemPort(), pkmPort(), pokemonPort(),
                new NoFeePolicy(), ThreadChecker.always(), clock,
                TradeCapabilityService.basic(resolver, repo));
    }

    @Test
    void committingCrashCompletesAfterRestart() {
        TradeId id = lockedTradeWithItems();
        forceStatus(id, TradeStatus.COMMITTING);

        Restarted r = restart(NOW);
        r.recovery().recoverAll();

        // 唯一确定结果：提交完成、交易移除、资产到位
        assertTrue(r.repo().getTrade(id).isEmpty());
        assertTrue(resolver.inventory(B).hasItem("minecraft:diamond", 10));
        assertTrue(resolver.inventory(A).hasItem("minecraft:emerald", 5));
        assertNoDuplicateAssets(r.repo());
    }

    @Test
    void cancellingCrashReturnsAfterRestart() {
        TradeId id = lockedTradeWithItems();
        forceStatus(id, TradeStatus.CANCELLING);

        Restarted r = restart(NOW);
        r.recovery().recoverAll();

        // 唯一确定结果：取消归还、交易移除
        assertTrue(r.repo().getTrade(id).isEmpty());
        assertTrue(resolver.inventory(A).hasItem("minecraft:diamond", 10));
        assertTrue(resolver.inventory(B).hasItem("minecraft:emerald", 5));
        assertNoDuplicateAssets(r.repo());
    }

    @Test
    void lockedExpiredCommitsAfterRestart() {
        TradeId id = lockedTradeWithItems();
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS + 1);

        Restarted r = restart(NOW + PlayerTrade.LOCK_DURATION_MILLIS + 1);
        r.recovery().recoverAll();

        assertTrue(r.repo().getTrade(id).isEmpty());
        assertTrue(resolver.inventory(B).hasItem("minecraft:diamond", 10));
        assertTrue(resolver.inventory(A).hasItem("minecraft:emerald", 5));
        assertNoDuplicateAssets(r.repo());
    }

    @Test
    void lockedExpiredOfflineUnlocksAfterRestart() {
        TradeId id = lockedTradeWithItems();
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS + 1);
        resolver.online.remove(A);

        Restarted r = restart(NOW + PlayerTrade.LOCK_DURATION_MILLIS + 1);
        r.recovery().recoverAll();

        // 离线方：不强制提交，回 OPEN，资产仍在报价
        assertEquals(TradeStatus.OPEN, r.repo().getTrade(id).orElseThrow().status());
        assertNoDuplicateAssets(r.repo());
    }

    // ------------------------------------------------------------------ helpers

    /** 双方各报价一个物品并确认，进入 LOCKED（quote 已冻结） */
    private TradeId lockedTradeWithItems() {
        resolver.inventory(A).set(0, snapshot("minecraft:diamond", 10));
        resolver.inventory(B).set(0, snapshot("minecraft:emerald", 5));
        TradeId id = service.invite(A, B).tradeId();
        service.accept(B, id, 0);
        long rev = service.offerItem(A, id, 1, 0, 10).revision();
        rev = service.offerItem(B, id, rev, 0, 5).revision();
        service.confirm(A, id, rev);
        service.confirm(B, id, rev);
        return id;
    }

    /** 强制交易状态（模拟崩溃点持久化） */
    private void forceStatus(TradeId id, TradeStatus status) {
        repo.updateTrade(id, t -> PlayerTrade.builder(t.tradeId(), t.leftPlayerId(), t.rightPlayerId())
                .status(status)
                .revision(t.revision())
                .leftOffer(t.leftOffer())
                .rightOffer(t.rightOffer())
                .leftConfirmedRevision(t.leftConfirmedRevision())
                .rightConfirmedRevision(t.rightConfirmedRevision())
                .leftPreference(t.leftPreference())
                .rightPreference(t.rightPreference())
                .feeQuote(t.feeQuote())
                .createdAt(t.createdAtEpochMillis())
                .updatedAt(t.updatedAtEpochMillis())
                .expiresAt(t.expiresAtEpochMillis())
                .lockDeadline(t.lockDeadlineEpochMillis())
                .failureError(t.failureError())
                .failureDetail(t.failureDetail())
                .build());
    }

    /** 保存当前 SavedData -> 重启加载 -> 组装新 service/recovery（玩家存储保留） */
    private Restarted restart(long newNow) {
        CompoundTag saved = TradeNbtCodec.encodeAll(data);
        TradeSavedData loaded = TradeSavedData.load(saved, null);
        SavedDataTradeRepository repo2 = new SavedDataTradeRepository(loaded);
        TradeServiceTest.FakeClock clock2 = new TradeServiceTest.FakeClock(newNow);
        TradeServiceImpl service2 = new TradeServiceImpl(repo2, resolver, itemPort(), pkmPort(), pokemonPort(),
                new NoFeePolicy(), ThreadChecker.always(), clock2,
                TradeCapabilityService.basic(resolver, repo2));
        TradeRecoveryService recovery2 = new TradeRecoveryService(service2, repo2, clock2, ThreadChecker.always());
        return new Restarted(loaded, repo2, recovery2);
    }

    private record Restarted(TradeSavedData data, SavedDataTradeRepository repo,
                             TradeRecoveryService recovery) {
    }

    private void assertNoDuplicateAssets(SavedDataTradeRepository repo) {
        Set<UUID> seen = new HashSet<>();
        for (PlayerTrade trade : repo.activeTrades()) {
            for (TradeAsset a : trade.leftOffer().allAssets()) {
                assertTrue(seen.add(a.assetId()), "duplicate asset in left offer: " + a.assetId());
            }
            for (TradeAsset a : trade.rightOffer().allAssets()) {
                assertTrue(seen.add(a.assetId()), "duplicate asset in right offer: " + a.assetId());
            }
        }
        for (InboxEntry e : repo.inboxOf(A)) {
            if (e.state() != InboxEntry.InboxState.DELIVERED) {
                assertTrue(seen.add(e.asset().assetId()), "duplicate asset in A inbox");
            }
        }
        for (InboxEntry e : repo.inboxOf(B)) {
            if (e.state() != InboxEntry.InboxState.DELIVERED) {
                assertTrue(seen.add(e.asset().assetId()), "duplicate asset in B inbox");
            }
        }
    }

    private static ItemSnapshot snapshot(String itemId, int count) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", itemId);
        nbt.putByte("Count", (byte) count);
        return new ItemSnapshot(itemId, count, nbt);
    }

    private ItemEscrowPort itemPort() {
        return new ItemEscrowPort() {
            @Override
            public Outcome<ItemEscrowGateway.PreparedItem> prepare(PlayerInventoryStore store, int slot, int count, UUID owner) {
                return ItemEscrowGateway.prepare(store, slot, count, owner);
            }

            @Override
            public Outcome<ItemEscrowGateway.EscrowedItem> remove(PlayerInventoryStore store,
                                                                   ItemEscrowGateway.PreparedItem prepared, UUID owner) {
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

    private PkmEscrowPort pkmPort() {
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

    private PokemonEscrowPort pokemonPort() {
        return new PokemonEscrowPort() {
            @Override
            public Outcome<PokemonEscrowGateway.PreparedPokemon> prepare(PokemonStoragePort port,
                                                                         PokemonLocation location, UUID owner, boolean alreadyEscrowed) {
                return PokemonEscrowGateway.prepare(port, location, owner, alreadyEscrowed);
            }

            @Override
            public Outcome<PokemonEscrowGateway.EscrowedPokemon> remove(PokemonStoragePort port,
                                                                        PokemonEscrowGateway.PreparedPokemon prepared, UUID owner) {
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
