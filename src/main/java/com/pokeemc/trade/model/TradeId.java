package com.pokeemc.trade.model;

import java.util.UUID;

/**
 * 交易稳定标识。所有客户端请求、命令、日志与幂等键都使用该 id，
 * 不允许用玩家名或可能变化的临时字段定位交易。
 */
public record TradeId(UUID value) {

    public TradeId {
        if (value == null) {
            throw new IllegalArgumentException("tradeId cannot be null");
        }
    }

    public static TradeId random() {
        return new TradeId(UUID.randomUUID());
    }

    /** 稳定幂等键前缀：同一交易的所有资产/费用操作共用该前缀 */
    public String keyPrefix() {
        return value.toString();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
