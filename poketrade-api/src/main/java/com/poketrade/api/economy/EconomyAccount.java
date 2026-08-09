package com.poketrade.api.economy;

/**
 * 可操作的资金账户（金额单位为 long，语义与主模组 WalletAccount 一致）。
 */
public interface EconomyAccount {

    /** 当前余额（long 精确表示）。 */
    long balance();

    /** 扣款；余额不足返回 false，不做部分扣款。 */
    boolean debit(long amount);

    /** 入账；返回是否成功。 */
    boolean credit(long amount);

    /** 是否支持幂等操作（默认为 false，同 Pixelmon 行为）。 */
    boolean supportsIdempotency();
}
