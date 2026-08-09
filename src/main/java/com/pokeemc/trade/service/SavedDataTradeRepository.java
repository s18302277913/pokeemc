package com.pokeemc.trade.service;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeReceipt;
import com.pokeemc.trade.persistence.InboxEntry;
import com.pokeemc.trade.persistence.OperationEntry;
import com.pokeemc.trade.persistence.TradeSavedData;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * {@link TradeRepository} 的生产实现：桥接世界级 {@link TradeSavedData}。
 * 只做薄委托，不含业务逻辑。
 */
public final class SavedDataTradeRepository implements TradeRepository {

    private final TradeSavedData data;

    public SavedDataTradeRepository(TradeSavedData data) {
        this.data = Objects.requireNonNull(data, "data");
    }

    @Override
    public Optional<PlayerTrade> getTrade(TradeId id) {
        return data.getTrade(id);
    }

    @Override
    public Optional<PlayerTrade> findTradeOf(UUID playerId) {
        return data.findTradeOf(playerId);
    }

    @Override
    public Optional<TradeId> findTradeByPokemon(UUID pokemonId) {
        return data.findTradeByPokemon(pokemonId);
    }

    @Override
    public void addTrade(PlayerTrade trade) {
        data.addTrade(trade);
    }

    @Override
    public PlayerTrade updateTrade(TradeId id, UnaryOperator<PlayerTrade> transform) {
        return data.updateTrade(id, transform);
    }

    @Override
    public boolean removeTrade(TradeId id) {
        return data.removeTrade(id);
    }

    @Override
    public Optional<InboxEntry> getInboxEntry(UUID entryId) {
        return data.getInboxEntry(entryId);
    }

    @Override
    public List<InboxEntry> inboxOf(UUID playerId) {
        return data.inboxOf(playerId);
    }

    @Override
    public void addInboxEntry(InboxEntry entry) {
        data.addInboxEntry(entry);
    }

    @Override
    public void updateInboxEntry(UUID entryId, UnaryOperator<InboxEntry> transform) {
        data.updateInboxEntry(entryId, transform);
    }

    @Override
    public DeliveryPreference getPreference(UUID playerId) {
        return data.getPreference(playerId);
    }

    @Override
    public void setPreference(UUID playerId, DeliveryPreference preference) {
        data.setPreference(playerId, preference);
    }

    @Override
    public void addReceipt(TradeReceipt receipt) {
        data.addReceipt(receipt);
    }

    @Override
    public List<PlayerTrade> activeTrades() {
        return data.activeTrades();
    }

    // ---- OperationLedger ----

    @Override
    public Optional<OperationEntry> get(String operationId) {
        return data.getOperation(operationId);
    }

    @Override
    public void record(OperationEntry entry) {
        data.recordOperation(entry);
    }

    @Override
    public void update(OperationEntry entry) {
        data.updateOperation(entry.operationId(), e -> entry);
    }
}
