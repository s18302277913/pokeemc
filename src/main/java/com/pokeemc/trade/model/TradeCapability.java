package com.pokeemc.trade.model;

/**
 * 交易能力状态（计划 2.5）。目录状态是短期提示，邀请和结算时必须重新校验。
 * S2C 只发送枚举与可选稳定原因码，不发送异常文本。
 */
public enum TradeCapability {

    /** 可发起/接受交易 */
    AVAILABLE,

    /** 目标就是自己 */
    SELF,

    /** 已有活动交易 */
    BUSY,

    /** 已收到该玩家的邀请，等待接受 */
    INVITE_PENDING,

    /** 不在线 */
    OFFLINE,

    /** 玩家在设置中关闭了交易入口 */
    DISABLED_BY_PLAYER,

    /** 服务器关闭了交易功能 */
    DISABLED_BY_SERVER,

    /** PKM 后端不可用（钱包不支持幂等事务），PKM 资产类型不可用 */
    PKM_UNSUPPORTED,

    /** 有未完成的恢复，需先处理 */
    RECOVERY_REQUIRED,

    /** 请求过于频繁 */
    RATE_LIMITED;

    /** 该能力状态是否允许作为邀请目标（注意：PKM_UNSUPPORTED 不影响物品/宝可梦交易） */
    public boolean invitable() {
        return this == AVAILABLE || this == PKM_UNSUPPORTED;
    }
}
