package com.pokeemc.trade.asset;

import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.persistence.OperationEntry;
import com.pokeemc.trade.persistence.OperationEntry.OperationState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PKM 托管 gateway（Task 4，计划 3.3）：报价后立即从原所有者钱包预写借记，
 * 模组托管金额；成交时贷记对手方，取消时贷记回原所有者。
 * <p>
 * 默认 Pixelmon BankAccount 不支持跨存储 ACID，因此采用「预写日志 + 幂等
 * operation」实现可恢复原子性：先持久化 PENDING 条目，再执行资金操作，成功后
 * 标记 APPLIED、失败标记 ROLLED_BACK。崩溃后遗留的 PENDING 条目视为未知借记
 * 结果，进入人工处理（{@link TradeError#REQUIRES_ADMIN}）。
 * <p>
 * 单笔上限 {@link #MAX_PKM_AMOUNT}；幂等键为 operationId，重复调用安全。
 */
public final class PkmEscrowGateway {

    /** 单笔 PKM 金额上限（与冷凝器 {@code MAX_PKM} 一致，防溢出） */
    public static final long MAX_PKM_AMOUNT = Long.MAX_VALUE / 4;

    /** 操作种类：借记 */
    public static final String OP_DEBIT = "pkm_debit";

    /** 操作种类：贷记 */
    public static final String OP_CREDIT = "pkm_credit";

    private PkmEscrowGateway() {
    }

    /**
     * 阶段 1（预写借记）：把 {@code amount} 从原所有者钱包扣到托管。
     *
     * @param tradeId     交易 id（写入 operation ledger）
     * @param owner       原所有者（被借记方）
     * @param amount      托管金额（1..MAX_PKM_AMOUNT）
     * @param operationId 稳定幂等键（由调用方生成）
     * @return 成功返回已借记的 {@link PkmAsset}（debited=true）
     */
    public static Outcome<PkmAsset> escrow(WalletPort port, OperationLedger ledger,
                                           UUID tradeId, UUID owner, long amount,
                                           String operationId, long now) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(owner, "owner");
        if (operationId == null || operationId.isBlank()) {
            return Outcome.fail(TradeError.INVALID_INPUT);
        }
        if (amount <= 0 || amount > MAX_PKM_AMOUNT) {
            return Outcome.fail(TradeError.PKM_INVALID_AMOUNT);
        }
        // 幂等去重：同一 operation 已应用则直接返回既有资产
        Optional<OperationEntry> existing = ledger.get(operationId);
        if (existing.isPresent()) {
            OperationState st = existing.get().state();
            if (st == OperationState.APPLIED) {
                return Outcome.ok(rehydrate(existing.get()));
            }
            if (st == OperationState.PENDING) {
                // 崩溃遗留：无法确定借记是否已应用，交人工处理
                return Outcome.fail(TradeError.REQUIRES_ADMIN);
            }
            // ROLLED_BACK：上次失败，允许重新借记
        }
        Optional<WalletAccount> accountOpt = port.find(owner);
        if (accountOpt.isEmpty()) {
            return Outcome.fail(TradeError.PKM_DEBIT_FAILED);
        }
        WalletAccount account = accountOpt.get();
        if (!account.supportsIdempotency()) {
            return Outcome.fail(TradeError.PKM_ESCROW_UNSUPPORTED);
        }
        if (account.balance() < amount) {
            return Outcome.fail(TradeError.PKM_INSUFFICIENT_BALANCE);
        }

        UUID assetId = UUID.randomUUID();
        OperationEntry pending = OperationEntry.record(
                operationId, OP_DEBIT, tradeId, assetId, owner, amount, "escrow-debit", now);
        ledger.record(pending);
        if (account.debit(amount)) {
            ledger.update(pending.withState(OperationState.APPLIED));
            return Outcome.ok(new PkmAsset(assetId, owner, amount, operationId, true));
        }
        ledger.update(pending.withState(OperationState.ROLLED_BACK));
        return Outcome.fail(TradeError.PKM_DEBIT_FAILED);
    }

    /**
     * 阶段 2（成交贷记）：把托管金额贷记到对手方钱包。幂等，重复调用安全。
     */
    public static Outcome<Void> settle(WalletPort port, OperationLedger ledger,
                                       PkmAsset asset, UUID recipient, UUID tradeId,
                                       String operationId, long now) {
        return credit(port, ledger, asset, recipient, tradeId, operationId, now);
    }

    /**
     * 取消贷记：把托管金额贷记回原所有者钱包。幂等，重复调用安全。
     */
    public static Outcome<Void> refund(WalletPort port, OperationLedger ledger,
                                       PkmAsset asset, UUID tradeId,
                                       String operationId, long now) {
        return credit(port, ledger, asset, asset.originalOwner(), tradeId, operationId, now);
    }

    private static Outcome<Void> credit(WalletPort port, OperationLedger ledger,
                                        PkmAsset asset, UUID recipient, UUID tradeId,
                                        String operationId, long now) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(tradeId, "tradeId");
        if (operationId == null || operationId.isBlank()) {
            return Outcome.fail(TradeError.INVALID_INPUT);
        }
        if (!asset.debited()) {
            // 借记未确认应用，禁止贷记（防凭空入账）
            return Outcome.fail(TradeError.PKM_DEBIT_FAILED);
        }
        Optional<OperationEntry> existing = ledger.get(operationId);
        if (existing.isPresent()) {
            OperationState st = existing.get().state();
            if (st == OperationState.APPLIED) {
                return Outcome.ok(null); // 幂等成功
            }
            if (st == OperationState.PENDING) {
                return Outcome.fail(TradeError.REQUIRES_ADMIN);
            }
            // ROLLED_BACK：允许重试
        }
        Optional<WalletAccount> accountOpt = port.find(recipient);
        if (accountOpt.isEmpty()) {
            return Outcome.fail(TradeError.PKM_DEBIT_FAILED);
        }
        WalletAccount account = accountOpt.get();
        if (!account.supportsIdempotency()) {
            return Outcome.fail(TradeError.PKM_ESCROW_UNSUPPORTED);
        }
        OperationEntry pending = OperationEntry.record(
                operationId, OP_CREDIT, tradeId, asset.assetId(), recipient,
                asset.amount(), "escrow-credit", now);
        ledger.record(pending);
        if (account.credit(asset.amount())) {
            ledger.update(pending.withState(OperationState.APPLIED));
            // 退款（贷记回原所有者）即取消托管：回滚原借记操作，使同一幂等键可重新托管
            if (recipient.equals(asset.originalOwner())) {
                ledger.get(asset.debitOperationId()).ifPresent(debit ->
                        ledger.update(debit.withState(OperationState.ROLLED_BACK)));
            }
            return Outcome.ok(null);
        }
        ledger.update(pending.withState(OperationState.ROLLED_BACK));
        return Outcome.fail(TradeError.PKM_DEBIT_FAILED);
    }

    /** 从已应用的操作条目重建资产（幂等命中路径） */
    private static PkmAsset rehydrate(OperationEntry entry) {
        return new PkmAsset(entry.assetId(), entry.playerId(), entry.amount(),
                entry.operationId(), true);
    }
}
