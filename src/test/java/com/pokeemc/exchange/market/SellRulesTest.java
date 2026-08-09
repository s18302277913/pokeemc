package com.pokeemc.exchange.market;

import com.google.gson.JsonParser;
import com.poketrade.api.TradeItemId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SellRulesTest {

    @Test
    void blacklistBlocksSell() {
        SellRules rules = SellRules.parse(JsonParser.parseString("""
                { "sellBlacklist": ["pixelmon:poke_ball#master_ball"], "requireConfirmValue": 100000 }
                """));
        assertFalse(rules.canSell(TradeItemId.parse("pixelmon:poke_ball#master_ball")));
        assertTrue(rules.canSell(TradeItemId.parse("pixelmon:poke_ball")));
    }

    @Test
    void whitelistOnlyWhenNonEmpty() {
        SellRules rules = SellRules.parse(JsonParser.parseString("""
                { "sellWhitelist": ["pixelmon:poke_ball"] }
                """));
        assertTrue(rules.canSell(TradeItemId.parse("pixelmon:poke_ball")));
        assertFalse(rules.canSell(TradeItemId.parse("pixelmon:potion")));
    }

    @Test
    void emptyWhitelistMeansAllowAll() {
        SellRules rules = SellRules.parse(JsonParser.parseString("{}"));
        assertTrue(rules.canSell(TradeItemId.parse("pixelmon:poke_ball")));
        // 未配置确认阈值时回退服务端配置默认值（100000）
        assertEquals(com.pokeemc.config.PokeTradeConfig.exchangeSellConfirmValue(),
                rules.requireConfirmValue());
    }

    @Test
    void confirmValueParsed() {
        SellRules rules = SellRules.parse(JsonParser.parseString(
                "{ \"requireConfirmValue\": 100000 }"));
        assertEquals(100_000L, rules.requireConfirmValue());
    }

    @Test
    void blacklistOverridesWhitelist() {
        // 同物品同时出现在黑白名单时，黑名单优先拦截（canSell 先查黑名单）。
        SellRules rules = SellRules.parse(JsonParser.parseString("""
                { "sellBlacklist": ["pixelmon:poke_ball#master_ball"],
                  "sellWhitelist": ["pixelmon:poke_ball", "pixelmon:poke_ball#master_ball"] }
                """));
        assertFalse(rules.canSell(TradeItemId.parse("pixelmon:poke_ball#master_ball")));
        assertTrue(rules.canSell(TradeItemId.parse("pixelmon:poke_ball")));
    }

    @Test
    void invalidIdIgnored() {
        // 非法 id（无冒号 / 空 path）解析时被忽略，不抛异常、不污染规则。
        SellRules rules = SellRules.parse(JsonParser.parseString("""
                { "sellBlacklist": ["not a valid id", "pixelmon:"] }
                """));
        assertTrue(rules.canSell(TradeItemId.parse("pixelmon:poke_ball")));
        assertTrue(rules.canSell(TradeItemId.parse("pixelmon:poke_ball#master_ball")));
    }

    @Test
    void nonObjectRootReturnsDefault() {
        assertSame(SellRules.DEFAULT, SellRules.parse(null));
        assertSame(SellRules.DEFAULT, SellRules.parse(JsonParser.parseString("[]")));
        assertSame(SellRules.DEFAULT, SellRules.parse(JsonParser.parseString("\"oops\"")));
    }

    @Test
    void exposesBlacklistWhitelistAndAllowlistEnabled() {
        SellRules rules = SellRules.parse(JsonParser.parseString("""
                { "sellBlacklist": ["pixelmon:poke_ball#master_ball"],
                  "sellWhitelist": ["pixelmon:poke_ball"] }
                """));
        assertEquals(Set.of(TradeItemId.parse("pixelmon:poke_ball#master_ball")), rules.blacklist());
        assertEquals(Set.of(TradeItemId.parse("pixelmon:poke_ball")), rules.whitelist());
        assertTrue(rules.allowlistEnabled());

        assertFalse(SellRules.DEFAULT.allowlistEnabled());
        assertEquals(Set.of(), SellRules.DEFAULT.blacklist());
        assertEquals(Set.of(), SellRules.DEFAULT.whitelist());
    }
}
