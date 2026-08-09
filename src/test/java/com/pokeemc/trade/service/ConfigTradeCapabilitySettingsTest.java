package com.pokeemc.trade.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置驱动交易能力设置：默认配置下服务器开关放行、玩家开关与限流恒放行。
 */
class ConfigTradeCapabilitySettingsTest {

    private final ConfigTradeCapabilitySettings settings = new ConfigTradeCapabilitySettings();

    @Test
    void tradingEnabledDefaultsToTrue() {
        assertTrue(settings.tradingEnabled());
    }

    @Test
    void playerTradingAlwaysAllowed() {
        assertTrue(settings.playerTradingEnabled(UUID.randomUUID()));
    }

    @Test
    void neverRateLimitedByDefault() {
        assertFalse(settings.isRateLimited(UUID.randomUUID()));
    }
}
