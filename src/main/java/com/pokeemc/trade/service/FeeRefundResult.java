package com.pokeemc.trade.service;

import com.pokeemc.trade.model.TradeError;

/**
 * 手续费退款结果（计划 5.4）：取消路径对已应用的手续费逐笔退款，
 * 写入 operation ledger 供审计。
 */
public record FeeRefundResult(boolean success, TradeError error) {

    public static FeeRefundResult noop() {
        return new FeeRefundResult(true, TradeError.NONE);
    }

    public static FeeRefundResult failed(TradeError error) {
        return new FeeRefundResult(false, error);
    }
}
