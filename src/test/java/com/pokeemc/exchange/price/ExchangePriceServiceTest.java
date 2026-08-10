package com.pokeemc.exchange.price;

import com.pokeemc.emc.PKMManager;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.price.PriceQuote;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExchangePriceServiceTest {

    /** 用注入数据构建服务实例，绕过 Minecraft 注册表。 */
    private static ExchangePriceService service(
            Map<TradeItemId, OfficialPriceParser.DoublePrice> official,
            Map<TradeItemId, PriceOverrides.OverridePrice> overrides) {
        return new ExchangePriceService(official, overrides);
    }

    @Test
    void officialMultipliedByTen() {
        ExchangePriceService svc = service(
                Map.of(TradeItemId.parse("pixelmon:poke_ball"),
                        new OfficialPriceParser.DoublePrice(200.0, 65.0)),
                Map.of());
        Optional<PriceQuote> q = svc.quote(TradeItemId.parse("pixelmon:poke_ball"));
        assertTrue(q.isPresent());
        assertEquals(2000L, q.get().buyPrice());
        assertEquals(650L, q.get().sellPrice());
    }

    @Test
    void overrideWinsAndNotMultiplied() {
        // [CHANGED] 会话 #14：球类覆盖价键改为球种感知编码 pixelmon:poke_ball#master_ball
        ExchangePriceService svc = service(
                Map.of(TradeItemId.parse("pixelmon:poke_ball#master_ball"),
                        new OfficialPriceParser.DoublePrice(500000.0, 0.0)),
                Map.of(TradeItemId.parse("pixelmon:poke_ball#master_ball"),
                        new PriceOverrides.OverridePrice(5_000_000L, 0L)));
        PriceQuote q = svc.quote(TradeItemId.parse("pixelmon:poke_ball#master_ball")).orElseThrow();
        assertEquals(5_000_000L, q.buyPrice());
    }

    @Test
    void doubleToLongRoundsExactly() {
        // [CHANGED] 会话 #14：球类键改为球种感知编码 pixelmon:poke_ball#ultra_ball
        ExchangePriceService svc = service(
                Map.of(TradeItemId.parse("pixelmon:poke_ball#ultra_ball"),
                        new OfficialPriceParser.DoublePrice(1200.0, 400.0)),
                Map.of());
        PriceQuote q = svc.quote(TradeItemId.parse("pixelmon:poke_ball#ultra_ball")).orElseThrow();
        assertEquals(12_000L, q.buyPrice());
        assertEquals(4_000L, q.sellPrice());
    }

    @Test
    void ballVariantOverrideAppearsInCatalogWithBalancedPrices() {
        // 会话 #14：球种感知键 pixelmon:poke_ball#master_ball 应可解析、可报价、
        // 且 buy==sell 的覆盖价（无套利）条目出现在目录（此前幽灵键 pixelmon:master_ball
        // 因注册表不存在被 isObtainable 剔除，目录里没有大师球 → 客户端显示「暂无定价」）。
        TradeItemId masterBall = TradeItemId.parse("pixelmon:poke_ball#master_ball");
        ExchangePriceService svc = service(
                Map.of(),
                Map.of(masterBall, new PriceOverrides.OverridePrice(5_000_000L, 5_000_000L)));
        PriceQuote q = svc.quote(masterBall).orElse(null);
        assertNotNull(q, "球种感知键覆盖价应有报价");
        assertEquals(5_000_000L, q.buyPrice());
        assertEquals(5_000_000L, q.sellPrice());
        assertTrue(q.buyAvailable());
        assertTrue(svc.catalog().entries().stream()
                        .anyMatch(e -> e.quote().itemId().equals(masterBall)),
                "球种感知键条目应出现在目录");
    }

    @Test
    void unknownItemHasNoQuote() {
        ExchangePriceService svc = service(Map.of(), Map.of());
        assertTrue(svc.quote(TradeItemId.parse("minecraft:diamond")).isEmpty());
    }

    @Test
    void buyAlwaysAboveOrEqualSell() {
        // 防套利：官方 buy > sell（×10 后保持）；目录任意条目 buy >= sell
        ExchangePriceService svc = service(
                Map.of(
                        TradeItemId.parse("pixelmon:poke_ball"), new OfficialPriceParser.DoublePrice(200.0, 65.0),
                        TradeItemId.parse("pixelmon:great_ball"), new OfficialPriceParser.DoublePrice(600.0, 200.0),
                        TradeItemId.parse("pixelmon:potion"), new OfficialPriceParser.DoublePrice(300.0, 100.0)),
                Map.of());
        for (PriceQuote q : svc.catalog().entries().stream().map(e -> e.quote()).toList()) {
            assertTrue(q.buyPrice() >= q.sellPrice(),
                    () -> q.itemId() + " 买价低于卖价，存在套利");
        }
    }

    @Test
    void catalogIncludesCategoryAndModId() {
        ExchangePriceService svc = service(
                Map.of(TradeItemId.parse("pixelmon:poke_ball"),
                        new OfficialPriceParser.DoublePrice(200.0, 65.0)),
                Map.of());
        var entry = svc.catalog().entries().get(0);
        assertFalse(entry.category().isEmpty());
        assertEquals("pixelmon", entry.modId());
    }

    @Test
    void overrideWithBuyBelowSellThrows() {
        // 防套利：可买入覆盖价 buy < sell 视为配置错误，构造即失败
        assertThrows(IllegalStateException.class, () -> service(
                Map.of(),
                Map.of(TradeItemId.parse("pixelmon:potion"),
                        new PriceOverrides.OverridePrice(100L, 5_000L))));
    }

    @Test
    void sellOnlyOverrideAllowed() {
        // sell-only 覆盖（buy=0，不可买入）合法，不构成套利
        ExchangePriceService svc = service(
                Map.of(),
                Map.of(TradeItemId.parse("pixelmon:free_item"),
                        new PriceOverrides.OverridePrice(0L, 5_000L)));
        PriceQuote q = svc.quote(TradeItemId.parse("pixelmon:free_item")).orElse(null);
        assertNotNull(q);
        assertEquals(0L, q.buyPrice());
        assertEquals(5_000L, q.sellPrice());
    }

    private static ExchangePriceService service(
            Map<TradeItemId, OfficialPriceParser.DoublePrice> official,
            Map<TradeItemId, PriceOverrides.OverridePrice> overrides,
            Map<TradeItemId, Long> pkm) {
        return new ExchangePriceService(official, overrides, pkm);
    }

    @Test
    void pkmFallbackFillsGapsWithBalancedBuySell() {
        // 无官方/覆盖价的原版物品由 PKM 兜底：买价=卖价（可买可卖，天然无套利）
        ExchangePriceService svc = service(
                Map.of(),
                Map.of(),
                Map.of(TradeItemId.parse("minecraft:coal"), 128L));
        PriceQuote q = svc.quote(TradeItemId.parse("minecraft:coal")).orElse(null);
        assertNotNull(q);
        assertEquals(128L, q.buyPrice());
        assertEquals(128L, q.sellPrice());
        assertEquals(com.poketrade.api.price.PriceSource.PKM, q.source());
        assertTrue(q.buyAvailable());
    }

    @Test
    void pkmFallbackDoesNotOverrideOfficialOrOverride() {
        // [CHANGED] 会话 #14：master_ball 覆盖价键改为球种感知编码
        ExchangePriceService svc = service(
                Map.of(TradeItemId.parse("pixelmon:poke_ball"),
                        new OfficialPriceParser.DoublePrice(200.0, 65.0)),
                Map.of(TradeItemId.parse("pixelmon:poke_ball#master_ball"),
                        new PriceOverrides.OverridePrice(5_000_000L, 0L)),
                Map.of(
                        TradeItemId.parse("pixelmon:poke_ball"), 256L,
                        TradeItemId.parse("pixelmon:poke_ball#master_ball"), 65536L,
                        TradeItemId.parse("minecraft:diamond"), 1024L));
        PriceQuote ball = svc.quote(TradeItemId.parse("pixelmon:poke_ball")).orElseThrow();
        assertEquals(2000L, ball.buyPrice()); // 官方价优先，非 PKM 256
        PriceQuote master = svc.quote(TradeItemId.parse("pixelmon:poke_ball#master_ball")).orElseThrow();
        assertEquals(5_000_000L, master.buyPrice()); // 覆盖价优先，非 PKM 65536
        PriceQuote diamond = svc.quote(TradeItemId.parse("minecraft:diamond")).orElseThrow();
        assertEquals(1024L, diamond.sellPrice()); // 缺口由 PKM 兜底
    }

    @Test
    void pkmFallbackSkipsNonPositiveValues() {
        ExchangePriceService svc = service(
                Map.of(),
                Map.of(),
                Map.of(
                        TradeItemId.parse("minecraft:bedrock"), 0L,
                        TradeItemId.parse("minecraft:barrier"), -1L));
        assertEquals(0, svc.catalog().entries().size());
    }

    @Test
    void categoryOverrideTakesPriorityOverBallFallback() {
        // 会话 #16：数据驱动的分类覆盖（categories.json）必须优先于球类兜底与 Creative tab 扫描，
        // 让「分类: unknown」的物品可经数据映射归类。
        TradeItemId masterBall = TradeItemId.parse("pixelmon:poke_ball#master_ball");
        ExchangePriceService.applyCategoryOverrides(Map.of(masterBall, "itemGroup.materials"));
        try {
            ExchangePriceService svc = service(Map.of(), Map.of(), Map.of(masterBall, 5_000_000L));
            svc.rebuild();
            var entry = svc.catalog().entries().stream()
                    .filter(e -> e.quote().itemId().equals(masterBall))
                    .findFirst().orElse(null);
            assertNotNull(entry, "球类条目应出现在目录");
            assertEquals("itemGroup.materials", entry.category(), "数据驱动覆盖应优先于球类兜底");
        } finally {
            ExchangePriceService.applyCategoryOverrides(Map.of());
        }
    }

    @Test
    void ballVariantWithoutOverrideFallsBackToPokeballs() {
        // 会话 #16：球类（含 '#' 的 itemId）无覆盖时兜底到统一球类分类，
        // 修复无组件 base 栈对带 POKE_BALL 组件 displayItems 恒失配导致的全球类 unknown。
        TradeItemId masterBall = TradeItemId.parse("pixelmon:poke_ball#master_ball");
        ExchangePriceService.applyCategoryOverrides(Map.of());
        try {
            ExchangePriceService svc = service(Map.of(), Map.of(), Map.of(masterBall, 5_000_000L));
            var entry = svc.catalog().entries().stream()
                    .filter(e -> e.quote().itemId().equals(masterBall))
                    .findFirst().orElse(null);
            assertNotNull(entry, "球类条目应出现在目录");
            assertEquals("poketrade.category.pokeballs", entry.category(), "无覆盖球类应兜底精灵球分类");
        } finally {
            ExchangePriceService.applyCategoryOverrides(Map.of());
        }
    }

    @Test
    void rebuildClearsCategoryOverridesEffectOnLive() {
        // 会话 #16：rebuild() 开头清空 CATEGORY_CACHE 并复位分类重试标志——
        // 覆盖数据在 rebuild 后经 categoryOf 重新计算生效（数据驱动路径与缓存路径一致）。
        TradeItemId coal = TradeItemId.parse("minecraft:coal");
        ExchangePriceService.applyCategoryOverrides(Map.of(coal, "itemGroup.materials"));
        try {
            ExchangePriceService svc = service(Map.of(), Map.of(), Map.of(coal, 128L));
            assertEquals("itemGroup.materials",
                    svc.catalog().entries().get(0).category(),
                    "覆盖分类应在目录条目上生效");
        } finally {
            ExchangePriceService.applyCategoryOverrides(Map.of());
        }
    }

    @Test
    void liveCatalogTracksPkmVersionChanges() {
        // Bug A/B 回归：生产装配（live=true）读取全局 PKMManager 快照；
        // 合成树计算（setComputed）发生在目录构建之后，catalog() 必须按版本号惰性重建，
        // 否则服务端 quote 缺值导致「客户端显示有价却卖不了」。
        // 测试用独立 key，避免影响其他用例（现有测试均不读取全局 PKM 状态）。
        String id = "minecraft:netherite_upgrade_smithing_template";
        ExchangePriceService svc = new ExchangePriceService(
                Map.of(), Map.of(), Map.of(), true);
        assertTrue(svc.quote(TradeItemId.parse(id)).isEmpty(), "初始快照无该物品");

        // 模拟合成树在目录构建后补充新值 → 版本号变化
        PKMManager.setComputed(
                ResourceLocation.fromNamespaceAndPath("minecraft", "netherite_upgrade_smithing_template"), 2048L);
        try {
            Optional<PriceQuote> q = svc.quote(TradeItemId.parse(id));
            assertTrue(q.isPresent(), "catalog() 必须检测到 PKM 版本变化并自动重建");
            assertEquals(2048L, q.get().sellPrice(), "新值必须经 PKM 兜底进入目录");
        } finally {
            // 隔离：清理该测试写入的全局值，避免污染后续用例（clearComputed 会清空推导值）
            PKMManager.clearComputed();
        }
    }
}
