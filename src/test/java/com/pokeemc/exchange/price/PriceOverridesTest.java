package com.pokeemc.exchange.price;

import com.google.gson.JsonParser;
import com.poketrade.api.TradeItemId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PriceOverridesTest {

    @Test
    void parsesOverrides() {
        String json = """
                { "items": {
                    "pixelmon:master_ball": { "buyPrice": 5000000, "sellPrice": 0 }
                } }
                """;
        Map<TradeItemId, PriceOverrides.OverridePrice> out =
                PriceOverrides.parse(JsonParser.parseString(json));
        PriceOverrides.OverridePrice mb = out.get(TradeItemId.parse("pixelmon:master_ball"));
        assertNotNull(mb);
        assertEquals(5_000_000L, mb.buy());
        assertEquals(0L, mb.sell());
    }

    @Test
    void validatesMasterBallFixedPrice() {
        // 大师球覆盖价不等于 500 万 -> 视为配置错误，不静默纠正
        String json = """
                { "items": { "pixelmon:master_ball": { "buyPrice": 123456, "sellPrice": 0 } } }
                """;
        assertThrows(IllegalStateException.class,
                () -> PriceOverrides.parse(JsonParser.parseString(json)));
    }

    @Test
    void missingMasterBallInjectedWithDefault() {
        String json = "{ \"items\": {} }";
        Map<TradeItemId, PriceOverrides.OverridePrice> out =
                PriceOverrides.parse(JsonParser.parseString(json));
        PriceOverrides.OverridePrice mb = out.get(TradeItemId.parse("pixelmon:master_ball"));
        assertNotNull(mb);
        assertEquals(PriceOverrides.MASTER_BALL_BUY_PRICE, mb.buy());
    }

    @Test
    void sellOnlyOverrideCollected() {
        String json = """
                { "items": {
                    "pixelmon:free_item": { "buyPrice": 0, "sellPrice": 100 }
                } }
                """;
        Map<TradeItemId, PriceOverrides.OverridePrice> out =
                PriceOverrides.parse(JsonParser.parseString(json));
        PriceOverrides.OverridePrice fi = out.get(TradeItemId.parse("pixelmon:free_item"));
        assertNotNull(fi);
        assertEquals(0L, fi.buy());
        assertEquals(100L, fi.sell());
    }

    @Test
    void zeroBothOverridesIgnored() {
        String json = """
                { "items": { "pixelmon:junk": { "buyPrice": 0, "sellPrice": 0 } } }
                """;
        Map<TradeItemId, PriceOverrides.OverridePrice> out =
                PriceOverrides.parse(JsonParser.parseString(json));
        assertFalse(out.containsKey(TradeItemId.parse("pixelmon:junk")));
    }

    @Test
    void invalidIdSkipped() {
        String json = """
                { "items": { "Not A Valid id!": { "buyPrice": 100, "sellPrice": 10 } } }
                """;
        Map<TradeItemId, PriceOverrides.OverridePrice> out =
                PriceOverrides.parse(JsonParser.parseString(json));
        // 非法 id 被跳过，仅剩大师球默认注入
        assertEquals(1, out.size());
        assertTrue(out.containsKey(TradeItemId.parse("pixelmon:master_ball")));
    }

    @Test
    void negativeOverridesClampedToZero() {
        String json = """
                { "items": { "pixelmon:weird": { "buyPrice": -5, "sellPrice": 10 } } }
                """;
        Map<TradeItemId, PriceOverrides.OverridePrice> out =
                PriceOverrides.parse(JsonParser.parseString(json));
        PriceOverrides.OverridePrice w = out.get(TradeItemId.parse("pixelmon:weird"));
        assertNotNull(w);
        assertEquals(0L, w.buy());
        assertEquals(10L, w.sell());
    }
}
