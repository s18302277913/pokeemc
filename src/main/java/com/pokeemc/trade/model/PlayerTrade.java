package com.pokeemc.trade.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 玩家交易状态机核心（计划 4）。所有状态迁移只通过显式命名方法进行，
 * 禁止在网络 handler / 命令中直接 setStatus。状态机自身不访问任何
 * Minecraft/Pixelmon 存储；资产托管与交付由 TradeService 与 gateway 完成。
 *
 * <p>不变量（计划 1.3）：</p>
 * <ul>
 *   <li>任一报价改变 -> revision + 1，且清空双方确认（confirmedRevision = -1）；</li>
 *   <li>最终确认必须携带客户端最后看到的 revision，过期确认不得结算；</li>
 *   <li>双方确认同一 revision 后进入 {@link TradeStatus#LOCKED} 并冻结 quote/收货偏好；</li>
 *   <li>锁定取消/掉线/能力失效/quote 过期 -> 回到 OPEN 并 revision + 1、清空确认；</li>
 *   <li>COMMITTING 之后不可取消、不可回滚。</li>
 * </ul>
 */
public final class PlayerTrade {

    /** 交易默认超时（毫秒）：5 分钟 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    /** 邀请超时（毫秒）：30 秒 */
    public static final long INVITE_TIMEOUT_MILLIS = 30 * 1000L;

    /** 锁定倒计时（毫秒）：3 秒 */
    public static final long LOCK_DURATION_MILLIS = 3 * 1000L;

    private final TradeId tradeId;
    private final UUID leftPlayerId;
    private final UUID rightPlayerId;

    private TradeStatus status;
    private long revision;
    private TradeOffer leftOffer;
    private TradeOffer rightOffer;
    private long leftConfirmedRevision = -1;
    private long rightConfirmedRevision = -1;
    private DeliveryPreference leftPreference;
    private DeliveryPreference rightPreference;
    private TradeFeeQuote feeQuote;           // 冻结在 LOCKED/COMMITTING 时使用
    private long createdAtEpochMillis;
    private long updatedAtEpochMillis;
    private long expiresAtEpochMillis;
    private long lockDeadlineEpochMillis = -1;
    private TradeError failureError = TradeError.NONE;
    private String failureDetail = "";

    private PlayerTrade(Builder b) {
        this.tradeId = b.tradeId;
        this.leftPlayerId = b.leftPlayerId;
        this.rightPlayerId = b.rightPlayerId;
        this.status = b.status;
        this.revision = b.revision;
        this.leftOffer = b.leftOffer == null ? TradeOffer.empty() : b.leftOffer;
        this.rightOffer = b.rightOffer == null ? TradeOffer.empty() : b.rightOffer;
        this.leftConfirmedRevision = b.leftConfirmedRevision;
        this.rightConfirmedRevision = b.rightConfirmedRevision;
        this.leftPreference = b.leftPreference == null ? DeliveryPreference.defaults() : b.leftPreference;
        this.rightPreference = b.rightPreference == null ? DeliveryPreference.defaults() : b.rightPreference;
        this.feeQuote = b.feeQuote;
        this.createdAtEpochMillis = b.createdAtEpochMillis;
        this.updatedAtEpochMillis = b.updatedAtEpochMillis;
        this.expiresAtEpochMillis = b.expiresAtEpochMillis;
        this.lockDeadlineEpochMillis = b.lockDeadlineEpochMillis;
        this.failureError = b.failureError;
        this.failureDetail = b.failureDetail;
    }

    /** 新建 INVITED 状态交易（由 TradeService.invite 调用） */
    public static PlayerTrade invited(TradeId tradeId, UUID initiator, UUID target, long nowEpochMillis) {
        return new Builder(tradeId, initiator, target)
                .status(TradeStatus.INVITED)
                .createdAt(nowEpochMillis)
                .updatedAt(nowEpochMillis)
                .expiresAt(nowEpochMillis + INVITE_TIMEOUT_MILLIS)
                .build();
    }

    // ------------------------------------------------------------------
    // 只读访问
    // ------------------------------------------------------------------

    public TradeId tradeId() {
        return tradeId;
    }

    public UUID leftPlayerId() {
        return leftPlayerId;
    }

    public UUID rightPlayerId() {
        return rightPlayerId;
    }

    public TradeStatus status() {
        return status;
    }

    public long revision() {
        return revision;
    }

    public TradeOffer leftOffer() {
        return leftOffer;
    }

    public TradeOffer rightOffer() {
        return rightOffer;
    }

    public TradeOffer offerOf(TradeSide side) {
        return side == TradeSide.LEFT ? leftOffer : rightOffer;
    }

    public long leftConfirmedRevision() {
        return leftConfirmedRevision;
    }

    public long rightConfirmedRevision() {
        return rightConfirmedRevision;
    }

    public boolean confirmed(TradeSide side) {
        return side == TradeSide.LEFT
                ? leftConfirmedRevision == revision
                : rightConfirmedRevision == revision;
    }

    public DeliveryPreference leftPreference() {
        return leftPreference;
    }

    public DeliveryPreference rightPreference() {
        return rightPreference;
    }

    public DeliveryPreference preferenceOf(TradeSide side) {
        return side == TradeSide.LEFT ? leftPreference : rightPreference;
    }

    public TradeFeeQuote feeQuote() {
        return feeQuote;
    }

    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public long updatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    public long lockDeadlineEpochMillis() {
        return lockDeadlineEpochMillis;
    }

    public boolean hasLockDeadline() {
        return lockDeadlineEpochMillis > 0;
    }

    public TradeError failureError() {
        return failureError;
    }

    public String failureDetail() {
        return failureDetail;
    }

    /** 对方玩家 UUID */
    public UUID counterpartOf(UUID playerId) {
        if (leftPlayerId.equals(playerId)) {
            return rightPlayerId;
        }
        if (rightPlayerId.equals(playerId)) {
            return leftPlayerId;
        }
        return null;
    }

    public boolean isParticipant(UUID playerId) {
        return leftPlayerId.equals(playerId) || rightPlayerId.equals(playerId);
    }

    public boolean expired(long nowEpochMillis) {
        return nowEpochMillis > expiresAtEpochMillis;
    }

    /** 锁定倒计时剩余毫秒；未锁定返回 0 */
    public long lockRemainingMillis(long nowEpochMillis) {
        if (status != TradeStatus.LOCKED || lockDeadlineEpochMillis < 0) {
            return 0;
        }
        return Math.max(0, lockDeadlineEpochMillis - nowEpochMillis);
    }

    // ------------------------------------------------------------------
    // 状态迁移（显式命名；非法迁移抛 IllegalStateException，调用方转稳定错误码）
    // ------------------------------------------------------------------

    /** 接受邀请：INVITED -> OPEN；接受后按双方创建时间重置为普通超时 */
    public void accept(long nowEpochMillis) {
        requireStatus(TradeStatus.INVITED, "accept");
        status = TradeStatus.OPEN;
        revision = 1;
        expiresAtEpochMillis = nowEpochMillis + DEFAULT_TIMEOUT_MILLIS;
        touch(nowEpochMillis);
    }

    /** 替换一方报价（OPEN 状态下）。报价变化 -> revision + 1、清空双方确认。 */
    public void replaceOffer(TradeSide side, TradeOffer newOffer, long nowEpochMillis) {
        requireStatus(TradeStatus.OPEN, "replaceOffer");
        if (side == TradeSide.LEFT) {
            leftOffer = Objects.requireNonNull(newOffer);
        } else {
            rightOffer = Objects.requireNonNull(newOffer);
        }
        bumpRevision(nowEpochMillis);
    }

    /** 修改本人收货偏好（OPEN 状态下）：revision + 1、清空确认、作废旧 quote */
    public void setDeliveryPreference(TradeSide side, DeliveryPreference preference, long nowEpochMillis) {
        requireStatus(TradeStatus.OPEN, "setDeliveryPreference");
        if (side == TradeSide.LEFT) {
            leftPreference = Objects.requireNonNull(preference);
        } else {
            rightPreference = Objects.requireNonNull(preference);
        }
        feeQuote = null;
        bumpRevision(nowEpochMillis);
    }

    /** 更新手续费 quote（OPEN 状态下）：revision + 1、清空确认 */
    public void setFeeQuote(TradeFeeQuote quote, long nowEpochMillis) {
        requireStatus(TradeStatus.OPEN, "setFeeQuote");
        if (quote != null && !quote.tradeId().equals(tradeId.value())) {
            throw new IllegalArgumentException("quote bound to different trade");
        }
        feeQuote = quote;
        bumpRevision(nowEpochMillis);
    }

    /**
     * 一方确认（OPEN 状态下）。必须携带 expectedRevision == 当前 revision；
     * 双方都确认同一 revision 后进入 LOCKED，冻结 feeQuote/收货偏好并设置 lockDeadline。
     * 第二位玩家确认时不会在同一调用内提交（由 TradeService 单独调度锁定期）。
     */
    public boolean confirm(TradeSide side, long expectedRevision, long nowEpochMillis) {
        requireStatus(TradeStatus.OPEN, "confirm");
        if (expectedRevision != revision) {
            return false;
        }
        if (side == TradeSide.LEFT) {
            leftConfirmedRevision = revision;
        } else {
            rightConfirmedRevision = revision;
        }
        touch(nowEpochMillis);
        if (leftConfirmedRevision == revision && rightConfirmedRevision == revision) {
            // 双方确认同一 revision：进入 LOCKED，冻结偏好与手续费 quote
            status = TradeStatus.LOCKED;
            lockDeadlineEpochMillis = nowEpochMillis + LOCK_DURATION_MILLIS;
            touch(nowEpochMillis);
            return true;
        }
        return false;
    }

    /**
     * 冻结手续费报价（LOCKED 状态）：双方确认进入锁定期后由 TradeService 调用，
     * 不改变 revision、不清空确认。quote 必须绑定本交易。
     */
    public void freezeFeeQuote(TradeFeeQuote quote, long nowEpochMillis) {
        requireStatus(TradeStatus.LOCKED, "freezeFeeQuote");
        if (quote != null && !quote.tradeId().equals(tradeId.value())) {
            throw new IllegalArgumentException("quote bound to different trade");
        }
        this.feeQuote = quote;
        touch(nowEpochMillis);
    }

    /**
     * 锁定取消 / 掉线 / 能力失效 / quote 过期：LOCKED -> OPEN，
     * revision + 1、清空确认与锁定期限、作废 quote。
     */
    public void unlockToOpen(long nowEpochMillis) {
        requireStatus(TradeStatus.LOCKED, "unlockToOpen");
        status = TradeStatus.OPEN;
        lockDeadlineEpochMillis = -1;
        feeQuote = null;
        bumpRevision(nowEpochMillis);
    }

    /** 倒计时到期且重新校验通过后进入提交：LOCKED -> COMMITTING */
    public void beginCommit(long nowEpochMillis) {
        requireStatus(TradeStatus.LOCKED, "beginCommit");
        if (nowEpochMillis < lockDeadlineEpochMillis) {
            throw new IllegalStateException("lock deadline not reached");
        }
        if (leftConfirmedRevision != revision || rightConfirmedRevision != revision) {
            throw new IllegalStateException("confirmations do not match current revision");
        }
        status = TradeStatus.COMMITTING;
        touch(nowEpochMillis);
    }

    /** 原子所有权切换完成：COMMITTING -> COMMITTED */
    public void markCommitted(long nowEpochMillis) {
        requireStatus(TradeStatus.COMMITTING, "markCommitted");
        status = TradeStatus.COMMITTED;
        touch(nowEpochMillis);
    }

    /** 收件箱交付进行中：COMMITTED -> DELIVERING */
    public void markDelivering(long nowEpochMillis) {
        requireStatus(TradeStatus.COMMITTED, "markDelivering");
        status = TradeStatus.DELIVERING;
        touch(nowEpochMillis);
    }

    /** 两个收件箱批次均已交付：DELIVERING -> COMPLETED（终态） */
    public void markCompleted(long nowEpochMillis) {
        requireStatus(TradeStatus.DELIVERING, "markCompleted");
        status = TradeStatus.COMPLETED;
        touch(nowEpochMillis);
    }

    /** 取消开始：INVITED/OPEN/LOCKED -> CANCELLING */
    public void beginCancel(long nowEpochMillis) {
        if (!status.cancellable()) {
            throw new IllegalStateException("cannot cancel in " + status);
        }
        status = TradeStatus.CANCELLING;
        lockDeadlineEpochMillis = -1;
        feeQuote = null;
        touch(nowEpochMillis);
    }

    /** 取消完成（资产全部进入原所有者收件箱）：CANCELLING -> CANCELLED（终态） */
    public void markCancelled(long nowEpochMillis) {
        requireStatus(TradeStatus.CANCELLING, "markCancelled");
        status = TradeStatus.CANCELLED;
        touch(nowEpochMillis);
    }

    /** 无法安全自动恢复：任意非终态 -> FAILED_REQUIRES_ADMIN（终态） */
    public void failRequiresAdmin(TradeError error, String detail, long nowEpochMillis) {
        if (status.terminal()) {
            throw new IllegalStateException("terminal state cannot be changed");
        }
        status = TradeStatus.FAILED_REQUIRES_ADMIN;
        failureError = error == null ? TradeError.REQUIRES_ADMIN : error;
        failureDetail = detail == null ? "" : detail;
        touch(nowEpochMillis);
    }

    /** 延长超时（恢复器 / 活跃操作时调用） */
    public void extendExpiry(long nowEpochMillis) {
        if (!status.terminal()) {
            expiresAtEpochMillis = nowEpochMillis + DEFAULT_TIMEOUT_MILLIS;
            touch(nowEpochMillis);
        }
    }

    /** 重建锁定期限（重启恢复时，LOCKED 状态） */
    public void restoreLockDeadline(long lockDeadlineEpochMillis) {
        requireStatus(TradeStatus.LOCKED, "restoreLockDeadline");
        this.lockDeadlineEpochMillis = lockDeadlineEpochMillis;
    }

    /** 恢复器：强制设为 COMMITTING（崩溃点在提交意图写入后），必须由恢复流程调用 */
    public void restoreToCommitting(long nowEpochMillis) {
        if (status != TradeStatus.COMMITTING && status != TradeStatus.COMMITTED) {
            throw new IllegalStateException("cannot restore to committing from " + status);
        }
        status = TradeStatus.COMMITTING;
        touch(nowEpochMillis);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void bumpRevision(long nowEpochMillis) {
        revision = Math.addExact(revision, 1L);
        leftConfirmedRevision = -1;
        rightConfirmedRevision = -1;
        touch(nowEpochMillis);
    }

    private void touch(long nowEpochMillis) {
        updatedAtEpochMillis = nowEpochMillis;
        // 终态不再延长超时
        if (!status.terminal() && expiresAtEpochMillis <= nowEpochMillis) {
            expiresAtEpochMillis = nowEpochMillis + DEFAULT_TIMEOUT_MILLIS;
        }
    }

    private void requireStatus(TradeStatus expected, String op) {
        if (status != expected) {
            throw new IllegalStateException("cannot " + op + " in state " + status + " (expected " + expected + ")");
        }
    }

    /** 构造器（供 NBT 解码器 / 测试重建完整状态） */
    public static Builder builder(TradeId tradeId, UUID leftPlayerId, UUID rightPlayerId) {
        return new Builder(tradeId, leftPlayerId, rightPlayerId);
    }

    public static final class Builder {
        private final TradeId tradeId;
        private final UUID leftPlayerId;
        private final UUID rightPlayerId;
        private TradeStatus status = TradeStatus.INVITED;
        private long revision = 0;
        private TradeOffer leftOffer;
        private TradeOffer rightOffer;
        private long leftConfirmedRevision = -1;
        private long rightConfirmedRevision = -1;
        private DeliveryPreference leftPreference;
        private DeliveryPreference rightPreference;
        private TradeFeeQuote feeQuote;
        private long createdAtEpochMillis;
        private long updatedAtEpochMillis;
        private long expiresAtEpochMillis;
        private long lockDeadlineEpochMillis = -1;
        private TradeError failureError = TradeError.NONE;
        private String failureDetail = "";

        private Builder(TradeId tradeId, UUID leftPlayerId, UUID rightPlayerId) {
            this.tradeId = Objects.requireNonNull(tradeId);
            this.leftPlayerId = Objects.requireNonNull(leftPlayerId);
            this.rightPlayerId = Objects.requireNonNull(rightPlayerId);
        }

        public Builder status(TradeStatus s) {
            this.status = Objects.requireNonNull(s);
            return this;
        }

        public Builder revision(long r) {
            this.revision = r;
            return this;
        }

        public Builder leftOffer(TradeOffer o) {
            this.leftOffer = o;
            return this;
        }

        public Builder rightOffer(TradeOffer o) {
            this.rightOffer = o;
            return this;
        }

        public Builder leftConfirmedRevision(long r) {
            this.leftConfirmedRevision = r;
            return this;
        }

        public Builder rightConfirmedRevision(long r) {
            this.rightConfirmedRevision = r;
            return this;
        }

        public Builder leftPreference(DeliveryPreference p) {
            this.leftPreference = p;
            return this;
        }

        public Builder rightPreference(DeliveryPreference p) {
            this.rightPreference = p;
            return this;
        }

        public Builder feeQuote(TradeFeeQuote q) {
            this.feeQuote = q;
            return this;
        }

        public Builder createdAt(long t) {
            this.createdAtEpochMillis = t;
            return this;
        }

        public Builder updatedAt(long t) {
            this.updatedAtEpochMillis = t;
            return this;
        }

        public Builder expiresAt(long t) {
            this.expiresAtEpochMillis = t;
            return this;
        }

        public Builder lockDeadline(long t) {
            this.lockDeadlineEpochMillis = t;
            return this;
        }

        public Builder failureError(TradeError e) {
            this.failureError = e == null ? TradeError.NONE : e;
            return this;
        }

        public Builder failureDetail(String d) {
            this.failureDetail = d == null ? "" : d;
            return this;
        }

        public PlayerTrade build() {
            return new PlayerTrade(this);
        }
    }
}
