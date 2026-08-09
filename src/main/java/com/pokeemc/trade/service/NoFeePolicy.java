package com.pokeemc.trade.service;

import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeFeeQuote;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 默认零手续费策略（Task 6）：首版不做任何扣费，quote 恒为零。
 * quote 有效期 30 秒，绑定当前 revision。
 */
public final class NoFeePolicy implements TradeFeePolicy {

    public static final String POLICY_ID = "none";

    private static final int VERSION = 1;

    private static final long QUOTE_VALIDITY_MILLIS = 30 * 1000L;

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public int policyVersion() {
        return VERSION;
    }

    @Override
    public TradeFeeQuote quote(TradeFeeContext context) {
        Instant now = context.now();
        return new TradeFeeQuote(
                UUID.randomUUID(),
                context.tradeId(),
                context.revision(),
                now.toEpochMilli() + QUOTE_VALIDITY_MILLIS,
                0L,
                0L,
                List.of(),
                policyId(),
                policyVersion());
    }

    @Override
    public FeeReservation reserve(TradeFeeQuote quote, PlayerTrade trade) {
        return FeeReservation.empty(quote);
    }

    @Override
    public FeeApplyResult apply(FeeReservation reservation) {
        return FeeApplyResult.noop();
    }

    @Override
    public FeeRefundResult refund(FeeReservation reservation) {
        return FeeRefundResult.noop();
    }
}
