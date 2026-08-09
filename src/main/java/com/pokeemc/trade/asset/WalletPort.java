package com.pokeemc.trade.asset;

import java.util.Optional;
import java.util.UUID;

/**
 * 钱包端口抽象（Task 4，计划 3.3）：把 PKM 资金操作从 Pixelmon API 解耦，
 * 让 {@link PkmEscrowGateway} 可在 JVM 单测驱动。
 * <p>
 * 余额转换规则：{@code find} 时若余额不是整数或超出 long 表示范围，
 * 一律视为钱包不可用返回 empty，绝不截断（计划 Task 4 步骤 1）。
 */
public interface WalletPort {

    /**
     * 查找玩家钱包；钱包不存在或余额不可精确表示为 long 时返回 empty。
     *
     * @param playerId 玩家 UUID
     */
    Optional<WalletAccount> find(UUID playerId);
}
