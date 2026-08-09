package com.pokeemc.trade.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 不可变交易回执（计划 4.2）：在 COMMITTING -> COMMITTED 的同一 SavedData 修改中写入，
 * 包含 quote ID、policy ID/version、双方费用与收货结果。用于审计与恢复，
 * 一经写入不得修改。
 */
public record TradeReceipt(
        UUID tradeId,
        long revision,
        Instant committedAt,
        TradeFeeQuote feeQuote,
        long leftPkmFee,
        long rightPkmFee,
        List<ItemFeeApplied> itemFeesApplied
) {

    public TradeReceipt {
        if (tradeId == null || committedAt == null) {
            throw new IllegalArgumentException("tradeId/committedAt cannot be null");
        }
        if (itemFeesApplied == null) {
            throw new IllegalArgumentException("itemFeesApplied cannot be null");
        }
        if (leftPkmFee < 0 || rightPkmFee < 0) {
            throw new IllegalArgumentException("fees cannot be negative");
        }
    }

    /** 已实际应用的一条物品手续费（count 已从付款人托管扣减或销毁） */
    public record ItemFeeApplied(
            UUID feeAssetId,
            String itemId,
            long count,
            UUID chargedToPlayerId,
            UUID feeSinkPlayerId
    ) {
        public ItemFeeApplied {
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalArgumentException("itemId cannot be blank");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }
}
