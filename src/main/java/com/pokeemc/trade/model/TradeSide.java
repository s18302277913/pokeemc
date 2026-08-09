package com.pokeemc.trade.model;

import java.util.UUID;

/**
 * 交易双方角色。发起邀请方为 {@link #LEFT}，受邀方为 {@link #RIGHT}。
 * <p>
 * 锁顺序固定为按双方 UUID 字典序，与角色无关；本枚举只用于 UI 左右栏与
 * 报价归属，不参与并发顺序决策。
 */
public enum TradeSide {

    LEFT("left"),
    RIGHT("right");

    private final String networkName;

    TradeSide(String networkName) {
        this.networkName = networkName;
    }

    public String networkName() {
        return networkName;
    }

    public TradeSide opposite() {
        return this == LEFT ? RIGHT : LEFT;
    }

    /** 解析玩家属于哪一方；非参与者返回 null。 */
    public static TradeSide of(UUID playerId, PlayerTrade trade) {
        if (trade.leftPlayerId().equals(playerId)) {
            return LEFT;
        }
        if (trade.rightPlayerId().equals(playerId)) {
            return RIGHT;
        }
        return null;
    }
}
