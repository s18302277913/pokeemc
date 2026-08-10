package com.pokeemc.exchange.history;

import com.poketrade.api.TradeItemId;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [NEW] 会话 #21-H 修订：学习模式个性化出售历史的持久化与查询语义。
 * 纯 NBT 编解码（不依赖服务端/注册表），JVM 可往返——与 TradeSavedData 测试同思路。
 */
class SalesHistorySavedDataTest {

    // 编解码不读取 registries（与 TradeSavedData.load(saved, null) 同约定）
    private static final HolderLookup.Provider REGISTRIES = null;

    @Test
    void recordHasSoldAndSoldItems() {
        SalesHistorySavedData data = new SalesHistorySavedData();
        UUID p = UUID.randomUUID();
        TradeItemId diamond = TradeItemId.parse("minecraft:diamond");

        assertFalse(data.hasSold(p, diamond));
        assertTrue(data.soldItems(p).isEmpty(), "新玩家无出售历史");

        data.record(p, diamond);
        assertTrue(data.hasSold(p, diamond));
        assertEquals(Set.of(diamond), data.soldItems(p));
    }

    @Test
    void duplicateRecordIsIdempotent() {
        SalesHistorySavedData data = new SalesHistorySavedData();
        UUID p = UUID.randomUUID();
        TradeItemId coal = TradeItemId.parse("minecraft:coal");

        data.record(p, coal);
        data.record(p, coal); // 重复出售同一物品 → 集合去重，不重复记

        assertEquals(Set.of(coal), data.soldItems(p));
    }

    @Test
    void historyIsPerPlayer() {
        SalesHistorySavedData data = new SalesHistorySavedData();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        data.record(a, TradeItemId.parse("minecraft:diamond"));

        assertTrue(data.hasSold(a, TradeItemId.parse("minecraft:diamond")));
        assertFalse(data.hasSold(b, TradeItemId.parse("minecraft:diamond")),
                "玩家B 不应看到玩家A 的出售历史（个性化）");
    }

    @Test
    void saveLoadRoundTrips() {
        SalesHistorySavedData data = new SalesHistorySavedData();
        UUID p = UUID.randomUUID();
        data.record(p, TradeItemId.parse("minecraft:diamond"));
        data.record(p, TradeItemId.parse("pixelmon:poke_ball#master_ball"));
        // 空集合玩家（只 record 从不 add 无法构造；改为直接不写入空集——save 跳过空集）
        UUID empty = UUID.randomUUID();
        data.record(empty, TradeItemId.parse("minecraft:diamond"));
        data.record(empty, TradeItemId.parse("minecraft:diamond")); // 幂等

        CompoundTag tag = data.save(new CompoundTag(), REGISTRIES);
        SalesHistorySavedData loaded = SalesHistorySavedData.load(tag, REGISTRIES);

        assertTrue(loaded.hasSold(p, TradeItemId.parse("minecraft:diamond")));
        assertTrue(loaded.hasSold(p, TradeItemId.parse("pixelmon:poke_ball#master_ball")),
                "含 '#' 的球类 id 也要往返");
        assertTrue(loaded.hasSold(empty, TradeItemId.parse("minecraft:diamond")));
        assertFalse(loaded.hasSold(UUID.randomUUID(), TradeItemId.parse("minecraft:diamond")),
                "未记录玩家加载后仍无历史");
    }

    @Test
    void loadIgnoresCorruptItemIds() {
        SalesHistorySavedData data = new SalesHistorySavedData();
        UUID p = UUID.randomUUID();
        data.record(p, TradeItemId.parse("minecraft:diamond"));

        CompoundTag tag = data.save(new CompoundTag(), REGISTRIES);
        // 往该玩家 items 列表塞一条非法 id：加载期应跳过不抛，其余保留
        ListTag players = tag.getList("players", Tag.TAG_COMPOUND);
        CompoundTag player = players.getCompound(0);
        player.getList("items", Tag.TAG_STRING).add(StringTag.valueOf("not a valid id!!"));

        SalesHistorySavedData loaded = SalesHistorySavedData.load(tag, REGISTRIES);

        assertTrue(loaded.hasSold(p, TradeItemId.parse("minecraft:diamond")),
                "合法记录不受损坏条目影响");
    }

    @Test
    void saveSkipsPlayersWithEmptyHistory() {
        SalesHistorySavedData data = new SalesHistorySavedData();
        CompoundTag tag = data.save(new CompoundTag(), REGISTRIES);
        SalesHistorySavedData loaded = SalesHistorySavedData.load(tag, REGISTRIES);
        assertTrue(loaded.soldItems(UUID.randomUUID()).isEmpty(),
                "空历史 save/load 不应产生幻影玩家记录");
    }
}
