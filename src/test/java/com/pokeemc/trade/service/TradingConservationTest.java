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
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.persistence.InboxEntry;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 12 步骤 2：资产守恒 GameTest（JVM 化）。
 * 两个测试玩家执行混合交易（物品 + PKM + 宝可梦），断言系统前后
 * 物品总量、PKM 总额与宝可梦 UUID 集合守恒，且无重复 asset UUID。
 */
class TradingConservationTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PKM_X = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PKM_Y = UUID.fromString("22222222-2222-2222-2222-222222222222");
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
    void mixedTradeConservesSystemAssets() {
        // 交易前系统状态
        resolver.inventory(A).set(0, snapshot("minecraft:diamond", 10));
        resolver.wallet().add(A, 100);
        resolver.pokemonStorage(A).put(PokemonLocation.party(0), poke(PKM_X));
        resolver.inventory(B).set(0, snapshot("minecraft:emerald", 5));
        resolver.wallet().add(B, 200);
        resolver.pokemonStorage(B).put(PokemonLocation.pc(0, 0), poke(PKM_Y));
        SystemState before = systemState();

        // 混合交易：A 出 10 钻石 + 30 PKM + PKM_X；B 出 5 绿宝石 + 50 PKM + PKM_Y
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
        assertTrue(service.commit(id).success());

        // 交易后系统状态：总量守恒
        SystemState after = systemState();
        assertEquals(before.items, after.items, "item counts must be conserved");
        assertEquals(before.pkmTotal, after.pkmTotal, "total PKM must be conserved");
        assertEquals(before.pokemon, after.pokemon, "pokemon UUID set must be conserved");
        assertNoDuplicateAssets();
    }

    // ------------------------------------------------------------------ helpers

    private TradeId openTrade() {
        TradeId id = service.invite(A, B).tradeId();
        service.accept(B, id, 0);
        return id;
    }

    /** 全系统状态快照：物品总量 / PKM 总额 / 宝可梦 UUID 集合 */
    private SystemState systemState() {
        Map<String, Integer> items = new HashMap<>();
        long pkmTotal = 0;
        Set<UUID> pokemon = new HashSet<>();
        for (UUID player : List.of(A, B)) {
            for (int i = 0; i < resolver.inventory(player).size(); i++) {
                ItemSnapshot slot = resolver.inventory(player).get(i);
                if (!slot.isEmpty()) {
                    items.merge(slot.itemId(), slot.count(), Integer::sum);
                }
            }
            pkmTotal += resolver.wallet().find(player).map(WalletAccount::balance).orElse(0L);
            for (StoredPokemon pk : resolver.pokemonStorage(player).storage.values()) {
                pokemon.add(pk.pokemonId());
            }
        }
        return new SystemState(items, pkmTotal, pokemon);
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

    private record SystemState(Map<String, Integer> items, long pkmTotal, Set<UUID> pokemon) {
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
