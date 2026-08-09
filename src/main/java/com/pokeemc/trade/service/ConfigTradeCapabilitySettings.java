package com.pokeemc.trade.service;

import com.pokeemc.config.PokeTradeConfig;

import java.util.UUID;

/**
 * 读取服务端配置 {@code trade.*} 组的交易能力设置（Task 11 接口的生产实现）。
 *
 * <p>{@link #tradingEnabled()} 来自 {@code trade.enabled}（服务器总开关）；
 * 玩家个人开关与限流暂由未来数据源（玩家命令/网络）提供，当前恒放行。</p>
 */
public final class ConfigTradeCapabilitySettings implements TradeCapabilitySettings {

    @Override
    public boolean tradingEnabled() {
        return PokeTradeConfig.tradeEnabled();
    }

    @Override
    public boolean playerTradingEnabled(UUID playerId) {
        return true;
    }

    @Override
    public boolean isRateLimited(UUID playerId) {
        return false;
    }
}
