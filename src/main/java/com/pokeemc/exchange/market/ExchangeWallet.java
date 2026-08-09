package com.pokeemc.exchange.market;

import net.minecraft.server.level.ServerPlayer;

/**
 * 交易所钱包抽象：买入扣款、出售入账。
 *
 * <p>生产实现绑定 {@code PixelmonWallet}（宝可元）；GameTest 环境没有 Pixelmon
 * 运行时（BankAccountProxy 无实现），测试注入内存钱包。</p>
 */
public interface ExchangeWallet {

    long getBalance(ServerPlayer player);

    /** 入账，返回是否成功。 */
    boolean add(ServerPlayer player, long amount);

    /** 扣款（余额不足则失败），返回是否成功。 */
    boolean take(ServerPlayer player, long amount);
}
