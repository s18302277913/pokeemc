package com.pokeemc.trade.asset;

import com.pokeemc.trade.model.TradeError;

/**
 * 通用操作结果：成功携带 value，失败携带稳定错误码（不向客户端透出异常文本）。
 * 供 {@link ItemEscrowGateway} / {@link PkmEscrowGateway} 等托管 gateway 共用。
 */
public record Outcome<T>(TradeError error, T value) {

    public static <T> Outcome<T> ok(T value) {
        return new Outcome<>(TradeError.NONE, value);
    }

    public static <T> Outcome<T> fail(TradeError error) {
        return new Outcome<>(error, null);
    }

    public boolean ok() {
        return error == TradeError.NONE;
    }
}
