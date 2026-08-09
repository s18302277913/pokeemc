package com.pokeemc.trade.persistence;

import java.util.Objects;
import java.util.UUID;

/**
 * 幂等资金操作 ledger 条目（计划 4.2 预写日志）：每个 PKM 借记/贷记、
 * 手续费预留/应用/退款都登记一条，携带稳定 {@code operationId}
 * （{@code tradeId + side + assetId + operation}），状态机可据此去重与对账。
 */
public record OperationEntry(
        String operationId,
        String kind,
        OperationState state,
        UUID tradeId,
        UUID assetId,
        UUID playerId,
        long amount,
        String detail,
        long createdAtEpochMillis
) {

    public OperationEntry {
        Objects.requireNonNull(operationId, "operationId");
        if (operationId.isBlank()) {
            throw new IllegalArgumentException("operationId cannot be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(tradeId, "tradeId");
    }

    public static OperationEntry record(
            String operationId, String kind, UUID tradeId,
            UUID assetId, UUID playerId, long amount, String detail, long now) {
        return new OperationEntry(
                operationId, kind, OperationState.PENDING,
                tradeId, assetId, playerId, amount, detail, now);
    }

    public OperationEntry withState(OperationState newState) {
        return new OperationEntry(
                operationId, kind, newState,
                tradeId, assetId, playerId, amount, detail, createdAtEpochMillis);
    }

    public OperationEntry withDetail(String newDetail) {
        return new OperationEntry(
                operationId, kind, state,
                tradeId, assetId, playerId, amount, newDetail, createdAtEpochMillis);
    }

    /** 操作生命周期：写入时 PENDING，成功后 APPLIED，退回时 ROLLED_BACK */
    public enum OperationState {
        PENDING,
        APPLIED,
        ROLLED_BACK
    }
}
