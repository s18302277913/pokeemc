package com.pokeemc.trade.service;

import java.util.UUID;

/**
 * 能力计算所需的服务器/玩家开关与限流输入（Task 11）。
 * <p>
 * 生产实现读取服务端配置与玩家个人交易开关（按需接入）；默认全部放行，
 * 保证尚未配置时交易能力不受影响。
 */
public interface TradeCapabilitySettings {

    /** 服务器交易功能总开关（false → 全体 DISABLED_BY_SERVER） */
    boolean tradingEnabled();

    /** 玩家个人交易开关（false → 该玩家 DISABLED_BY_PLAYER） */
    boolean playerTradingEnabled(UUID playerId);

    /** 请求者是否被限流（true → RATE_LIMITED，仅影响该请求者视角） */
    boolean isRateLimited(UUID playerId);

    /** 默认配置：全部放行 */
    TradeCapabilitySettings DEFAULTS = new TradeCapabilitySettings() {
        @Override
        public boolean tradingEnabled() {
            return true;
        }

        @Override
        public boolean playerTradingEnabled(UUID playerId) {
            return true;
        }

        @Override
        public boolean isRateLimited(UUID playerId) {
            return false;
        }
    };
}
