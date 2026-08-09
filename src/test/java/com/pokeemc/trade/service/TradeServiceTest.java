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
import com.pokeemc.trade.model.TradeSide;
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
import java.util.Collection;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 6：交易服务测试（计划 4.1/5.3 / Task 6 步骤 1）。
 * 覆盖邀请/接受、三类资产报价、容量与幂等、移除资产归还、
 * 偏好持久化、确认进入锁定并冻结 quote、取消归还、提交原子切换与回执、
 * 锁到期/掉线回 OPEN、claim 交付与交易推进终态、快照视角。
 */
class TradeServiceTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID D = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID PKM_X = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID PKM_Y = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final long NOW = 1_000_000L;

    private FakeRepo repo;
    private FakeResolver resolver;
    private FakeClock clock;
    private TradeServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        resolver = new FakeResolver();
        clock = new FakeClock(NOW);
        resolver.online.add(A);
        resolver.online.add(B);
        service = new TradeServiceImpl(repo, resolver, itemPort(), pkmPort(), pokemonPort(),
                new NoFeePolicy(), ThreadChecker.always(), clock,
                TradeCapabilityService.basic(resolver, repo));
    }

    // ------------------------------------------------------------------ 邀请 / 接受

    @Test
    void inviteCreatesInvitedTrade() {
        TradeResult r = service.invite(A, B);
        assertTrue(r.success());
        PlayerTrade trade = repo.getTrade(r.tradeId()).orElseThrow();
        assertEquals(TradeStatus.INVITED, trade.status());
        assertEquals(A, trade.leftPlayerId());
        assertEquals(B, trade.rightPlayerId());
    }

    @Test
    void inviteSelfRejected() {
        assertEquals(TradeError.SELF_TRADE, service.invite(A, A).error());
    }

    @Test
    void inviteTargetOfflineRejected() {
        assertEquals(TradeError.TARGET_OFFLINE, service.invite(A, C).error());
    }

    @Test
    void inviteAlreadyInTradeRejected() {
        resolver.online.add(C);
        service.invite(A, B);
        assertEquals(TradeError.ALREADY_IN_TRADE, service.invite(A, C).error());
        assertEquals(TradeError.ALREADY_IN_TRADE, service.invite(C, B).error());
    }

    @Test
    void acceptOpensTrade() {
        TradeId id = service.invite(A, B).tradeId();
        TradeResult r = service.accept(B, id, 0);
        assertTrue(r.success());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(TradeStatus.OPEN, trade.status());
        assertEquals(1, trade.revision());
    }

    @Test
    void initiatorCannotAccept() {
        TradeId id = service.invite(A, B).tradeId();
        assertEquals(TradeError.INVALID_STATE, service.accept(A, id, 0).error());
    }

    @Test
    void acceptWrongRevisionRejected() {
        TradeId id = service.invite(A, B).tradeId();
        assertEquals(TradeError.STALE_REVISION, service.accept(B, id, 5).error());
    }

    @Test
    void acceptOnlyInInvited() {
        TradeId id = service.invite(A, B).tradeId();
        service.accept(B, id, 0);
        assertEquals(TradeError.INVALID_STATE, service.accept(B, id, 1).error());
    }

    // ------------------------------------------------------------------ 物品报价

    @Test
    void offerItemEscrowsAndAddsToOffer() {
        FakeStore store = resolver.inventory(A);
        store.set(0, snapshot("minecraft:diamond", 10));
        TradeId id = openTrade();

        TradeResult r = service.offerItem(A, id, 1, 0, 10);
        assertTrue(r.success());
        assertEquals(2, r.revision());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(1, trade.leftOffer().items().size());
        assertEquals(10, ItemEscrowGateway.assetCount(trade.leftOffer().items().get(0)));
        assertTrue(store.get(0).isEmpty()); // 槽位已清空
    }

    @Test
    void offerItemInvalidSlotRejected() {
        resolver.inventory(A).set(0, snapshot("minecraft:diamond", 10));
        TradeId id = openTrade();
        assertEquals(TradeError.INVALID_ITEM_SLOT, service.offerItem(A, id, 1, -1, 1).error());
        assertEquals(TradeError.INVALID_COUNT, service.offerItem(A, id, 1, 0, 0).error());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(0, trade.leftOffer().items().size());
    }

    @Test
    void offerItemOverLimitRejectedWithoutDeduction() {
        FakeStore store = resolver.inventory(A);
        for (int i = 0; i < 40; i++) {
            store.set(i, snapshot("minecraft:stick", 1));
        }
        TradeId id = openTrade();
        long rev = 1;
        for (int i = 0; i < 27; i++) {
            rev = service.offerItem(A, id, rev, i, 1).revision();
        }
        TradeResult r = service.offerItem(A, id, rev, 27, 1);
        assertEquals(TradeError.OFFER_LIMIT_REACHED, r.error());
        // 超限物品未扣减
        assertFalse(store.get(27).isEmpty());
    }

    // ------------------------------------------------------------------ PKM 报价

    @Test
    void offerPkmEscrowsAndAdds() {
        resolver.wallet().add(A, 100);
        TradeId id = openTrade();

        TradeResult r = service.offerPkm(A, id, 1, 30);
        assertTrue(r.success());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(1, trade.leftOffer().pkm().size());
        assertEquals(30, trade.leftOffer().pkm().get(0).amount());
        assertEquals(70, resolver.wallet().find(A).orElseThrow().balance());
    }

    @Test
    void offerPkmInsufficientBalanceRejected() {
        resolver.wallet().add(A, 20);
        TradeId id = openTrade();
        assertEquals(TradeError.PKM_INSUFFICIENT_BALANCE,
                service.offerPkm(A, id, 1, 30).error());
        assertEquals(20, resolver.wallet().find(A).orElseThrow().balance());
        assertEquals(0, repo.getTrade(id).orElseThrow().leftOffer().pkm().size());
    }

    @Test
    void offerPkmIdempotentRepeatReturnsSameAsset() {
        resolver.wallet().add(A, 100);
        TradeId id = openTrade();
        long rev1 = service.offerPkm(A, id, 1, 30).revision();
        long rev2 = service.offerPkm(A, id, rev1, 30).revision();
        assertEquals(rev1, rev2); // 幂等重复不产生新 revision
        assertEquals(1, repo.getTrade(id).orElseThrow().leftOffer().pkm().size());
        assertEquals(70, resolver.wallet().find(A).orElseThrow().balance()); // 只扣一次
    }

    @Test
    void offerPkmReofferAfterRemoval() {
        resolver.wallet().add(A, 100);
        TradeId id = openTrade();
        long rev = service.offerPkm(A, id, 1, 30).revision();
        UUID assetId = repo.getTrade(id).orElseThrow().leftOffer().pkm().get(0).assetId();
        rev = service.removeAsset(A, id, rev, assetId).revision();
        assertTrue(repo.getTrade(id).orElseThrow().leftOffer().pkm().isEmpty());
        // 移除（退款 ROLLED_BACK）后重新报价同一 operationId -> gateway 重试，新条目
        TradeResult r = service.offerPkm(A, id, rev, 40);
        assertTrue(r.success());
        assertEquals(40, repo.getTrade(id).orElseThrow().leftOffer().pkm().get(0).amount());
        // 100 - 30 + 30 - 40 = 60
        assertEquals(60, resolver.wallet().find(A).orElseThrow().balance());
    }

    // ------------------------------------------------------------------ 宝可梦报价

    @Test
    void offerPokemonFromParty() {
        resolver.pokemonStorage(A).put(PokemonLocation.party(0), poke(PKM_X));
        TradeId id = openTrade();

        TradeResult r = service.offerPokemon(A, id, 1, PokemonLocator.party(0));
        assertTrue(r.success());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(1, trade.leftOffer().pokemon().size());
        assertEquals(PKM_X, trade.leftOffer().pokemon().get(0).pokemonId());
        assertTrue(resolver.pokemonStorage(A).at(PokemonLocation.party(0)).isEmpty());
    }

    @Test
    void offerPokemonFromPc() {
        resolver.pokemonStorage(A).put(PokemonLocation.pc(0, 1), poke(PKM_X));
        TradeId id = openTrade();
        assertTrue(service.offerPokemon(A, id, 1, PokemonLocator.pc(0, 1)).success());
        assertEquals(PKM_X, repo.getTrade(id).orElseThrow().leftOffer().pokemon().get(0).pokemonId());
    }

    @Test
    void offerPokemonAlreadyEscrowedElsewhereRejected() {
        // D 在另一笔交易中已托管 PKM_X（同一 UUID）
        PlayerTrade other = PlayerTrade.invited(TradeId.random(), C, D, NOW);
        other.accept(NOW);
        other.replaceOffer(TradeSide.RIGHT,
                other.rightOffer().withAdded(new PokemonAsset(
                        UUID.randomUUID(), D, PKM_X, new CompoundTag(), "party", -1, 0)), NOW);
        repo.addTrade(other);

        resolver.pokemonStorage(A).put(PokemonLocation.party(0), poke(PKM_X));
        TradeId id = openTrade();
        assertEquals(TradeError.POKEMON_ALREADY_ESCROWED,
                service.offerPokemon(A, id, 1, PokemonLocator.party(0)).error());
    }

    @Test
    void offerPokemonLastPartyRejected() {
        FakePort port = resolver.pokemonStorage(A);
        port.put(PokemonLocation.party(0), poke(PKM_X));
        port.usablePartyCount = 1;
        TradeId id = openTrade();
        assertEquals(TradeError.POKEMON_LAST_PARTY,
                service.offerPokemon(A, id, 1, PokemonLocator.party(0)).error());
    }

    @Test
    void offerPokemonOverLimitRejected() {
        FakePort port = resolver.pokemonStorage(A);
        TradeId id = openTrade();
        long rev = 1;
        for (int i = 0; i < 6; i++) {
            port.put(PokemonLocation.party(i), poke(UUID.randomUUID()));
            rev = service.offerPokemon(A, id, rev, PokemonLocator.party(i)).revision();
        }
        port.put(PokemonLocation.pc(0, 0), poke(PKM_Y));
        assertEquals(TradeError.OFFER_LIMIT_REACHED,
                service.offerPokemon(A, id, rev, PokemonLocator.pc(0, 0)).error());
    }

    // ------------------------------------------------------------------ 移除资产

    @Test
    void removeAssetItemReturnsToInventory() {
        FakeStore store = resolver.inventory(A);
        store.set(0, snapshot("minecraft:diamond", 10));
        TradeId id = openTrade();
        long rev = service.offerItem(A, id, 1, 0, 10).revision();
        UUID assetId = repo.getTrade(id).orElseThrow().leftOffer().items().get(0).assetId();

        TradeResult r = service.removeAsset(A, id, rev, assetId);
        assertTrue(r.success());
        assertTrue(repo.getTrade(id).orElseThrow().leftOffer().items().isEmpty());
        // 物品立即回到背包（deliver 到空槽 0）
        assertEquals(10, store.get(0).count());
    }

    @Test
    void removeAssetPkmRefundsWallet() {
        resolver.wallet().add(A, 100);
        TradeId id = openTrade();
        long rev = service.offerPkm(A, id, 1, 30).revision();
        UUID assetId = repo.getTrade(id).orElseThrow().leftOffer().pkm().get(0).assetId();

        TradeResult r = service.removeAsset(A, id, rev, assetId);
        assertTrue(r.success());
        assertTrue(repo.getTrade(id).orElseThrow().leftOffer().pkm().isEmpty());
        assertEquals(100, resolver.wallet().find(A).orElseThrow().balance());
    }

    @Test
    void removeAssetNotOwnedRejected() {
        resolver.wallet().add(A, 100);
        resolver.wallet().add(B, 100);
        TradeId id = openTrade();
        long rev = service.offerPkm(A, id, 1, 30).revision();
        UUID otherAssetId = repo.getTrade(id).orElseThrow().rightOffer() == null ? null : null;
        // B 尝试移除 A 的资产（B 不在报价中该资产 -> ASSET_NOT_OWNED）
        assertEquals(TradeError.ASSET_NOT_OWNED,
                service.removeAsset(B, id, rev, otherAssetId == null ? UUID.randomUUID() : otherAssetId).error());
    }

    // ------------------------------------------------------------------ 偏好

    @Test
    void setDeliveryPreferenceUpdatesAndPersists() {
        TradeId id = openTrade();
        DeliveryPreference pc = new DeliveryPreference(
                DeliveryPreference.ItemDestination.AUTO, DeliveryPreference.PokemonDestination.PC);
        TradeResult r = service.setDeliveryPreference(A, id, 1, pc);
        assertTrue(r.success());
        assertEquals(2, r.revision());
        assertEquals(pc, repo.getPreference(A));
        assertEquals(pc, repo.getTrade(id).orElseThrow().preferenceOf(TradeSide.LEFT));
    }

    // ------------------------------------------------------------------ 确认 / 锁定

    @Test
    void bothConfirmEntersLockedAndFreezesQuote() {
        TradeId id = openTrade();
        // A 先确认（未锁定）
        TradeResult r1 = service.confirm(A, id, 1);
        assertTrue(r1.success());
        assertEquals(TradeStatus.OPEN, repo.getTrade(id).orElseThrow().status());
        // B 确认同一 revision -> LOCKED，冻结 quote
        TradeResult r2 = service.confirm(B, id, 1);
        assertTrue(r2.success());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(TradeStatus.LOCKED, trade.status());
        assertNotNull(trade.feeQuote());
        assertEquals(NOW + PlayerTrade.LOCK_DURATION_MILLIS, trade.lockDeadlineEpochMillis());
        assertEquals(id, trade.tradeId());
    }

    @Test
    void confirmAfterOfferChangeRejectsStaleRevision() {
        TradeId id = openTrade();
        service.confirm(A, id, 1);
        // B 修改报价 -> revision +1
        resolver.inventory(B).set(0, snapshot("minecraft:emerald", 5));
        long rev2 = service.offerItem(B, id, 1, 0, 5).revision();
        // A 用旧 revision 确认 -> STALE_REVISION
        assertEquals(TradeError.STALE_REVISION, service.confirm(A, id, 1).error());
        assertTrue(rev2 > 1);
    }

    // ------------------------------------------------------------------ 取消

    @Test
    void cancelReturnsAssetsToOwners() {
        FakeStore storeA = resolver.inventory(A);
        FakeStore storeB = resolver.inventory(B);
        storeA.set(0, snapshot("minecraft:diamond", 10));
        storeB.set(0, snapshot("minecraft:emerald", 5));
        TradeId id = openTrade();
        long rev = service.offerItem(A, id, 1, 0, 10).revision();
        rev = service.offerItem(B, id, rev, 0, 5).revision();
        service.confirm(A, id, rev);
        service.confirm(B, id, rev);

        TradeResult r = service.cancel(A, id, rev);
        assertTrue(r.success());
        // 交易已删除
        assertTrue(repo.getTrade(id).isEmpty());
        // cancel 内已触发 claim：A 收回 10 钻石、B 收回 5 绿宝石（各自原资产）
        assertEquals("minecraft:diamond", storeA.get(0).itemId());
        assertEquals(10, storeA.get(0).count());
        assertEquals("minecraft:emerald", storeB.get(0).itemId());
        assertEquals(5, storeB.get(0).count());
    }

    @Test
    void cancelRejectedAfterCommitting() {
        TradeId id = openTrade();
        long rev = service.confirm(A, id, 1).revision();
        service.confirm(B, id, rev);
        // 已 LOCKED 但未到期：锁定期仍可取消
        assertTrue(service.cancel(A, id, rev).success());
        assertTrue(repo.getTrade(id).isEmpty());
    }

    // ------------------------------------------------------------------ 提交

    @Test
    void commitSwapsAssetsAndCompletes() {
        // A：10 钻石、钱包 100、Party(0)=PKM_X
        FakeStore storeA = resolver.inventory(A);
        storeA.set(0, snapshot("minecraft:diamond", 10));
        resolver.wallet().add(A, 100);
        resolver.pokemonStorage(A).put(PokemonLocation.party(0), poke(PKM_X));
        // B：5 绿宝石、钱包 200、PC(0,0)=PKM_Y
        FakeStore storeB = resolver.inventory(B);
        storeB.set(0, snapshot("minecraft:emerald", 5));
        resolver.wallet().add(B, 200);
        resolver.pokemonStorage(B).put(PokemonLocation.pc(0, 0), poke(PKM_Y));

        TradeId id = openTrade();
        long rev = service.offerItem(A, id, 1, 0, 10).revision();
        rev = service.offerItem(B, id, rev, 0, 5).revision();
        rev = service.offerPkm(A, id, rev, 30).revision();
        rev = service.offerPkm(B, id, rev, 50).revision();
        rev = service.offerPokemon(A, id, rev, PokemonLocator.party(0)).revision();
        rev = service.offerPokemon(B, id, rev, PokemonLocator.pc(0, 0)).revision();
        service.confirm(A, id, rev);
        service.confirm(B, id, rev);
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS + 1);

        TradeResult r = service.commit(id);
        assertTrue(r.success(), r.error().name());

        // 收件箱条目：A 收到 B 的 5 绿宝石 / 50 PKM / PKM_Y；B 收到 A 的 10 钻石 / 30 PKM / PKM_X
        assertEquals(3, repo.inboxOf(A).size());
        assertEquals(3, repo.inboxOf(B).size());
        assertEquals(1, repo.receipts.size());
        // commit 内 claim 已交付
        assertTrue(repo.inboxOf(A).stream().allMatch(e -> e.state() == InboxEntry.InboxState.DELIVERED));
        assertTrue(repo.inboxOf(B).stream().allMatch(e -> e.state() == InboxEntry.InboxState.DELIVERED));
        // 交易终态删除
        assertTrue(repo.getTrade(id).isEmpty());
        // 资产到位：A 背包 5 绿宝石、钱包 120、存储含 PKM_Y
        assertEquals("minecraft:emerald", storeA.get(0).itemId());
        assertEquals(5, storeA.get(0).count());
        assertEquals(120, resolver.wallet().find(A).orElseThrow().balance());
        assertEquals(PKM_Y, resolver.pokemonStorage(A).locate(PKM_Y).isPresent() ? PKM_Y : null);
        // B 背包 10 钻石、钱包 180、存储含 PKM_X
        assertEquals("minecraft:diamond", storeB.get(0).itemId());
        assertEquals(10, storeB.get(0).count());
        assertEquals(180, resolver.wallet().find(B).orElseThrow().balance());
        assertEquals(PKM_X, resolver.pokemonStorage(B).locate(PKM_X).isPresent() ? PKM_X : null);
    }

    @Test
    void commitBeforeLockExpiryRejected() {
        TradeId id = openTrade();
        long rev = service.confirm(A, id, 1).revision();
        service.confirm(B, id, rev);
        clock.advance(1000); // 未到期
        assertEquals(TradeError.INVALID_STATE, service.commit(id).error());
        assertEquals(TradeStatus.LOCKED, repo.getTrade(id).orElseThrow().status());
    }

    @Test
    void commitWithOfflinePlayerReopens() {
        TradeId id = openTrade();
        long rev = service.confirm(A, id, 1).revision();
        service.confirm(B, id, rev);
        clock.advance(PlayerTrade.LOCK_DURATION_MILLIS + 1);
        resolver.online.remove(A);
        assertEquals(TradeError.TARGET_OFFLINE, service.commit(id).error());
        PlayerTrade trade = repo.getTrade(id).orElseThrow();
        assertEquals(TradeStatus.OPEN, trade.status());
        assertEquals(rev + 1, trade.revision());
    }

    // ------------------------------------------------------------------ 快照

    @Test
    void snapshotUsesSelfPerspective() {
        resolver.inventory(A).set(0, snapshot("minecraft:diamond", 10));
        TradeId id = openTrade();
        long rev = service.offerItem(A, id, 1, 0, 10).revision();

        Optional<TradeSnapshot> snap = service.snapshot(A);
        assertTrue(snap.isPresent());
        TradeSnapshot s = snap.get();
        assertEquals(A, s.selfPlayerId());
        assertEquals(B, s.otherPlayerId());
        assertEquals(1, s.selfOffer().items().size());
        assertTrue(s.otherOffer().items().isEmpty());
        assertEquals(TradeStatus.OPEN, s.status());
        assertEquals(rev, s.revision());
    }

    @Test
    void snapshotEmptyWithoutTrade() {
        assertTrue(service.snapshot(C).isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private TradeId openTrade() {
        TradeId id = service.invite(A, B).tradeId();
        service.accept(B, id, 0);
        return id;
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

    // ------------------------------------------------------------------ fakes

    static final class FakeRepo implements TradeRepository {
        final Map<TradeId, PlayerTrade> trades = new LinkedHashMap<>();
        final Map<UUID, List<InboxEntry>> inbox = new HashMap<>();
        final Map<UUID, DeliveryPreference> prefs = new HashMap<>();
        final List<TradeReceipt> receipts = new ArrayList<>();
        final Map<String, OperationEntry> ops = new HashMap<>();

        @Override
        public Optional<PlayerTrade> getTrade(TradeId id) {
            return Optional.ofNullable(trades.get(id));
        }

        @Override
        public Optional<PlayerTrade> findTradeOf(UUID playerId) {
            return trades.values().stream().filter(t -> t.isParticipant(playerId)).findFirst();
        }

        @Override
        public Optional<TradeId> findTradeByPokemon(UUID pokemonId) {
            return trades.values().stream()
                    .filter(t -> t.leftOffer().pokemon().stream().anyMatch(p -> p.pokemonId().equals(pokemonId))
                            || t.rightOffer().pokemon().stream().anyMatch(p -> p.pokemonId().equals(pokemonId)))
                    .map(PlayerTrade::tradeId)
                    .findFirst();
        }

        @Override
        public void addTrade(PlayerTrade trade) {
            trades.put(trade.tradeId(), trade);
        }

        @Override
        public PlayerTrade updateTrade(TradeId id, UnaryOperator<PlayerTrade> transform) {
            PlayerTrade updated = transform.apply(trades.get(id));
            trades.put(id, updated);
            return updated;
        }

        @Override
        public boolean removeTrade(TradeId id) {
            return trades.remove(id) != null;
        }

        @Override
        public Optional<InboxEntry> getInboxEntry(UUID entryId) {
            return inbox.values().stream()
                    .flatMap(List::stream)
                    .filter(e -> e.entryId().equals(entryId))
                    .findFirst();
        }

        @Override
        public List<InboxEntry> inboxOf(UUID playerId) {
            return new ArrayList<>(inbox.getOrDefault(playerId, List.of()));
        }

        @Override
        public void addInboxEntry(InboxEntry entry) {
            inbox.computeIfAbsent(entry.recipientId(), k -> new ArrayList<>()).add(entry);
        }

        @Override
        public void updateInboxEntry(UUID entryId, UnaryOperator<InboxEntry> transform) {
            for (List<InboxEntry> list : inbox.values()) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).entryId().equals(entryId)) {
                        list.set(i, transform.apply(list.get(i)));
                        return;
                    }
                }
            }
        }

        @Override
        public DeliveryPreference getPreference(UUID playerId) {
            return prefs.getOrDefault(playerId, DeliveryPreference.defaults());
        }

        @Override
        public void setPreference(UUID playerId, DeliveryPreference preference) {
            prefs.put(playerId, preference);
        }

        @Override
        public void addReceipt(TradeReceipt receipt) {
            receipts.add(receipt);
        }

        @Override
        public List<PlayerTrade> activeTrades() {
            return new ArrayList<>(trades.values());
        }

        @Override
        public Optional<OperationEntry> get(String operationId) {
            return Optional.ofNullable(ops.get(operationId));
        }

        @Override
        public void record(OperationEntry entry) {
            ops.put(entry.operationId(), entry);
        }

        @Override
        public void update(OperationEntry entry) {
            ops.put(entry.operationId(), entry);
        }
    }

    static final class FakeResolver implements PlayerStorageResolver {
        final Set<UUID> online = new HashSet<>();
        final Map<UUID, FakeStore> inventories = new HashMap<>();
        final Map<UUID, FakePort> pokemonStores = new HashMap<>();
        final FakeWalletPort wallet = new FakeWalletPort();

        @Override
        public boolean isOnline(UUID playerId) {
            return online.contains(playerId);
        }

        @Override
        public String displayName(UUID playerId) {
            return "Player" + (playerId.hashCode() & 0xffff);
        }

        @Override
        public Collection<UUID> onlinePlayers() {
            return new ArrayList<>(online);
        }

        @Override
        public FakeStore inventory(UUID playerId) {
            return inventories.computeIfAbsent(playerId, p -> new FakeStore(40));
        }

        @Override
        public FakeWalletPort wallet() {
            return wallet;
        }

        @Override
        public FakePort pokemonStorage(UUID playerId) {
            return pokemonStores.computeIfAbsent(playerId, p -> new FakePort());
        }
    }

    static final class FakeStore implements PlayerInventoryStore {
        private final List<ItemSnapshot> slots;
        private int changes;

        FakeStore(int size) {
            this.slots = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                slots.add(ItemSnapshot.empty());
            }
        }

        @Override
        public int size() {
            return slots.size();
        }

        @Override
        public ItemSnapshot get(int slot) {
            return slots.get(slot);
        }

        @Override
        public void set(int slot, ItemSnapshot snapshot) {
            slots.set(slot, snapshot == null ? ItemSnapshot.empty() : snapshot);
        }

        /** 断言辅助：背包中是否恰好存在指定 id 的单个堆叠 */
        boolean hasItem(String itemId, int count) {
            return slots.stream().anyMatch(s -> !s.isEmpty()
                    && itemId.equals(s.itemId()) && s.count() == count);
        }

        @Override
        public int maxStack(int slot) {
            return 64;
        }

        @Override
        public boolean isFull() {
            return slots.stream().noneMatch(s -> s.isEmpty());
        }

        @Override
        public void setChanged() {
            changes++;
        }
    }

    static final class FakePort implements PokemonStoragePort {
        final Map<PokemonLocation, StoredPokemon> storage = new HashMap<>();
        int partyCapacity = 6;
        int boxCount = 2;
        int boxCapacity = 3;
        int usablePartyCount = 6;

        void put(PokemonLocation loc, StoredPokemon pokemon) {
            storage.put(loc, pokemon);
        }

        @Override
        public int partyCapacity() {
            return partyCapacity;
        }

        @Override
        public int boxCount() {
            return boxCount;
        }

        @Override
        public int boxCapacity(int box) {
            return box < 0 || box >= boxCount ? 0 : boxCapacity;
        }

        @Override
        public int usablePartyCount() {
            return usablePartyCount;
        }

        @Override
        public Optional<StoredPokemon> at(PokemonLocation location) {
            return Optional.ofNullable(storage.get(location));
        }

        @Override
        public Optional<PokemonLocation> locate(UUID pokemonId) {
            return storage.entrySet().stream()
                    .filter(e -> e.getValue().pokemonId().equals(pokemonId))
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        @Override
        public Optional<StoredPokemon> remove(PokemonLocation location) {
            return Optional.ofNullable(storage.remove(location));
        }

        @Override
        public boolean place(PokemonLocation location, StoredPokemon pokemon) {
            if (location.isParty() ? location.slot() >= partyCapacity
                    : (location.box() >= boxCount || location.slot() >= boxCapacity(location.box()))) {
                return false;
            }
            if (storage.containsKey(location)) {
                return false;
            }
            storage.put(location, pokemon);
            return true;
        }
    }

    static final class FakeWalletPort implements WalletPort {
        private final Map<UUID, FakeAccount> accounts = new HashMap<>();

        FakeAccount add(UUID playerId, long balance) {
            FakeAccount account = new FakeAccount(balance);
            accounts.put(playerId, account);
            return account;
        }

        @Override
        public Optional<WalletAccount> find(UUID playerId) {
            return Optional.ofNullable(accounts.get(playerId));
        }
    }

    static final class FakeAccount implements WalletAccount {
        long balance;
        boolean idempotent = true;

        FakeAccount(long balance) {
            this.balance = balance;
        }

        @Override
        public long balance() {
            return balance;
        }

        @Override
        public boolean debit(long amount) {
            if (balance < amount) {
                return false;
            }
            balance -= amount;
            return true;
        }

        @Override
        public boolean credit(long amount) {
            balance += amount;
            return true;
        }

        @Override
        public boolean supportsIdempotency() {
            return idempotent;
        }
    }

    static final class FakeClock extends Clock {
        long millis;

        FakeClock(long millis) {
            this.millis = millis;
        }

        void advance(long ms) {
            millis += ms;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
