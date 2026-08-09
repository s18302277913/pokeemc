package com.pokeemc.trade.asset;

/**
 * 单个玩家钱包账户（Task 4）：{@link WalletPort#find} 返回的账户句柄。
 * 金额一律为 long（转换由端口在 find 时完成，非法余额不会到达本层）。
 */
public interface WalletAccount {

    /** 当前余额（long，端口已保证可精确表示） */
    long balance();

    /**
     * 借记（扣款）。仅执行扣减，不重复做余额校验（由 gateway 先校验）。
     *
     * @return 是否成功
     */
    boolean debit(long amount);

    /**
     * 贷记（入账）。
     *
     * @return 是否成功
     */
    boolean credit(long amount);

    /**
     * 后端是否支持以 operation id 为键的幂等资金操作。
     * 默认 Pixelmon BankAccount 不支持，返回 false —— PKM 玩家交易默认禁用。
     */
    boolean supportsIdempotency();
}
