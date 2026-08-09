package com.pokeemc.trade.model;

/**
 * 玩家交易状态机状态。全部迁移只能在服务端主线程执行（见计划 4.3）。
 *
 * <pre>
 * INVITED -> OPEN（接受邀请）
 * OPEN -> LOCKED（双方确认同一 revision）
 * OPEN/LOCKED -> CANCELLING -> CANCELLED（任一方取消）
 * LOCKED -> OPEN（锁定取消 / 掉线 / 能力失效 / quote 过期）
 * LOCKED -> COMMITTING（倒计时到期后重新校验通过）
 * COMMITTING -> COMMITTED（原子所有权切换）
 * COMMITTED -> DELIVERING -> COMPLETED（收件箱交付）
 * 任意非终态 -> FAILED_REQUIRES_ADMIN（无法安全自动恢复）
 * </pre>
 *
 * 终态：{@link #COMPLETED}、{@link #CANCELLED}、{@link #FAILED_REQUIRES_ADMIN}。
 */
public enum TradeStatus {

    INVITED,
    OPEN,
    LOCKED,
    COMMITTING,
    COMMITTED,
    DELIVERING,
    COMPLETED,
    CANCELLING,
    CANCELLED,
    FAILED_REQUIRES_ADMIN;

    /** 该状态下可取消（对应计划不变量 7：取消只作用于 OPEN/LOCKED，COMMITTING 由恢复器推进） */
    public boolean cancellable() {
        return this == INVITED || this == OPEN || this == LOCKED;
    }

    /** 该状态已进入提交流程，任何取消或回滚都不允许 */
    public boolean committingOrLater() {
        return ordinal() >= COMMITTING.ordinal();
    }

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED_REQUIRES_ADMIN;
    }
}
