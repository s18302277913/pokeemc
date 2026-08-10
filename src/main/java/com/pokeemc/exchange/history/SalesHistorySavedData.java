package com.pokeemc.exchange.history;

import com.poketrade.api.TradeItemId;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [NEW] 会话 #21-H 修订：交易所「学习模式」的个性化出售历史（世界级持久化）。
 *
 * <p>记录每个玩家「出售过哪些物品」（{@code player UUID → Set<TradeItemId>}），挂 overworld
 * {@code DataStorage}，数据名 {@value #DATA_NAME}。学习模式目录按它过滤：只显示当前玩家
 * 卖过的物品（卖过才能买回），未出售的物品隐藏——每个玩家各自一份，重启保留。</p>
 *
 * <p>序列化/反序列化为纯静态方法（不含 {@code HolderLookup} 依赖），JVM 测试可往返；
 * 非法/损坏的 itemId 在加载期忽略，不抛异常。</p>
 */
public class SalesHistorySavedData extends SavedData {

    public static final String DATA_NAME = "pokeemc_sales_history";

    private final Map<UUID, Set<TradeItemId>> byPlayer = new HashMap<>();

    public SalesHistorySavedData() {
        super();
    }

    // ---------------------------------------------------------------- 工厂

    public static SalesHistorySavedData create() {
        return new SalesHistorySavedData();
    }

    public static SavedData.Factory<SalesHistorySavedData> factory() {
        return new SavedData.Factory<>(SalesHistorySavedData::create, SalesHistorySavedData::load);
    }

    public static SalesHistorySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SalesHistorySavedData data = new SalesHistorySavedData();
        if (tag.contains("players", Tag.TAG_LIST)) {
            ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
            for (int i = 0; i < players.size(); i++) {
                CompoundTag p = players.getCompound(i);
                UUID uuid = p.hasUUID("uuid") ? p.getUUID("uuid") : null;
                if (uuid == null) {
                    continue;
                }
                Set<TradeItemId> items = new HashSet<>();
                if (p.contains("items", Tag.TAG_LIST)) {
                    ListTag list = p.getList("items", Tag.TAG_STRING);
                    for (int j = 0; j < list.size(); j++) {
                        try {
                            items.add(TradeItemId.parse(list.getString(j)));
                        } catch (RuntimeException ignored) {
                            // 非法/损坏 id 忽略，不污染该玩家记录
                        }
                    }
                }
                data.byPlayer.put(uuid, items);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, Set<TradeItemId>> e : byPlayer.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            CompoundTag p = new CompoundTag();
            p.putUUID("uuid", e.getKey());
            ListTag items = new ListTag();
            for (TradeItemId id : e.getValue()) {
                items.add(StringTag.valueOf(id.toString()));
            }
            p.put("items", items);
            players.add(p);
        }
        tag.put("players", players);
        return tag;
    }

    // ---------------------------------------------------------------- 读写

    /** 记录一次出售；仅在集合实际新增时标脏（重复出售不触发写盘）。 */
    public void record(UUID playerId, TradeItemId id) {
        Set<TradeItemId> set = byPlayer.computeIfAbsent(playerId, k -> new HashSet<>());
        if (set.add(id)) {
            setDirty();
        }
    }

    public boolean hasSold(UUID playerId, TradeItemId id) {
        Set<TradeItemId> set = byPlayer.get(playerId);
        return set != null && set.contains(id);
    }

    /** 该玩家已出售物品（不可变快照；无记录返回空集）。 */
    public Set<TradeItemId> soldItems(UUID playerId) {
        Set<TradeItemId> set = byPlayer.get(playerId);
        return set == null ? Set.of() : Set.copyOf(set);
    }
}
