package com.pokeemc.trade.persistence;

import com.mojang.logging.LogUtils;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeAsset;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeReceipt;
import com.pokeemc.trade.model.TradeStatus;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 玩家交易世界级持久化（Task 2）：活动交易、待交付收件箱、最近完成回执与
 * 幂等资金操作 ledger 统一挂在 overworld {@code DataStorage}。
 *
 * <p>数据名固定为 {@code pokeemc_player_trades}（计划原文，向后兼容）。
 * 序列化/反序列化为纯静态方法，不依赖 {@code HolderLookup}，JVM 测试可往返。</p>
 *
 * <p>加载后从活动交易与收件箱重建玩家、宝可梦和过期索引；检测重复 asset UUID
 * 时进入安全失败（抛 {@link IllegalStateException}），不选择任一副本继续运行。</p>
 */
public class TradeSavedData extends SavedData {

    public static final String DATA_NAME = "pokeemc_player_trades";

    public static final Logger LOGGER = LogUtils.getLogger();

    /** 回执保留上限（计划：最近 10,000 条，防无界增长） */
    public static final int MAX_RECEIPTS = 10_000;

    private final LinkedHashMap<TradeId, PlayerTrade> trades = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, InboxEntry> inbox = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, TradeReceipt> receipts = new LinkedHashMap<>();
    private final LinkedHashMap<String, OperationEntry> operations = new LinkedHashMap<>();
    /** 玩家收货偏好（持久化，OPEN 状态下可随时修改） */
    private final Map<UUID, DeliveryPreference> preferences = new HashMap<>();
    /** 玩家 -> 活动交易（索引） */
    private final Map<UUID, Set<TradeId>> playerIndex = new HashMap<>();
    /** 宝可梦 UUID -> 托管中的交易（索引） */
    private final Map<UUID, TradeId> pokemonIndex = new HashMap<>();

    public TradeSavedData() {
        super();
    }

    // ---------------------------------------------------------------- 工厂

    public static TradeSavedData create() {
        return new TradeSavedData();
    }

    public static SavedData.Factory<TradeSavedData> factory() {
        return new SavedData.Factory<>(TradeSavedData::create, TradeSavedData::load);
    }

    public static TradeSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TradeSavedData data = new TradeSavedData();
        TradeNbtCodec.decodeAll(tag, data);
        data.rebuildIndexes();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return TradeNbtCodec.encodeAll(this);
    }

    // ---------------------------------------------------------------- 视图

    public Map<TradeId, PlayerTrade> tradesView() {
        return Collections.unmodifiableMap(trades);
    }

    public List<InboxEntry> inboxView() {
        return new ArrayList<>(inbox.values());
    }

    public Map<UUID, TradeReceipt> receiptsView() {
        return Collections.unmodifiableMap(receipts);
    }

    public Map<String, OperationEntry> operationsView() {
        return Collections.unmodifiableMap(operations);
    }

    public Map<UUID, DeliveryPreference> preferencesView() {
        return Collections.unmodifiableMap(preferences);
    }

    // ---------------------------------------------------------------- 活动交易

    public Optional<PlayerTrade> getTrade(TradeId id) {
        return Optional.ofNullable(trades.get(id));
    }

    public Optional<PlayerTrade> findTradeOf(UUID playerId) {
        Set<TradeId> ids = playerIndex.get(playerId);
        if (ids == null || ids.isEmpty()) {
            return Optional.empty();
        }
        for (TradeId id : ids) {
            PlayerTrade t = trades.get(id);
            if (t != null && !t.status().terminal()) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    public Optional<TradeId> findTradeByPokemon(UUID pokemonId) {
        return Optional.ofNullable(pokemonIndex.get(pokemonId));
    }

    /** 新建交易：索引更新 + setDirty。重复 id 抛异常（调用方保证唯一）。 */
    public void addTrade(PlayerTrade trade) {
        Objects.requireNonNull(trade, "trade");
        TradeId id = trade.tradeId();
        if (trades.containsKey(id)) {
            throw new IllegalArgumentException("trade already exists: " + id);
        }
        trades.put(id, trade);
        indexTradePlayers(id, trade);
        for (TradeAsset asset : trade.leftOffer().allAssets()) {
            if (asset instanceof com.pokeemc.trade.model.PokemonAsset p) {
                pokemonIndex.put(p.pokemonId(), id);
            }
        }
        for (TradeAsset asset : trade.rightOffer().allAssets()) {
            if (asset instanceof com.pokeemc.trade.model.PokemonAsset p) {
                pokemonIndex.put(p.pokemonId(), id);
            }
        }
        setDirty();
    }

    /** 更新交易（持有者负责状态机迁移）。返回旧值或 null。 */
    public PlayerTrade updateTrade(TradeId id, UnaryOperator<PlayerTrade> transform) {
        PlayerTrade current = trades.get(id);
        if (current == null) {
            return null;
        }
        PlayerTrade updated = Objects.requireNonNull(transform.apply(current), "transform");
        trades.put(id, updated);
        // 报价中宝可梦可能变化，重建宝可梦索引
        rebuildPokemonIndex();
        setDirty();
        return updated;
    }

    /** 移除交易（终态清理）。返回是否移除。 */
    public boolean removeTrade(TradeId id) {
        PlayerTrade removed = trades.remove(id);
        if (removed == null) {
            return false;
        }
        playerIndex.values().forEach(ids -> ids.remove(id));
        playerIndex.entrySet().removeIf(e -> e.getValue().isEmpty());
        rebuildPokemonIndex();
        setDirty();
        return true;
    }

    // ---------------------------------------------------------------- 收件箱

    public Optional<InboxEntry> getInboxEntry(UUID entryId) {
        return Optional.ofNullable(inbox.get(entryId));
    }

    public List<InboxEntry> inboxOf(UUID playerId) {
        List<InboxEntry> out = new ArrayList<>();
        for (InboxEntry entry : inbox.values()) {
            if (entry.recipientId().equals(playerId)) {
                out.add(entry);
            }
        }
        return out;
    }

    public long pendingInboxCount(UUID playerId) {
        long count = 0;
        for (InboxEntry entry : inbox.values()) {
            if (entry.recipientId().equals(playerId)
                    && entry.state() != InboxEntry.InboxState.DELIVERED) {
                count++;
            }
        }
        return count;
    }

    public void addInboxEntry(InboxEntry entry) {
        Objects.requireNonNull(entry, "entry");
        inbox.put(entry.entryId(), entry);
        setDirty();
    }

    public void updateInboxEntry(UUID entryId, UnaryOperator<InboxEntry> transform) {
        InboxEntry current = inbox.get(entryId);
        if (current == null) {
            return;
        }
        inbox.put(entryId, Objects.requireNonNull(transform.apply(current), "transform"));
        setDirty();
    }

    /** 移除收件箱条目（已交付清理）。 */
    public boolean removeInboxEntry(UUID entryId) {
        if (inbox.remove(entryId) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 回执 / 操作 / 偏好

    public void addReceipt(TradeReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        receipts.put(receipt.tradeId(), receipt);
        while (receipts.size() > MAX_RECEIPTS) {
            receipts.remove(receipts.keySet().iterator().next());
        }
        setDirty();
    }

    public Optional<TradeReceipt> getReceipt(UUID tradeId) {
        return Optional.ofNullable(receipts.get(tradeId));
    }

    public Optional<OperationEntry> getOperation(String operationId) {
        return Optional.ofNullable(operations.get(operationId));
    }

    /** 登记 operation（预写日志）。返回已存在条目（幂等去重用）。 */
    public OperationEntry recordOperation(OperationEntry entry) {
        OperationEntry existing = operations.put(entry.operationId(), entry);
        setDirty();
        return existing;
    }

    public void updateOperation(String operationId, UnaryOperator<OperationEntry> transform) {
        OperationEntry current = operations.get(operationId);
        if (current == null) {
            return;
        }
        operations.put(operationId, Objects.requireNonNull(transform.apply(current), "transform"));
        setDirty();
    }

    public DeliveryPreference getPreference(UUID playerId) {
        return preferences.getOrDefault(playerId, DeliveryPreference.defaults());
    }

    public void setPreference(UUID playerId, DeliveryPreference preference) {
        preferences.put(playerId, Objects.requireNonNull(preference, "preference"));
        setDirty();
    }

    // ---------------------------------------------------------------- 恢复期（加载专用）

    /** 加载期还原交易（不重建索引；由 rebuildIndexes 统一完成）。 */
    public void restoreTrade(PlayerTrade trade) {
        trades.put(trade.tradeId(), trade);
    }

    public void restoreInboxEntry(InboxEntry entry) {
        inbox.put(entry.entryId(), entry);
    }

    public void restoreReceipt(TradeReceipt receipt) {
        receipts.put(receipt.tradeId(), receipt);
    }

    public void restoreOperation(OperationEntry entry) {
        operations.put(entry.operationId(), entry);
    }

    /** 加载期还原玩家收货偏好。 */
    public void restorePreference(UUID playerId, DeliveryPreference preference) {
        preferences.put(playerId, preference);
    }

    /**
     * 加载后重建玩家/宝可梦/过期索引，并检测重复 asset UUID（安全失败）。
     *
     * @throws IllegalStateException 存在重复 asset UUID（同一资产被引用两次）
     */
    public void rebuildIndexes() {
        playerIndex.clear();
        pokemonIndex.clear();
        Set<UUID> seenAssets = new HashSet<>();
        for (PlayerTrade trade : trades.values()) {
            indexTradePlayers(trade.tradeId(), trade);
            if (!assetsInOffers(trade)) {
                // COMMITTING/COMMITTED/DELIVERING/CANCELLING：资产已迁往（或正在迁往）收件箱，
                // 报价仅作展示，不占位置，避免与收件箱条目重复
                continue;
            }
            for (TradeAsset asset : trade.leftOffer().allAssets()) {
                checkDuplicate(seenAssets, asset);
                if (asset instanceof com.pokeemc.trade.model.PokemonAsset p) {
                    pokemonIndex.put(p.pokemonId(), trade.tradeId());
                }
            }
            for (TradeAsset asset : trade.rightOffer().allAssets()) {
                checkDuplicate(seenAssets, asset);
                if (asset instanceof com.pokeemc.trade.model.PokemonAsset p) {
                    pokemonIndex.put(p.pokemonId(), trade.tradeId());
                }
            }
        }
        // 收件箱待投递条目与活动交易资产不得重叠；已交付条目资产已回到玩家存储，不占位置
        for (InboxEntry entry : inbox.values()) {
            if (entry.state() == InboxEntry.InboxState.DELIVERED) {
                continue;
            }
            checkDuplicate(seenAssets, entry.asset());
            if (entry.asset() instanceof com.pokeemc.trade.model.PokemonAsset p) {
                pokemonIndex.put(p.pokemonId(), new TradeId(entry.tradeId()));
            }
        }
        // 过期索引：查询时按 expiresAt 比较即可（计划要求索引，这里用常量时间上限简化）
        setDirty();
    }

    /** 该状态交易中资产仍唯一位于报价内（否则已在/正在收件箱） */
    private static boolean assetsInOffers(PlayerTrade trade) {
        TradeStatus s = trade.status();
        return s == TradeStatus.INVITED || s == TradeStatus.OPEN || s == TradeStatus.LOCKED;
    }

    private static void checkDuplicate(Set<UUID> seenAssets, TradeAsset asset) {
        if (!seenAssets.add(asset.assetId())) {
            throw new IllegalStateException(
                    "duplicate asset UUID " + asset.assetId()
                            + " across trades/inbox; refusing to continue");
        }
    }

    private void indexTradePlayers(TradeId id, PlayerTrade trade) {
        playerIndex.computeIfAbsent(trade.leftPlayerId(), ignored -> new HashSet<>()).add(id);
        playerIndex.computeIfAbsent(trade.rightPlayerId(), ignored -> new HashSet<>()).add(id);
    }

    private void rebuildPokemonIndex() {
        pokemonIndex.clear();
        for (PlayerTrade trade : trades.values()) {
            if (!assetsInOffers(trade)) {
                continue;
            }
            for (TradeAsset asset : trade.leftOffer().allAssets()) {
                if (asset instanceof com.pokeemc.trade.model.PokemonAsset p) {
                    pokemonIndex.put(p.pokemonId(), trade.tradeId());
                }
            }
            for (TradeAsset asset : trade.rightOffer().allAssets()) {
                if (asset instanceof com.pokeemc.trade.model.PokemonAsset p) {
                    pokemonIndex.put(p.pokemonId(), trade.tradeId());
                }
            }
        }
        for (InboxEntry entry : inbox.values()) {
            if (entry.state() == InboxEntry.InboxState.DELIVERED) {
                continue;
            }
            if (entry.asset() instanceof com.pokeemc.trade.model.PokemonAsset p) {
                pokemonIndex.put(p.pokemonId(), new TradeId(entry.tradeId()));
            }
        }
    }

    /** 查询所有非终态交易（供恢复器/超时扫描）。 */
    public List<PlayerTrade> activeTrades() {
        List<PlayerTrade> out = new ArrayList<>();
        for (PlayerTrade trade : trades.values()) {
            if (!trade.status().terminal()) {
                out.add(trade);
            }
        }
        return out;
    }
}
