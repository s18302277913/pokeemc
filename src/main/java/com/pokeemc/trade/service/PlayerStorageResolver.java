package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.PlayerInventoryStore;
import com.pokeemc.trade.asset.PokemonStoragePort;
import com.pokeemc.trade.asset.WalletPort;

import java.util.Collection;
import java.util.UUID;

/**
 * 玩家存储解析器（Task 6）：把玩家 UUID 解析为背包/钱包/宝可梦存储抽象，
 * 让 TradeService 脱离 ServerPlayer 与 Pixelmon 类，可在 JVM 单测驱动。
 * 生产实现（Task 11 接线）使用 Minecraft/Pixelmon 桥接。
 */
public interface PlayerStorageResolver {

    /** 玩家是否在线（离线不能发起/接受交易） */
    boolean isOnline(UUID playerId);

    /** 玩家当前公开名称（目录展示用；离玩家返回稳定占位） */
    String displayName(UUID playerId);

    /** 当前在线玩家 UUID 集合（目录查询用，不扫描资产） */
    Collection<UUID> onlinePlayers();

    /** 玩家背包存储 */
    PlayerInventoryStore inventory(UUID playerId);

    /** 玩家末影箱存储（个人容器）；不支持时返回 null（交付降级到收件箱）。 */
    default PlayerInventoryStore enderChest(UUID playerId) {
        return null;
    }

    /** 全服钱包端口（PKM 借记/贷记） */
    WalletPort wallet();

    /** 玩家宝可梦存储（Party/PC） */
    PokemonStoragePort pokemonStorage(UUID playerId);
}
