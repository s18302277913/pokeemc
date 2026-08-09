package com.pokeemc.trade.service;

import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeFeeQuote;

/**
 * 手续费策略（计划 5.4）：服务端生成稳定报价、预留并应用/退款。
 * 默认提供 {@link NoFeePolicy}；百分比费率策略（PkmPercentageFeePolicy）在后续落地。
 * quote 绑定 tradeId + revision + policyId + policyVersion，默认有效期 30 秒。
 */
public interface TradeFeePolicy {

    /** 稳定策略 id（用于审计与 quote 绑定） */
    String policyId();

    /** 策略版本；版本改变必须为新 quote 生成新报价 */
    int policyVersion();

    /** 生成手续费报价（绑定当前 revision） */
    TradeFeeQuote quote(TradeFeeContext context);

    /** 进入 COMMITTING 前预留：为每个扣费步骤生成幂等 operation id */
    FeeReservation reserve(TradeFeeQuote quote, PlayerTrade trade);

    /** 应用预留的手续费；失败时不得先转移交易资产，交易回到 OPEN */
    FeeApplyResult apply(FeeReservation reservation);

    /** 取消路径退款已应用的手续费 */
    FeeRefundResult refund(FeeReservation reservation);
}
