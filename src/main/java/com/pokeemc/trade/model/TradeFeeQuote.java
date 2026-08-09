package com.pokeemc.trade.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 手续费报价（计划 2.6/5.4）：由服务端 TradeFeePolicy 生成并冻结进提交意图，
 * 客户端仅展示不得计算。quote 绑定 tradeId + revision + policyId + policyVersion，
 * 默认有效期 30 秒；报价或策略版本改变必须 revision + 1 并撤销确认。
 */
public record TradeFeeQuote(
        UUID quoteId,
        UUID tradeId,
        long quotedRevision,
        long expiresAtEpochMillis,
        long leftPkmFee,
        long rightPkmFee,
        List<ItemFee> itemFees,
        String policyId,
        int policyVersion
) {

    public TradeFeeQuote {
        if (quoteId == null || tradeId == null) {
            throw new IllegalArgumentException("quoteId/tradeId cannot be null");
        }
        if (itemFees == null) {
            throw new IllegalArgumentException("itemFees cannot be null");
        }
        if (leftPkmFee < 0 || rightPkmFee < 0) {
            throw new IllegalArgumentException("fees cannot be negative");
        }
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("policyId cannot be blank");
        }
    }

    public boolean expired(long nowEpochMillis) {
        return nowEpochMillis > expiresAtEpochMillis;
    }

    /** 与当前 revision 是否匹配且未过期 */
    public boolean validFor(UUID expectedTradeId, long expectedRevision, long nowEpochMillis) {
        return tradeId.equals(expectedTradeId)
                && quotedRevision == expectedRevision
                && !expired(nowEpochMillis);
    }

    /** 一方 PKM 费用条目（policyId/version 已审计；金额不允许为负） */
    public record ItemFee(
            String itemId,
            long count,
            UUID chargedToPlayerId
    ) {
        public ItemFee {
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalArgumentException("itemId cannot be blank");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }
}
