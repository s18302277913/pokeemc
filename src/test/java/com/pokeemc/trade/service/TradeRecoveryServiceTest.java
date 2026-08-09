package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.DeliveryResult;
import com.pokeemc.trade.asset.ItemEscrowGateway;
import com.pokeemc.trade.asset.ItemSnapshot;
import com.pokeemc.trade.asset.OperationLedger;
import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.asset.PkmEscrowGateway;
import com.pokeemc.trade.asset.PlayerInventoryStore;
import com.pokeemc.trade.asset.PokemonEscrowGateway;
import com.pokeemc.trade.asset.PokemonLocation;
import com.pokeemc.trade.asset.PokemonStoragePort;
import com.pokeemc.trade.asset.StoredPokemon;
import com.pokeemc.trade.asset.WalletAccount;
import com.pokeemc.trade.asset.WalletPort;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeAsset;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeReceipt;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.persistence.InboxEntry;
import com.pokeemc.trade.persistence.OperationEntry;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7：崩溃恢复与超时测试（计划 7.3 步骤 1）。
 * 故障注入点：COMMITTING（部分/全部迁移）、COMMITTED/DELIVERING（重试交付）、
 * CANCELLING（归还）、LOCKED（到期恢复/未到期保持）、过期 INVITED/OPEN（自动取消）、
 * REQUIRES_ADMIN（不自动重试）。并覆盖 recoverAll 报告、sweepExpired 限流、onLogin 交付。
 *
 * <p>断言核心不变量：每个 asset UUID 只有一个位置（报价或收件箱）、
 * 资产总数守恒、已提交交易不会回滚、未知 PKM 结果不自动重试借记。</p>
 */
class TradeRecoveryServiceTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID D = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID E = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID F = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final long NOW = 1_000_000L;

    private TradeServiceTest.FakeRepo repo;
    private TradeServiceTest.FakeResolver resolver;
    private TradeServiceTest.FakeClock clock;
    private TradeServiceImpl service;
    private TradeRecoveryService recovery;

    @BeforeEach
    void setUp() {
        repo = new TradeServiceTest.FakeRepo();
        resolver = new TradeServiceTest.FakeResolver();
        clock = new TradeServiceTest.FakeClock(NOW);
        resolver.online.add(A);
        resolver.online.add(B);
        service = new TradeServiceImpl(repo, resolver, itemPort(), pkmPort(), pokemonPort(),
                new NoFeePolicy(), ThreadChecker.always(), clock,
                TradeCapabilityService.basic(resolver, repo));
        recovery = new TradeRecoveryService(service, repo, clock, ThreadChecker.always());
    }

    // ------------------------------------------------------------------ COMMITTING 崩溃

    @Test
    void recoverCommittingFinalizesCommitSwapsAssets() {
        TradeId id = lockedTradeWithItems();
        forceStatus(id, TradeStatus.COMMITTING);

        TradeResult r = recovery.service().recover(id);
        assertTrue(r.success(), r.error().name());

        // 所有权切换完成：B 收到钻石、A 收到绿宝石；交易推进终态并移除
        assertTrue(repo.getTrade(id).isEmpty(), "trade should complete and be removed");
        TradeServiceTest.FakeStore storeA = resolver.inventory(A);
        TradeServiceTest.FakeStore storeB = resolver.inventory(B);
        assertTrue(storeB.hasItem("minecraft:diamond", 10), "B should receive A's diamond");
        assertTrue(storeA.hasItem("minecraft:emerald", 5), "A should receive B's emerald");
        assertNoDuplicateAssets();
    }

    @Test
    void recoverCommittingIsIdempotentAfterPartialMigration() {
        TradeId id = lockedTradeWithItems();
        PlayerTrade locked = repo.getTrade(id).orElseThrow();
        forceStatus(id, TradeStatus.COMMITTING);
        // 模拟崩溃前已把 A 的钻石迁入 B 收件箱（PENDING）
        ItemAsset diamond = (ItemAsset) locked.leftOffer().allAssets().iterator().next();
        repo.addInboxEntry(InboxEntry.pending(id.value(), B, diamond,
                DeliveryPreference.defaults(), locked.revision(), clock.millis()));

        assertTrue(recovery.service().recover(id).success());

        // 幂等：B 收件箱仍只有一条钻石条目
        long diamondEntries = repo.inboxOf(B).stream()
                .filter(e -> e.asset().assetId().equals(diamond.assetId()))
                .count();
        assertEquals(1, diamondEntries, "recovery must not duplicate migrated assets");
        assertNoDuplicateAssets();
    }

    // ------------------------------------------------------------------ COMMITTED / DELIVERING 崩溃

    @Test
    void recoverCommittedRetriesDelivery() {
        TradeId id = lockedTradeWithItems();
        PlayerTrade locked = repo.getTrade(id).orElseThrow();
        forceStatus(id, TradeStatus.COMMITTED);
        // 提交点已把资产迁入收件箱，但未交付
        ItemAsset diamond = (ItemAsset) locked.leftOffer().allAssets().iterator().next();
        ItemAsset emerald = (ItemAsset) locked.rightOffer().allAssets().iterator().next();
        repo.addInboxEntry(InboxEntry.pending(id.value(), B, diamond,
                DeliveryPreference.defaults(), locked.revision(), clock.millis()));
        repo.addInboxEntry(InboxEntry.pending(id.value(), A, emerald,
                DeliveryPreference.defaults(), locked.revision(), clock.millis()));

        assertTrue(recovery.service().recover(id).success());

        // 重试交付：B 收到钻石、A 收到绿宝石；交易完成移除
        assertTrue(repo.getTrade(id).isEmpty());
        assertTrue(resolver.inventory(B).hasItem("minecraft:diamond", 10));
        assertTrue(resolver.inventory(A).hasItem("minecraft:emerald", 5));
    }

    @Test
    void recoverDeliveringRetriesDelivery() {
        TradeId id = lockedTradeWithItems();
        PlayerTrade locked = repo.getTrade(id).orElseThrow();
        forceStatus(id, TradeStatus.DELIVERING);
        ItemAsset diamond = (ItemAsset) locked.leftOffer().allAssets().iterator().next();
        ItemAsset emerald = (ItemAsset) locked.rightOffer().allAssets().iterator().next();
        repo.addInboxEntry(InboxEntry.pending(id.value(), B, diamond,
                DeliveryPreference.defaults(), locked.revision(), clock.millis()));
        repo.addInboxEntry(InboxEntry.pending(id.value(), A, emerald,
                DeliveryPreference.defaults(), locked.revision(), clock.millis()));

        assertTrue(recovery.service().recover(id).success());
        assertTrue(repo.getTrade(id).isEmpty());
        assertTrue(resolver.inventory(B).hasItem("minecraft:diamond", 10));
        assertTrue(resolver.inventory(A).hasItem("minecraft:emerald", 5));
    }

    // ------------------------------------------------------------------ CANCELLING 崩溃

    @Test
    void recoverCancellingReturnsAssetsToOwners() {
        TradeId id = lockedTradeWithItems();
        forceStatus(id, TradeStatus.CANCELLING);

        assertTrue(recovery.service().recover(id).success());

        // 取消归还：A 收回钻石、B 收回绿宝石；交易移除
        assertTrue(repo.getTrade(id).isEmpty());
        assertTrue(resolver.inventory(A).hasItem("minecraft:diamond", 10));
        assertTrue(resolver.inventory(B).hasItem("minecraft:emerald", 5));
        assertNoDuplicateAssets();
    }

    @Test
    void recoverCancellingIsIdempotentAfterPartialMigration() {
        TradeId id = lockedTradeWithItems();
        PlayerTrade locked = repo.getTrade(id).orElseThrow();
        forceStatus(id, TradeStatus.CANCELLING);
        ItemAsset diamond = (ItemAsset) locked.leftOffer().allAssets().iterator().next();
        repo.addInboxEntry(InboxEntry.pending(id.value(), A, diamond,
                DeliveryPreference.defaults(), locked.revision(), clock.millis()));

        assertTrue(recovery.service().recover(id).success());

        // 幂等：A 收件箱只有一条钻石条目
        long entries = repo.inboxOf(A).stream()
                .filter(e -> e.asset().assetId().equals(diamond.assetId()))
                .count();
        assertEquals(1, entries);
        assertNoDuplicateAssets();
    }

    // ------------------------------------------------------------------ LOCKED 崩溃/超时

    @Test
    void recoverLockedNotExpiredKeepsLock() {
        TradeId id = lockedTradeWithItems();
        clock.advance(1000); // 未到 3 秒锁定期

        assertTrue(recovery.service().recover(id).success());
        assertEquals(TradeStatus.LOCKED, repo.getTrade(id).orElseThrow().status());
        // 报价未变：资产仍在各自报价
        assertNoDuplicateAssets();
    }

    @Test
    void recoverLockedExpiredCommitsWhenOnline() {
        TradeId id = lockedTradeWithItems();
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS + 1);

        assertTrue(recovery.service().recover(id).success());

        // 锁到期 + 双方在线 + quote 有效：提交完成
        assertTrue(repo.getTrade(id).isEmpty());
        assertTrue(resolver.inventory(B).hasItem("minecraft:diamond", 10));
        assertTrue(resolver.inventory(A).hasItem("minecraft:emerald", 5));
    }

    @Test
    void recoverLockedExpiredOfflineUnlocksToOpen() {
        TradeId id = lockedTradeWithItems();
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS + 1);
        resolver.online.remove(A);

        assertFalse(recovery.service().recover(id).success());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(TradeStatus.OPEN, trade.status(), "offline participant should unlock to OPEN");
        // 资产仍在报价（未迁移）
        assertNoDuplicateAssets();
    }

    // ------------------------------------------------------------------ 过期 INVITED / OPEN

    @Test
    void recoverExpiredOpenCancelsAndReturns() {
        TradeId id = lockedTradeWithItems();
        forceStatus(id, TradeStatus.OPEN);
        clock.advance(PlayerTrade.DEFAULT_TIMEOUT_MILLIS + 1);

        assertTrue(recovery.service().recover(id).success());

        assertTrue(repo.getTrade(id).isEmpty());
        assertTrue(resolver.inventory(A).hasItem("minecraft:diamond", 10));
        assertTrue(resolver.inventory(B).hasItem("minecraft:emerald", 5));
    }

    @Test
    void recoverNotExpiredOpenKeeps() {
        TradeId id = lockedTradeWithItems();
        forceStatus(id, TradeStatus.OPEN);
        clock.advance(1000);

        assertTrue(recovery.service().recover(id).success());
        assertEquals(TradeStatus.OPEN, repo.getTrade(id).orElseThrow().status());
        assertNoDuplicateAssets();
    }

    // ------------------------------------------------------------------ REQUIRES_ADMIN 不自动重试

    @Test
    void recoverRequiresAdminIsNotAutoRetried() {
        // 崩溃遗留 PENDING PKM 借记（余额未动，状态不可知）
        resolver.wallet().add(A, 100);
        TradeId id = openTrade();
        String opId = "op:pending";
        repo.record(OperationEntry.record(opId, "PKM_DEBIT", id.value(), null, A, 30, "pending", clock.millis()));
        // 该交易已被标记 FAILED_REQUIRES_ADMIN（终态，需人工处理）
        forceStatus(id, TradeStatus.FAILED_REQUIRES_ADMIN);

        TradeResult r = service.recover(id);
        assertFalse(r.success());
        assertEquals(TradeError.INVALID_STATE, r.error());
        // 未知 PKM 结果：不自动重试借记，钱包余额保持不变
        assertEquals(100, resolver.wallet().find(A).orElseThrow().balance());
        assertEquals(OperationEntry.OperationState.PENDING,
                repo.get(opId).orElseThrow().state());
    }

    // ------------------------------------------------------------------ recoverAll / sweep / onLogin

    @Test
    void recoverAllReportsStats() {
        TradeId c1 = lockedTradeWithItems();
        forceStatus(c1, TradeStatus.COMMITTING);
        resolver.online.add(C);
        resolver.online.add(D);
        TradeId c2 = openTradeWithPair(C, D); // 未过期，保持

        TradeRecoveryService.RecoveryReport report = recovery.recoverAll();
        assertEquals(2, report.total());
        assertEquals(2, report.recovered());
        assertEquals(0, report.failed());
        assertTrue(repo.getTrade(c1).isEmpty());
        assertEquals(TradeStatus.OPEN, repo.getTrade(c2).orElseThrow().status());
    }

    @Test
    void sweepExpiredOnlyExpiredAndBatchLimited() {
        // 三个独立玩家对：两个过期 OPEN + 一个未过期 OPEN
        resolver.online.add(C);
        resolver.online.add(D);
        resolver.online.add(E);
        resolver.online.add(F);
        TradeId e1 = openTradeWithPair(A, B);
        TradeId e2 = openTradeWithPair(C, D);
        TradeId fresh = openTradeWithPair(E, F);
        clock.advance(PlayerTrade.DEFAULT_TIMEOUT_MILLIS + 1);

        TradeRecoveryService limited = new TradeRecoveryService(
                service, repo, clock, ThreadChecker.always(), 2);
        int processed = limited.sweepExpired();

        assertEquals(2, processed, "batch limit must cap sweep");
        assertTrue(repo.getTrade(e1).isEmpty(), "expired trade 1 cancelled");
        assertTrue(repo.getTrade(e2).isEmpty(), "expired trade 2 cancelled");
        assertEquals(TradeStatus.OPEN, repo.getTrade(fresh).orElseThrow().status(),
                "not-expired trade untouched");
    }

    @Test
    void onLoginDeliversPendingInbox() {
        TradeId id = openTrade();
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        ItemAsset diamond = new ItemAsset(UUID.randomUUID(), A, snapshot("minecraft:diamond", 10).nbt());
        repo.addInboxEntry(InboxEntry.pending(id.value(), B, diamond,
                DeliveryPreference.defaults(), trade.revision(), clock.millis()));

        recovery.onLogin(B);

        // 登录触发 claim：钻石交付到 B 背包
        assertTrue(resolver.inventory(B).hasItem("minecraft:diamond", 10));
        assertTrue(repo.inboxOf(B).stream()
                .allMatch(e -> e.state() == InboxEntry.InboxState.DELIVERED));
    }

    // ------------------------------------------------------------------ helpers

    /** 双方各报价一个物品并确认，进入 LOCKED（quote 已冻结） */
    private TradeId lockedTradeWithItems() {
        resolver.inventory(A).set(0, snapshot("minecraft:diamond", 10));
        resolver.inventory(B).set(0, snapshot("minecraft:emerald", 5));
        TradeId id = openTrade();
        long rev = service.offerItem(A, id, 1, 0, 10).revision();
        rev = service.offerItem(B, id, rev, 0, 5).revision();
        service.confirm(A, id, rev);
        service.confirm(B, id, rev);
        return id;
    }

    /** 仅双方确认进入 OPEN 并保持（报价为空） */
    private TradeId openTradeWithPair(UUID initiator, UUID target) {
        resolver.online.add(initiator);
        resolver.online.add(target);
        TradeId id = service.invite(initiator, target).tradeId();
        service.accept(target, id, 0);
        return id;
    }

    private TradeId openTrade() {
        return openTradeWithPair(A, B);
    }

    /** 强制交易状态（模拟崩溃点持久化） */
    private void forceStatus(TradeId id, TradeStatus status) {
        repo.updateTrade(id, t -> {
            return PlayerTrade.builder(t.tradeId(), t.leftPlayerId(), t.rightPlayerId())
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
                    .build();
        });
    }

    /** 不变量：扫描全部活动交易报价 + 收件箱，无重复 asset UUID */
    private void assertNoDuplicateAssets() {
        Set<UUID> seen = new HashSet<>();
        for (PlayerTrade trade : repo.activeTrades()) {
            for (TradeAsset a : trade.leftOffer().allAssets()) {
                assertTrue(seen.add(a.assetId()), "duplicate asset in left offer: " + a.assetId());
            }
            for (TradeAsset a : trade.rightOffer().allAssets()) {
                assertTrue(seen.add(a.assetId()), "duplicate asset in right offer: " + a.assetId());
            }
        }
        for (List<InboxEntry> entries : repo.inbox.values()) {
            for (InboxEntry e : entries) {
                if (e.state() == InboxEntry.InboxState.DELIVERED) {
                    continue;
                }
                assertTrue(seen.add(e.asset().assetId()), "duplicate asset in inbox: " + e.asset().assetId());
            }
        }
    }

    private static ItemSnapshot snapshot(String itemId, int count) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", itemId);
        nbt.putByte("Count", (byte) count);
        return new ItemSnapshot(itemId, count, nbt);
    }

    private static StoredPokemon poke(UUID id) {
        return new StoredPokemon(id, new CompoundTag(), true, false);
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
