package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.OperationLedger;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeReceipt;
import com.pokeemc.trade.persistence.InboxEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 交易仓储窄接口（Task 6）：TradeService 依赖的唯一数据访问抽象，
 * 生产实现桥接 {@code TradeSavedData}，测试注入内存 fake。
 * 继承 {@link OperationLedger} 提供资金操作预写日志能力。
 */
public interface TradeRepository extends OperationLedger {

    Optional<PlayerTrade> getTrade(TradeId id);

    /** 玩家活动交易索引（O(1)） */
    Optional<PlayerTrade> findTradeOf(UUID playerId);

    /** 宝可梦托管索引（O(1)）：该宝可梦当前所在交易 */
    Optional<TradeId> findTradeByPokemon(UUID pokemonId);

    void addTrade(PlayerTrade trade);

    PlayerTrade updateTrade(TradeId id, UnaryOperator<PlayerTrade> transform);

    boolean removeTrade(TradeId id);

    Optional<InboxEntry> getInboxEntry(UUID entryId);

    List<InboxEntry> inboxOf(UUID playerId);

    void addInboxEntry(InboxEntry entry);

    void updateInboxEntry(UUID entryId, UnaryOperator<InboxEntry> transform);

    DeliveryPreference getPreference(UUID playerId);

    void setPreference(UUID playerId, DeliveryPreference preference);

    void addReceipt(TradeReceipt receipt);

    /** 全部活动（非终态）交易，供超时扫描与提交调度 */
    List<PlayerTrade> activeTrades();
}
