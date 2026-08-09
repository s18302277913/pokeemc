package com.pokeemc.trade.service;

import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeFeeQuote;

import java.util.List;
import java.util.UUID;

/**
 * 手续费预留（计划 5.4）：进入 COMMITTING 前由策略 reserve 生成，
 * 为每个扣费步骤携带幂等 operation id，保证 apply/refund 可恢复。
 * <p>
 * reserve 全部成功时 {@link #ok()} 为 true；任一扣费步骤失败时返回
 * {@link #failed}（策略内部已把部分成功扣费按 quote 退款步骤幂等退回），
 * 调用方（TradeService）据此回到 OPEN 而不进入提交。
 */
public record FeeReservation(String reservationId, TradeFeeQuote quote, List<String> operationIds, TradeError error) {

    /** 无手续费策略的预留（NoFeePolicy） */
    public static FeeReservation empty(TradeFeeQuote quote) {
        return new FeeReservation(UUID.randomUUID().toString(), quote, List.of(), TradeError.NONE);
    }

    /** 预留失败：内部已自回滚部分扣费，交易不应进入 COMMITTING */
    public static FeeReservation failed(TradeFeeQuote quote, TradeError error) {
        return new FeeReservation(UUID.randomUUID().toString(), quote, List.of(), error);
    }

    /** 预留是否全部成功 */
    public boolean ok() {
        return error == TradeError.NONE;
    }
}
