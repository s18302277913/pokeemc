package com.pokeemc.economy;

import com.pixelmonmod.pixelmon.api.economy.BankAccount;
import com.pixelmonmod.pixelmon.api.economy.BankAccountProxy;
import com.pokeemc.trade.asset.WalletAccount;
import com.pokeemc.trade.asset.WalletPort;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pixelmon 钱包（宝可元 / PKM）桥接工具。
 * <p>
 * 本模组的货币与 Pixelmon 的金币系统共用同一数值：转化桌余额即玩家
 * Pixelmon 钱包余额。所有加减都直接作用在 {@link BankAccount} 上。
 */
public final class PixelmonWallet {

    private PixelmonWallet() {}

    /** 获取玩家的 Pixelmon 钱包；非服务端或无实现时返回 null */
    public static BankAccount getAccount(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            return BankAccountProxy.getBankAccountNow(serverPlayer);
        }
        return null;
    }

    /** 读取玩家钱包余额（截断为 long，正常情况下宝可元不会超过 long 上限） */
    public static long getBalance(Player player) {
        BankAccount account = getAccount(player);
        return account == null ? 0 : account.getBalance().longValue();
    }

    /** 向玩家钱包入账，返回是否成功 */
    public static boolean add(Player player, long amount) {
        if (amount <= 0) {
            return true;
        }
        BankAccount account = getAccount(player);
        return account != null && account.add(BigDecimal.valueOf(amount));
    }

    /** 从玩家钱包扣款（余额不足则失败），返回是否成功 */
    public static boolean take(Player player, long amount) {
        if (amount <= 0) {
            return true;
        }
        BankAccount account = getAccount(player);
        return account != null
                && account.hasBalance(BigDecimal.valueOf(amount))
                && account.take(BigDecimal.valueOf(amount));
    }

    /**
     * 以 {@link WalletPort} 形式暴露 Pixelmon 钱包（Task 4）。
     * 余额不是整数或超出 long 表示范围时返回 empty，绝不截断。
     * 默认 Pixelmon BankAccount 不支持 operation 幂等，故 {@link WalletAccount#supportsIdempotency()}
     * 返回 false —— PKM 玩家交易默认禁用，直到接入支持幂等键的经济后端。
     */
    public static WalletPort port() {
        return playerId -> {
            if (playerId == null) {
                return Optional.empty();
            }
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return Optional.empty();
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return Optional.empty();
            }
            BankAccount account = BankAccountProxy.getBankAccountNow(player);
            if (account == null) {
                return Optional.empty();
            }
            try {
                account.getBalance().longValueExact();
            } catch (ArithmeticException e) {
                return Optional.empty();
            }
            return Optional.of(new PixelmonWalletAccount(account));
        };
    }

    /** Pixelmon BankAccount 的 {@link WalletAccount} 适配器 */
    private static final class PixelmonWalletAccount implements WalletAccount {

        private final BankAccount account;

        private PixelmonWalletAccount(BankAccount account) {
            this.account = Objects.requireNonNull(account, "account");
        }

        @Override
        public long balance() {
            return account.getBalance().longValueExact();
        }

        @Override
        public boolean debit(long amount) {
            if (amount <= 0) {
                return true;
            }
            return account.hasBalance(BigDecimal.valueOf(amount))
                    && account.take(BigDecimal.valueOf(amount));
        }

        @Override
        public boolean credit(long amount) {
            if (amount <= 0) {
                return true;
            }
            return account.add(BigDecimal.valueOf(amount));
        }

        @Override
        public boolean supportsIdempotency() {
            return false;
        }
    }
}
