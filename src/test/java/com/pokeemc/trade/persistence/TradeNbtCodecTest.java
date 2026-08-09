package com.pokeemc.trade.persistence;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeAsset;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.model.TradeReceipt;
import com.pokeemc.trade.model.TradeSide;
import com.pokeemc.trade.model.TradeStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 2：NBT 编解码 round-trip 与 schema 版本控制测试。
 */
class TradeNbtCodecTest {

    private static final UUID LEFT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RIGHT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long NOW = 1_000_000L;

    // ------------------------------------------------------------------
    // round-trip：完整交易（物品 + PKM + 宝可梦 + 双方确认 + COMMITTING）
    // ------------------------------------------------------------------

    @Test
    void playerTradeRoundTripPreservesEverything() {
        TradeId id = TradeId.random();
        PlayerTrade trade = PlayerTrade.invited(id, LEFT, RIGHT, NOW);
        trade.accept(NOW + 100);
        trade.setDeliveryPreference(TradeSide.LEFT,
                new DeliveryPreference(DeliveryPreference.ItemDestination.INBOX,
                        DeliveryPreference.PokemonDestination.INBOX), NOW + 200);

        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:diamond");
        stack.putInt("Count", 16);
        trade.replaceOffer(TradeSide.LEFT,
                TradeOffer.empty()
                        .withAdded(new ItemAsset(UUID.randomUUID(), LEFT, stack))
                        .withAdded(new PkmAsset(UUID.randomUUID(), LEFT, 10_000L,
                                id.value() + ".LEFT.PKM.asset1", true)),
                NOW + 300);
        CompoundTag pkmNbt = new CompoundTag();
        pkmNbt.putString("UUID", UUID.randomUUID().toString());
        trade.replaceOffer(TradeSide.RIGHT,
                TradeOffer.empty().withAdded(new PokemonAsset(
                        UUID.randomUUID(), RIGHT, UUID.randomUUID(), pkmNbt,
                        "party", -1, 2)),
                NOW + 400);

        // 双方确认进入 LOCKED
        long rev = trade.revision();
        trade.confirm(TradeSide.LEFT, rev, NOW + 500);
        trade.confirm(TradeSide.RIGHT, rev, NOW + 600);
        long deadline = trade.lockDeadlineEpochMillis();
        // 倒计时到期 -> COMMITTING
        trade.beginCommit(deadline + 1);

        CompoundTag encoded = TradeNbtCodec.encodePlayerTrade(trade);
        PlayerTrade decoded = TradeNbtCodec.decodePlayerTrade(encoded);

        assertEquals(id, decoded.tradeId());
        assertEquals(LEFT, decoded.leftPlayerId());
        assertEquals(RIGHT, decoded.rightPlayerId());
        assertEquals(TradeStatus.COMMITTING, decoded.status());
        assertEquals(trade.revision(), decoded.revision());
        assertEquals(LEFT, decoded.leftOffer().items().get(0).originalOwner());
        assertEquals(16, decoded.leftOffer().items().get(0).stackNbt().getInt("Count"));
        assertEquals(10_000L, decoded.leftOffer().pkm().get(0).amount());
        assertTrue(decoded.leftOffer().pkm().get(0).debited());
        assertEquals("party", decoded.rightOffer().pokemon().get(0).sourceStorage());
        assertEquals(2, decoded.rightOffer().pokemon().get(0).sourceSlot());
        assertTrue(decoded.confirmed(TradeSide.LEFT));
        assertTrue(decoded.confirmed(TradeSide.RIGHT));
        assertEquals(deadline, decoded.lockDeadlineEpochMillis());
        assertEquals(DeliveryPreference.ItemDestination.INBOX,
                decoded.leftPreference().itemDestination());
        assertEquals(trade.createdAtEpochMillis(), decoded.createdAtEpochMillis());
        assertEquals(trade.updatedAtEpochMillis(), decoded.updatedAtEpochMillis());
        assertEquals(trade.expiresAtEpochMillis(), decoded.expiresAtEpochMillis());
    }

    @Test
    void feeQuoteRoundTrip() {
        TradeId id = TradeId.random();
        TradeFeeQuote quote = new TradeFeeQuote(
                UUID.randomUUID(), id.value(), 7, NOW + 60_000,
                1_000L, 2_000L,
                List.of(new TradeFeeQuote.ItemFee("minecraft:emerald", 3, LEFT)),
                "flat", 2);
        CompoundTag encoded = TradeNbtCodec.encodeFeeQuote(quote);
        TradeFeeQuote decoded = TradeNbtCodec.decodeFeeQuote(encoded);
        assertEquals(quote, decoded);
        assertTrue(decoded.validFor(id.value(), 7, NOW + 1_000));
        assertFalse(decoded.validFor(id.value(), 7, NOW + 61_000));
        assertFalse(decoded.validFor(id.value(), 8, NOW + 1_000));
    }

    @Test
    void receiptRoundTrip() {
        TradeId id = TradeId.random();
        TradeFeeQuote quote = new TradeFeeQuote(
                UUID.randomUUID(), id.value(), 5, NOW + 60_000,
                0L, 500L, List.of(), "flat", 1);
        TradeReceipt receipt = new TradeReceipt(
                id.value(), 5, java.time.Instant.ofEpochMilli(NOW + 1000), quote,
                0L, 500L,
                List.of(new TradeReceipt.ItemFeeApplied(
                        UUID.randomUUID(), "minecraft:emerald", 1, RIGHT, RIGHT)));
        CompoundTag encoded = TradeNbtCodec.encodeReceipt(receipt);
        TradeReceipt decoded = TradeNbtCodec.decodeReceipt(encoded);
        assertEquals(receipt, decoded);
    }

    @Test
    void inboxEntryRoundTrip() {
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:stick");
        stack.putInt("Count", 1);
        ItemAsset asset = new ItemAsset(UUID.randomUUID(), LEFT, stack);
        InboxEntry entry = InboxEntry.pending(
                UUID.randomUUID(), RIGHT, asset,
                DeliveryPreference.defaults(), 3, NOW);
        CompoundTag encoded = TradeNbtCodec.encodeInboxEntry(entry);
        InboxEntry decoded = TradeNbtCodec.decodeInboxEntry(encoded);
        assertEquals(entry, decoded);
        assertEquals(InboxEntry.InboxState.PENDING, decoded.state());
    }

    @Test
    void operationEntryRoundTrip() {
        OperationEntry op = OperationEntry.record(
                "trade.LEFT.asset1.PKM_DEBIT", "PKM_DEBIT",
                UUID.randomUUID(), UUID.randomUUID(), LEFT, 10_000L, "initial", NOW);
        CompoundTag encoded = TradeNbtCodec.encodeOperation(op);
        OperationEntry decoded = TradeNbtCodec.decodeOperation(encoded);
        assertEquals(op, decoded);
    }

    // ------------------------------------------------------------------
    // 全量 SavedData round-trip
    // ------------------------------------------------------------------

    @Test
    void fullSavedDataRoundTrip() {
        TradeId id = TradeId.random();
        PlayerTrade trade = PlayerTrade.invited(id, LEFT, RIGHT, NOW);
        trade.accept(NOW + 100);
        long rev = trade.revision();
        trade.confirm(TradeSide.LEFT, rev, NOW + 200);
        trade.confirm(TradeSide.RIGHT, rev, NOW + 300);

        TradeSavedData data = new TradeSavedData();
        data.addTrade(trade);
        data.addInboxEntry(InboxEntry.pending(
                UUID.randomUUID(), LEFT, new ItemAsset(
                        UUID.randomUUID(), RIGHT,
                        stack("minecraft:diamond", 1)),
                DeliveryPreference.defaults(), rev, NOW));
        data.addReceipt(new TradeReceipt(
                id.value(), rev, java.time.Instant.ofEpochMilli(NOW),
                new TradeFeeQuote(UUID.randomUUID(), id.value(), rev, NOW + 60_000,
                        0, 0, List.of(), "flat", 1),
                0, 0, List.of()));
        data.recordOperation(OperationEntry.record(
                "trade.LEFT.PKM_DEBIT", "PKM_DEBIT", id.value(),
                UUID.randomUUID(), LEFT, 500L, "test", NOW));
        data.setPreference(LEFT, new DeliveryPreference(
                DeliveryPreference.ItemDestination.INVENTORY,
                DeliveryPreference.PokemonDestination.PARTY));

        CompoundTag encoded = TradeNbtCodec.encodeAll(data);
        assertEquals(1, encoded.getInt("schema_version"));

        TradeSavedData decoded = new TradeSavedData();
        TradeNbtCodec.decodeAll(encoded, decoded);
        decoded.rebuildIndexes();

        assertTrue(decoded.getTrade(id).isPresent());
        assertEquals(TradeStatus.LOCKED, decoded.getTrade(id).get().status());
        assertEquals(1, decoded.inboxView().size());
        assertEquals(1, decoded.receiptsView().size());
        assertEquals(1, decoded.operationsView().size());
        assertEquals(DeliveryPreference.PokemonDestination.PARTY,
                decoded.getPreference(LEFT).pokemonDestination());
        // 索引重建：双方玩家都能找到活动交易
        assertTrue(decoded.findTradeOf(LEFT).isPresent());
        assertTrue(decoded.findTradeOf(RIGHT).isPresent());
    }

    @Test
    void duplicateAssetUuidTriggersSafeFailure() {
        UUID sharedAssetId = UUID.randomUUID();
        TradeId id1 = TradeId.random();
        PlayerTrade t1 = PlayerTrade.invited(id1, LEFT, RIGHT, NOW);
        t1.accept(NOW + 100);
        t1.replaceOffer(TradeSide.LEFT,
                TradeOffer.empty().withAdded(new ItemAsset(sharedAssetId, LEFT, stack("a", 1))),
                NOW + 200);

        TradeId id2 = TradeId.random();
        PlayerTrade t2 = PlayerTrade.invited(id2, UUID.randomUUID(), UUID.randomUUID(), NOW);
        t2.accept(NOW + 100);
        t2.replaceOffer(TradeSide.LEFT,
                TradeOffer.empty().withAdded(new ItemAsset(sharedAssetId, LEFT, stack("b", 1))),
                NOW + 200);

        TradeSavedData data = new TradeSavedData();
        data.restoreTrade(t1);
        data.restoreTrade(t2);
        assertThrows(IllegalStateException.class, data::rebuildIndexes);
    }

    @Test
    void unknownHigherSchemaRejected() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", 999);
        TradeSavedData data = new TradeSavedData();
        assertThrows(UnsupportedOperationException.class,
                () -> TradeNbtCodec.decodeAll(tag, data));
    }

    @Test
    void pokemonIndexRebuiltOnUpdate() {
        TradeId id = TradeId.random();
        PlayerTrade trade = PlayerTrade.invited(id, LEFT, RIGHT, NOW);
        trade.accept(NOW + 100);
        UUID pokemonId = UUID.randomUUID();
        trade.replaceOffer(TradeSide.LEFT,
                TradeOffer.empty().withAdded(new PokemonAsset(
                        UUID.randomUUID(), LEFT, pokemonId, new CompoundTag(),
                        "pc", 0, 1)),
                NOW + 200);

        TradeSavedData data = new TradeSavedData();
        data.addTrade(trade);
        assertTrue(data.findTradeByPokemon(pokemonId).isPresent());
        assertEquals(id, data.findTradeByPokemon(pokemonId).get());

        // 移除宝可梦后索引同步清除
        data.updateTrade(id, t -> {
            t.replaceOffer(TradeSide.LEFT, TradeOffer.empty(), NOW + 300);
            return t;
        });
        assertTrue(data.findTradeByPokemon(pokemonId).isEmpty());
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static CompoundTag stack(String itemId, int count) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", itemId);
        tag.putInt("Count", count);
        return tag;
    }
}
