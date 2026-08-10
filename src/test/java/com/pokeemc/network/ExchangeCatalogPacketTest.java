package com.pokeemc.network;

import com.poketrade.api.TradeItemId;
import com.poketrade.api.price.PriceCatalogEntry;
import com.poketrade.api.price.PriceQuote;
import com.poketrade.api.price.PriceSort;
import com.poketrade.api.price.PriceSource;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交易所目录包的 codec round-trip：新加入的黑白名单字段（{@code blockedItems} /
 * {@code allowedItems} / {@code allowlistEnabled}）必须与发送端字段顺序一致，
 * 发与收用同一 record，往返后字段完全一致。
 */
class ExchangeCatalogPacketTest {

    @Test
    void responseRoundTripsEntriesCategoriesAndSellRules() {
        ExchangeCatalogPacket.Response original = new ExchangeCatalogPacket.Response(
                "session-1",
                List.of(new ExchangeCatalogPacket.EntryWire(
                        "minecraft:diamond", 100L, 50L, "矿物", "common", "minecraft")),
                List.of("矿物", "unknown"),
                100_000L,
                List.of("pixelmon:master_ball"),
                List.of("pixelmon:poke_ball"),
                true, true, true,
                42L, false, "LEARNING",
                Map.of("minecraft:diamond", 50L));

        ExchangeCatalogPacket.Response decoded = roundTrip(original);

        assertEquals(original, decoded);
        assertEquals(42L, decoded.catalogVersion());
        assertEquals(false, decoded.truncated());
        // [NEW] 会话 #21-H：mode 字段往返一致
        assertEquals("LEARNING", decoded.mode());
        // [NEW] 会话 #21-H 修订：全量出售价表往返一致
        assertEquals(Map.of("minecraft:diamond", 50L), decoded.sellPrices());
    }

    @Test
    void responseRoundTripsEmptySellRules() {
        ExchangeCatalogPacket.Response original = new ExchangeCatalogPacket.Response(
                "session-2", List.of(), List.of("unknown"), 0L,
                List.of(), List.of(), false, false, false,
                0L, true, "FULL", Map.of());

        ExchangeCatalogPacket.Response decoded = roundTrip(original);

        assertEquals(original, decoded);
        assertEquals(List.of(), decoded.blockedItems());
        assertEquals(List.of(), decoded.allowedItems());
        assertEquals(false, decoded.allowlistEnabled());
        assertEquals(false, decoded.buyEnabled());
        assertEquals(false, decoded.sellEnabled());
        assertEquals(true, decoded.truncated());
        assertEquals("FULL", decoded.mode());
    }

    @Test
    void learningVisibleShowsOnlyItemsPlayerSold() {
        // [CHANGED] 会话 #21-H 修订：学习模式可见性 = 该玩家「出售过」该物品 且 当前有买入价。
        // 未出售过（即使有价）不显示——卖过才能买回。
        Set<TradeItemId> sold = Set.of(
                TradeItemId.parse("minecraft:diamond"),
                TradeItemId.parse("minecraft:emerald"));
        assertTrue(ExchangeCatalogPacket.learningVisible(
                entry("minecraft:diamond", 100L, 50L), sold));
        assertTrue(ExchangeCatalogPacket.learningVisible(
                entry("minecraft:emerald", 100L, 50L), sold));
        assertFalse(ExchangeCatalogPacket.learningVisible(
                entry("minecraft:coal", 100L, 50L), sold),
                "未出售过的物品（有价也不显示）");
    }

    @Test
    void learningVisibleHidesUnsoldPricedItemsAndBuyLocked() {
        // [CHANGED] 会话 #21-H 修订：大师球这类「有价但没卖过」的条目，学习模式必须隐藏
        // （此前按可卖规则判断会显示，但用户实际没卖过 → 列表不该出现，解决「有价格却不显示」）。
        assertFalse(ExchangeCatalogPacket.learningVisible(
                entry("pixelmon:poke_ball#master_ball", 5_000_000L, 5_000_000L),
                Set.of(TradeItemId.parse("minecraft:diamond"))),
                "大师球未出售 → 学习模式隐藏（需 /poketrade exchange mode full 才能买）");
        // 卖过但当前仅可卖（buy=0）：点了也买不了 → 隐藏
        assertFalse(ExchangeCatalogPacket.learningVisible(
                entry("minecraft:diamond", 0L, 50L),
                Set.of(TradeItemId.parse("minecraft:diamond"))),
                "卖过但当前无买入价（buy=0）→ 隐藏");
        // 卖过且当前可买 → 显示（空出售历史时列表为空）
        assertFalse(ExchangeCatalogPacket.learningVisible(
                entry("minecraft:diamond", 100L, 50L), Set.of()),
                "无任何出售历史 → 学习目录为空");
    }

    private static PriceCatalogEntry entry(String id, long buy, long sell) {
        return new PriceCatalogEntry(
                PriceQuote.of(TradeItemId.parse(id), buy, sell, PriceSource.OFFICIAL),
                "矿物", "", "common", "minecraft");
    }

    @Test
    void sellPricesRoundTripPreservesAllEntries() {
        // [NEW] 会话 #21-H 修订：全量出售价表（学习模式下出售预览必须覆盖全部有卖价的物品）
        Map<String, Long> allSell = new java.util.LinkedHashMap<>();
        allSell.put("minecraft:diamond", 50L);
        allSell.put("pixelmon:poke_ball#master_ball", 5_000_000L);
        allSell.put("minecraft:emerald", 90L); // 与目录无关的纯价表条目

        ExchangeCatalogPacket.Response original = new ExchangeCatalogPacket.Response(
                "session-4", List.of(), List.of("unknown"), 0L,
                List.of(), List.of(), false, false, false,
                7L, false, "LEARNING", allSell);

        ExchangeCatalogPacket.Response decoded = roundTrip(original);

        assertEquals(allSell, decoded.sellPrices());
        assertEquals("LEARNING", decoded.mode());
    }

    @Test
    void requestRoundTrips() {
        ExchangeCatalogPacket.Request original = new ExchangeCatalogPacket.Request(
                "session-3", "球", "战斗用品", PriceSort.CATEGORY);

        ExchangeCatalogPacket.Request decoded = roundTrip(original);

        assertEquals(original, decoded);
    }

    private static <T> T roundTrip(T payload) {
        // [CHANGED] 官方 API：旧构造已弃用（Neo 建议带 ConnectionType 上下文的构造）
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        if (payload instanceof ExchangeCatalogPacket.Response p) {
            ExchangeCatalogPacket.Response.STREAM_CODEC.encode(buf, p);
            return cast(ExchangeCatalogPacket.Response.STREAM_CODEC.decode(buf));
        }
        if (payload instanceof ExchangeCatalogPacket.Request p) {
            ExchangeCatalogPacket.Request.STREAM_CODEC.encode(buf, p);
            return cast(ExchangeCatalogPacket.Request.STREAM_CODEC.decode(buf));
        }
        throw new AssertionError("unhandled payload " + payload);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }
}
