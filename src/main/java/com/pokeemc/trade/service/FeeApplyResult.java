package com.pokeemc.trade.service;

import com.pokeemc.trade.model.TradeError;

import java.util.List;

/**
 * 手续费应用结果（计划 5.4）：apply 失败时不得先转移交易资产，
 * 交易回到 OPEN；已应用的 operation 可被取消路径 refund。
 */
public record FeeApplyResult(boolean success, TradeError error, List<String> appliedOperationIds) {

    public static FeeApplyResult noop() {
        return new FeeApplyResult(true, TradeError.NONE, List.of());
    }

    public static FeeApplyResult failed(TradeError error) {
        return new FeeApplyResult(false, error, List.of());
    }
}
