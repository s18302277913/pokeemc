package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.DeliveryResult;
import com.pokeemc.trade.asset.ItemEscrowGateway;
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
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.model.TradeReceipt;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.persistence.InboxEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 11：3 秒锁定与手续费测试（计划 4.3/5.4 / Task 11 步骤 3-4）。
 * 覆盖：双确认进入 LOCKED 并冻结 quote、锁定期内 commit 拒绝、到期后提交并应用
 * 手续费、余额不足回 OPEN、quote 过期回 OPEN、PkmPercentageFeePolicy 报价计算与
 * 预留幂等。
 */
class TradeLockAndFeeTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final long NOW = 1_000_000L;

    private TradeServiceTest.FakeRepo repo;
    private TradeServiceTest.FakeResolver resolver;
    private TradeServiceTest.FakeClock clock;
    private PkmPercentageFeePolicy feePolicy;
    private TradeServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = new TradeServiceTest.FakeRepo();
        resolver = new TradeServiceTest.FakeResolver();
        clock = new TradeServiceTest.FakeClock(NOW);
        resolver.online.add(A);
        resolver.online.add(B);
        feePolicy = new PkmPercentageFeePolicy(resolver.wallet(), repo, 500, 0, 0); // 5%
        service = new TradeServiceImpl(repo, resolver, itemPort(), pkmPort(), pokemonPort(),
                feePolicy, ThreadChecker.always(), clock,
                TradeCapabilityService.basic(resolver, repo));
    }

    // ------------------------------------------------------------------ 3 秒锁定

    @Test
    void bothConfirmEntersLockedAndFreezesQuote() {
        TradeId id = openTrade();
        TradeResult r1 = service.confirm(A, id, 1);
        assertTrue(r1.success());
        assertEquals(TradeStatus.OPEN, repo.getTrade(id).orElseThrow().status());
        // 第二方确认 -> LOCKED，deadline = NOW + 3000，冻结 quote
        TradeResult r2 = service.confirm(B, id, 1);
        assertTrue(r2.success());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(TradeStatus.LOCKED, trade.status());
        assertEquals(NOW + PlayerTrade.LOCK_DURATION_MILLIS, trade.lockDeadlineEpochMillis());
        assertNotNull(trade.feeQuote());
        assertEquals(trade.revision(), trade.feeQuote().quotedRevision());
    }

    @Test
    void commitRejectedInsideLockWindow() {
        TradeId id = openTrade();
        long rev = service.confirm(A, id, 1).revision();
        service.confirm(B, id, rev);
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS - 1);
        assertEquals(TradeError.INVALID_STATE, service.commit(id).error());
        assertEquals(TradeStatus.LOCKED, repo.getTrade(id).orElseThrow().status());
    }

    @Test
    void commitAfterLockExpirySwapsAndChargesFee() {
        resolver.wallet().add(A, 100);
        resolver.wallet().add(B, 200);
        TradeId id = openTrade();
        long rev = service.offerPkm(A, id, 1, 30).revision();
        rev = service.offerPkm(B, id, rev, 50).revision();
        service.confirm(A, id, rev);
        service.confirm(B, id, rev);
        // 冻结 quote：A 30 PKM * 5% = 1.5 -> 2；B 50 PKM * 5% = 2.5 -> 3
        PlayerTrade locked = repo.getTrade(id).orElseThrow();
        assertEquals(2, locked.feeQuote().leftPkmFee());
        assertEquals(3, locked.feeQuote().rightPkmFee());
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS + 1);

        TradeResult r = service.commit(id);
        assertTrue(r.success(), r.error().name());

        // 手续费 + 交换结算：A 100 - 30 - 2 + 50 = 118；B 200 - 50 - 3 + 30 = 177
        assertEquals(118, resolver.wallet().find(A).orElseThrow().balance());
        assertEquals(177, resolver.wallet().find(B).orElseThrow().balance());
        assertEquals(1, repo.receipts.size());
        TradeReceipt receipt = repo.receipts.get(0);
        assertEquals(2, receipt.leftPkmFee());
        assertEquals(3, receipt.rightPkmFee());
        // 收件箱全部交付，交易进入终态被移除
        assertTrue(repo.inboxOf(A).stream().allMatch(e -> e.state() == InboxEntry.InboxState.DELIVERED));
        assertTrue(repo.inboxOf(B).stream().allMatch(e -> e.state() == InboxEntry.InboxState.DELIVERED));
        assertTrue(repo.getTrade(id).isEmpty());
    }

    // ------------------------------------------------------------------ 手续费回退

    @Test
    void commitWithInsufficientFeeBalanceReturnsToOpen() {
        resolver.wallet().add(A, 30);  // 报价 30 后余额 0，无力支付 2 手续费
        resolver.wallet().add(B, 100);
        TradeId id = openTrade();
        long rev = service.offerPkm(A, id, 1, 30).revision();
        rev = service.offerPkm(B, id, rev, 30).revision();
        service.confirm(A, id, rev);
        service.confirm(B, id, rev);
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS + 1);

        TradeResult r = service.commit(id);
        assertEquals(TradeError.PKM_INSUFFICIENT_BALANCE, r.error());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(TradeStatus.OPEN, trade.status());
        assertEquals(rev + 1, trade.revision()); // unlockToOpen bump
        // 失败侧未重复扣费；另一侧因短路未被 reserve
        assertEquals(0, resolver.wallet().find(A).orElseThrow().balance());
        assertEquals(70, resolver.wallet().find(B).orElseThrow().balance());
    }

    @Test
    void commitWithExpiredQuoteReturnsToOpen() {
        TradeId id = openTrade();
        long rev = service.confirm(A, id, 1).revision();
        service.confirm(B, id, rev);
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS + 1);   // 锁到期
        clock.advance(30_000L + 1);                            // quote 30s 有效期过期
        assertEquals(TradeError.FEE_QUOTE_INVALID, service.commit(id).error());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(TradeStatus.OPEN, trade.status());
        assertEquals(rev + 1, trade.revision());
    }

    // ------------------------------------------------------------------ 手续费策略单元

    @Test
    void zeroFeeQuoteWithoutPkmOffers() {
        TradeId id = openTrade();
        service.confirm(A, id, 1);
        service.confirm(B, id, 1);
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(0, trade.feeQuote().leftPkmFee());
        assertEquals(0, trade.feeQuote().rightPkmFee());
    }

    @Test
    void quoteAppliesMinimumAndMaximumClamp() {
        PkmPercentageFeePolicy minPolicy = new PkmPercentageFeePolicy(resolver.wallet(), repo, 500, 100, 0);
        TradeOffer left = TradeOffer.empty().withAdded(new PkmAsset(UUID.randomUUID(), A, 30, "op-l", true));
        TradeOffer right = TradeOffer.empty().withAdded(new PkmAsset(UUID.randomUUID(), B, 50, "op-r", true));
        TradeFeeQuote q1 = minPolicy.quote(context(left, right));
        assertEquals(100, q1.leftPkmFee());   // ceil(1.5) = 2 < min 100
        assertEquals(100, q1.rightPkmFee());  // ceil(2.5) = 3 < min 100

        PkmPercentageFeePolicy maxPolicy = new PkmPercentageFeePolicy(resolver.wallet(), repo, 500, 0, 5);
        TradeOffer big = TradeOffer.empty().withAdded(new PkmAsset(UUID.randomUUID(), B, 200_000, "op-b", true));
        TradeFeeQuote q2 = maxPolicy.quote(context(big, big));
        assertEquals(5, q2.leftPkmFee());   // ceil(10_000) = 10_000 > max 5
        assertEquals(5, q2.rightPkmFee());
    }

    @Test
    void quoteWithExtremeAmountsNeverThrows() {
        // Task 13 步骤 1：金额上限可达 Long.MAX_VALUE / 4，乘法必须走 BigInteger
        // 而非 multiplyExact，否则抛出 ArithmeticException 泄漏为未捕获异常。
        long extreme = Long.MAX_VALUE / 4;
        TradeOffer left = TradeOffer.empty()
                .withAdded(new PkmAsset(UUID.randomUUID(), A, extreme, "op-xl", true));
        TradeOffer right = TradeOffer.empty()
                .withAdded(new PkmAsset(UUID.randomUUID(), B, extreme, "op-xr", true));
        TradeFeeQuote q = assertDoesNotThrow(() -> feePolicy.quote(context(left, right)));
        // 5% * (Long.MAX_VALUE/4) 精确值：BigInteger 计算不溢出、不为负
        // ceil(2305843009213693951 * 500 / 10000) = 115292150460684698
        assertEquals(115292150460684698L, q.leftPkmFee());
        assertEquals(115292150460684698L, q.rightPkmFee());

        // 100% 费率（basisPoints = 10000）下 Long.MAX_VALUE 触发 clamp 到 Long.MAX_VALUE
        PkmPercentageFeePolicy fullPolicy =
                new PkmPercentageFeePolicy(resolver.wallet(), repo, 10_000, 0, 0);
        TradeOffer full = TradeOffer.empty()
                .withAdded(new PkmAsset(UUID.randomUUID(), A, Long.MAX_VALUE, "op-full", true));
        TradeFeeQuote qFull = assertDoesNotThrow(() -> fullPolicy.quote(context(full, full)));
        assertEquals(Long.MAX_VALUE, qFull.leftPkmFee());
        assertEquals(Long.MAX_VALUE, qFull.rightPkmFee());

        // 随机大金额不抛异常、不退化为负值
        java.util.Random rng = new java.util.Random(0xD0C0D0C0D0C0D0CL);
        for (int i = 0; i < 500; i++) {
            long amount = Math.abs(rng.nextLong()) / 4 + 1;
            TradeOffer l = TradeOffer.empty()
                    .withAdded(new PkmAsset(UUID.randomUUID(), A, amount, "op-r" + i, true));
            TradeFeeQuote q2 = assertDoesNotThrow(() -> feePolicy.quote(context(l, l)));
            assertTrue(q2.leftPkmFee() >= 0);
            assertTrue(q2.rightPkmFee() >= 0);
        }
    }

    @Test
    void reserveIsIdempotentOnRetry() {
        resolver.wallet().add(A, 100);
        resolver.wallet().add(B, 200);
        TradeId id = openTrade();
        long rev = service.offerPkm(A, id, 1, 30).revision();
        rev = service.offerPkm(B, id, rev, 50).revision();
        service.confirm(A, id, rev);
        service.confirm(B, id, rev);
        PlayerTrade locked = repo.getTrade(id).orElseThrow();

        FeeReservation first = feePolicy.reserve(locked.feeQuote(), locked);
        FeeReservation second = feePolicy.reserve(locked.feeQuote(), locked);
        assertTrue(first.ok());
        assertTrue(second.ok());
        // 幂等：第二次预留不重复扣费
        assertEquals(100 - 30 - 2, resolver.wallet().find(A).orElseThrow().balance());
        assertEquals(200 - 50 - 3, resolver.wallet().find(B).orElseThrow().balance());
    }

    // ------------------------------------------------------------------ helpers

    private TradeId openTrade() {
        TradeId id = service.invite(A, B).tradeId();
        service.accept(B, id, 0);
        return id;
    }

    private TradeFeeContext context(TradeOffer left, TradeOffer right) {
        return new TradeFeeContext(UUID.randomUUID(), 1, left, right, A,
                Instant.ofEpochMilli(clock.millis()));
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
