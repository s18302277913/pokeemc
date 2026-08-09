package com.pokeemc.trade.model;

import java.util.UUID;

/**
 * PKM 托管资产（计划 3.3）：报价后立即从原所有者钱包借记，模组托管金额。
 * {@code debitOperationId} 是稳定幂等键；{@code debited} 标记借记是否已确认应用。
 * <p>
 * 默认 Pixelmon BankAccount 不支持跨存储 ACID，通过预写日志 + 幂等 operation
 * 实现可恢复原子性；不支持的实现必须禁用 PKM 交易。
 */
public record PkmAsset(
        UUID assetId,
        UUID originalOwner,
        long amount,
        String debitOperationId,
        boolean debited
) implements TradeAsset {

    public PkmAsset {
        if (assetId == null || originalOwner == null) {
            throw new IllegalArgumentException("assetId/originalOwner cannot be null");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (debitOperationId == null || debitOperationId.isBlank()) {
            throw new IllegalArgumentException("debitOperationId cannot be blank");
        }
    }

    @Override
    public String kind() {
        return "PKM";
    }

    public PkmAsset withDebited(boolean newDebited) {
        return new PkmAsset(assetId, originalOwner, amount, debitOperationId, newDebited);
    }
}
