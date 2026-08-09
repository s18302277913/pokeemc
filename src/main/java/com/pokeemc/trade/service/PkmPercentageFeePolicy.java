package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.OperationLedger;
import com.pokeemc.trade.asset.WalletAccount;
import com.pokeemc.trade.asset.WalletPort;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.persistence.OperationEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * PKM 百分比手续费策略（Task 11，计划 5.4）。
 * <p>
 * 按每方送出 PKM 计算单侧费率：{@code ceil(amount * basisPoints / 10_000)}
 * （用 BigInteger 计算防溢出，Task 13 步骤 1：金额上限可达
 * {@code Long.MAX_VALUE / 4}，乘法不得抛 {@link ArithmeticException}），
 * 可配最低/最高单侧上限。
 * reserve 阶段即时借记（与交易 PKM 托管同模式但 operation kind 不同），
 * 全部成功才返回 {@link FeeReservation#ok()}；任一扣费失败时把部分成功项
 * 按 quote 退款步骤幂等退回后再返回失败，交易由 TradeService 回到 OPEN。
 * <p>
 * 策略本身不重复扣费：以稳定 operation id（{@code tradeId:side:reserve}）
 * 幂等，崩溃恢复重入时跳过已 APPLIED / ROLLED_BACK 的扣费步骤。
 */
public final class PkmPercentageFeePolicy implements TradeFeePolicy {

    public static final String POLICY_ID = "pkm_percentage";
    public static final int POLICY_VERSION = 1;

    /** 手续费预留/退回的 operation kind（与交易资产托管 kind 区分） */
    public static final String OP_FEE_RESERVE = "fee_reserve";
    public static final String OP_FEE_CREDIT = "fee_credit";

    private static final long QUOTE_VALIDITY_MILLIS = 30_000L;
    private static final int DENOMINATOR = 10_000;
    private static final String RESERVE_SUFFIX = ":reserve";
    private static final String CREDIT_SUFFIX = ":credit";

    private final WalletPort wallet;
    private final OperationLedger ledger;
    private final int basisPoints;   // 万分之几；0 = 无费率
    private final long minimumFee;   // 单侧最低（<=0 不设）
    private final long maximumFee;   // 单侧最高（<=0 不设）

    public PkmPercentageFeePolicy(WalletPort wallet, OperationLedger ledger,
                                  int basisPoints, long minimumFee, long maximumFee) {
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        if (basisPoints < 0) {
            throw new IllegalArgumentException("basisPoints must be >= 0");
        }
        if (minimumFee < 0) {
            throw new IllegalArgumentException("minimumFee must be >= 0");
        }
        if (maximumFee < 0) {
            throw new IllegalArgumentException("maximumFee must be >= 0");
        }
        this.basisPoints = basisPoints;
        this.minimumFee = minimumFee;
        this.maximumFee = maximumFee;
    }

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public int policyVersion() {
        return POLICY_VERSION;
    }

    @Override
    public TradeFeeQuote quote(TradeFeeContext context) {
        long leftFee = feeFor(context.leftOffer().totalPkm());
        long rightFee = feeFor(context.rightOffer().totalPkm());
        return new TradeFeeQuote(
                UUID.randomUUID(),
                context.tradeId(),
                context.revision(),
                context.now().toEpochMilli() + QUOTE_VALIDITY_MILLIS,
                leftFee,
                rightFee,
                List.of(),
                policyId(),
                policyVersion());
    }

    /** 单侧费用：向上取整百分比，clamp 到最低/最高（BigInteger 防溢出，Task 13 步骤 1） */
    private long feeFor(long amount) {
        if (amount <= 0 || basisPoints == 0) {
            return 0;
        }
        java.math.BigInteger fee = java.math.BigInteger.valueOf(amount)
                .multiply(java.math.BigInteger.valueOf(basisPoints))
                .add(java.math.BigInteger.valueOf(DENOMINATOR - 1))
                .divide(java.math.BigInteger.valueOf(DENOMINATOR));
        long result = fee.min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        if (minimumFee > 0 && result < minimumFee) {
            result = minimumFee;
        }
        if (maximumFee > 0 && result > maximumFee) {
            result = maximumFee;
        }
        return result;
    }

    @Override
    public FeeReservation reserve(TradeFeeQuote quote, PlayerTrade trade) {
        List<String> opIds = new ArrayList<>();
        long now = System.currentTimeMillis();
        TradeError error = reserveSide(quote, trade.leftPlayerId(), quote.leftPkmFee(), "left", now, opIds);
        if (error == TradeError.NONE) {
            error = reserveSide(quote, trade.rightPlayerId(), quote.rightPkmFee(), "right", now, opIds);
        }
        if (error != TradeError.NONE) {
            // 部分成功扣费立即按 quote 退款步骤幂等退回，调用方回到 OPEN
            for (String opId : opIds) {
                Optional<OperationEntry> applied = ledger.get(opId);
                if (applied.isPresent()) {
                    refundAppliedReserve(applied.get(), now);
                }
            }
            return FeeReservation.failed(quote, error);
        }
        return new FeeReservation(UUID.randomUUID().toString(), quote, opIds, TradeError.NONE);
    }

    /** 预留单侧手续费；幂等（崩溃恢复重入不会重复扣费） */
    private TradeError reserveSide(TradeFeeQuote quote, UUID playerId, long fee, String side,
                                   long now, List<String> opIds) {
        if (fee <= 0) {
            return TradeError.NONE;
        }
        String opId = operationId(quote.tradeId(), side, RESERVE_SUFFIX);
        Optional<OperationEntry> existing = ledger.get(opId);
        if (existing.isPresent()) {
            OperationEntry.OperationState state = existing.get().state();
            if (state == OperationEntry.OperationState.APPLIED || state == OperationEntry.OperationState.ROLLED_BACK) {
                opIds.add(opId);
                return TradeError.NONE; // 已处理过：直接复用
            }
            // PENDING 遗留：继续重试
        }
        Optional<WalletAccount> account = wallet.find(playerId);
        if (account.isEmpty()) {
            return TradeError.PKM_ESCROW_UNSUPPORTED;
        }
        if (account.get().balance() < fee) {
            return TradeError.PKM_INSUFFICIENT_BALANCE;
        }
        OperationEntry op = OperationEntry.record(opId, OP_FEE_RESERVE, quote.tradeId(),
                null, playerId, fee, "fee reserve " + side, now);
        ledger.record(op);
        if (!account.get().debit(fee)) {
            ledger.update(op.withState(OperationEntry.OperationState.ROLLED_BACK));
            return TradeError.PKM_DEBIT_FAILED;
        }
        ledger.update(op.withState(OperationEntry.OperationState.APPLIED));
        opIds.add(opId);
        return TradeError.NONE;
    }

    /** 退回一笔已 APPLIED 的手续费预留（幂等：同一 credit operation 只贷记一次） */
    private void refundAppliedReserve(OperationEntry reserve, long now) {
        String creditId = reserve.operationId().replace(RESERVE_SUFFIX, CREDIT_SUFFIX);
        Optional<OperationEntry> existing = ledger.get(creditId);
        if (existing.isPresent()
                && existing.get().state() == OperationEntry.OperationState.APPLIED) {
            return;
        }
        OperationEntry credit = OperationEntry.record(creditId, OP_FEE_CREDIT, reserve.tradeId(),
                reserve.assetId(), reserve.playerId(), reserve.amount(), "fee refund", now);
        ledger.record(credit);
        Optional<WalletAccount> account = wallet.find(reserve.playerId());
        if (account.isPresent() && account.get().credit(reserve.amount())) {
            ledger.update(credit.withState(OperationEntry.OperationState.APPLIED));
            ledger.update(reserve.withState(OperationEntry.OperationState.ROLLED_BACK));
        } else {
            ledger.update(credit.withState(OperationEntry.OperationState.ROLLED_BACK));
        }
    }

    @Override
    public FeeApplyResult apply(FeeReservation reservation) {
        // 借记已在 reserve 完成；apply 仅确认所有扣费步骤都已 APPLIED
        for (String opId : reservation.operationIds()) {
            Optional<OperationEntry> op = ledger.get(opId);
            if (op.isEmpty() || op.get().state() != OperationEntry.OperationState.APPLIED) {
                return FeeApplyResult.failed(TradeError.FEE_RESERVE_FAILED);
            }
        }
        return FeeApplyResult.noop();
    }

    @Override
    public FeeRefundResult refund(FeeReservation reservation) {
        long now = System.currentTimeMillis();
        for (String opId : reservation.operationIds()) {
            Optional<OperationEntry> op = ledger.get(opId);
            if (op.isPresent()) {
                refundAppliedReserve(op.get(), now);
            }
        }
        return FeeRefundResult.noop();
    }

    private static String operationId(UUID tradeId, String side, String suffix) {
        return tradeId + ":" + side + suffix;
    }
}
