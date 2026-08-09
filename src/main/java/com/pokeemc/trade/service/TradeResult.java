package com.pokeemc.trade.service;

import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeId;

/**
 * 交易操作结果：成功/失败 + 稳定错误码 + 交易 id + 最新 revision。
 * 网络、命令与测试共享，避免只用 boolean 掩盖失败原因。
 */
public record TradeResult(boolean success, TradeError error, TradeId tradeId, long revision) {

    public static TradeResult ok(TradeId tradeId, long revision) {
        return new TradeResult(true, TradeError.NONE, tradeId, revision);
    }

    public static TradeResult fail(TradeId tradeId, TradeError error) {
        return new TradeResult(false, error, tradeId, 0);
    }

    public static TradeResult fail(TradeError error) {
        return fail(null, error);
    }
}
