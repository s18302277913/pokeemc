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
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.persistence.InboxEntry;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 12 步骤 4：并发请求测试（JVM 化）。
 * 模拟同一 tick 顺序到达的竞争请求（重复确认 / 确认与取消 / 报价与确认），
 * 断言主线程顺序下只有一个合法结果，没有复制或双扣。
 */
class TradeConcurrencyTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final long NOW = 1_000_000L;

    private TradeServiceTest.FakeRepo repo;
    private TradeServiceTest.FakeResolver resolver;
    private TradeServiceTest.FakeClock clock;
    private TradeServiceImpl service;

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
    }

    @Test
    void duplicateConfirmAfterLockDoesNotBreakState() {
        resolver.inventory(A).set(0, snapshot("minecraft:diamond", 10));
        resolver.inventory(B).set(0, snapshot("minecraft:emerald", 5));
        TradeId id = openTrade();
        long rev = service.offerItem(A, id, 1, 0, 10).revision();
        rev = service.offerItem(B, id, rev, 0, 5).revision();
        assertTrue(service.confirm(A, id, rev).success());
        assertTrue(service.confirm(B, id, rev).success());
        assertEquals(TradeStatus.LOCKED, repo.getTrade(id).orElseThrow().status());

        // 同一 tick 排队第二个相同确认：唯一合法结果 = INVALID_STATE，状态不被破坏
        assertEquals(TradeError.INVALID_STATE, service.confirm(A, id, rev).error());
        assertEquals(TradeStatus.LOCKED, repo.getTrade(id).orElseThrow().status());
        assertNoDuplicateAssets();
    }

    @Test
    void confirmThenCancelHasSingleOutcome() {
        resolver.inventory(A).set(0, snapshot("minecraft:diamond", 10));
        resolver.inventory(B).set(0, snapshot("minecraft:emerald", 5));
        TradeId id = openTrade();
        long rev = service.offerItem(A, id, 1, 0, 10).revision();
        rev = service.offerItem(B, id, rev, 0, 5).revision();
        assertTrue(service.confirm(A, id, rev).success());
        assertEquals(TradeStatus.OPEN, repo.getTrade(id).orElseThrow().status());

        // 同一 tick 确认后取消：只有一个合法结果 = 取消并归还
        assertTrue(service.cancel(B, id, rev).success());
        assertTrue(repo.getTrade(id).isEmpty());
        assertTrue(resolver.inventory(A).hasItem("minecraft:diamond", 10));
        assertTrue(resolver.inventory(B).hasItem("minecraft:emerald", 5));
        // 交易已终态：重复取消不重复归还
        assertEquals(TradeError.TRADE_NOT_FOUND, service.cancel(B, id, rev).error());
        assertNoDuplicateAssets();
    }

    @Test
    void staleRevisionConfirmRejected() {
        resolver.wallet().add(A, 100);
        TradeId id = openTrade();
        long rev = service.offerPkm(A, id, 1, 30).revision();
        // 报价与确认竞争：用旧 revision 确认被拒，不进入 LOCKED
        assertEquals(TradeError.STALE_REVISION, service.confirm(A, id, 1).error());
        assertTrue(service.confirm(A, id, rev).success());
        assertEquals(TradeStatus.OPEN, repo.getTrade(id).orElseThrow().status());
    }

    @Test
    void duplicateOfferDoesNotDoubleDebit() {
        resolver.wallet().add(A, 100);
        TradeId id = openTrade();
        long rev = service.offerPkm(A, id, 1, 30).revision();
        // 同一 tick 重复报价同额：幂等，revision 不推进，只扣一次
        long rev2 = service.offerPkm(A, id, rev, 30).revision();
        assertEquals(rev, rev2);
        assertEquals(70, resolver.wallet().find(A).orElseThrow().balance());
        assertNoDuplicateAssets();
    }

    // ------------------------------------------------------------------ helpers

    private TradeId openTrade() {
        TradeId id = service.invite(A, B).tradeId();
        service.accept(B, id, 0);
        return id;
    }

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
