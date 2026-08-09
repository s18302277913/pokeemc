package com.pokeemc.trade.asset;

/**
 * 交付结果（托管 gateway 共用）：成功放入目标存储的数量与剩余数量
 * （剩余需由调用方转入收件箱）。
 */
public record DeliveryResult(int placed, int remaining) {

    public boolean allDelivered() {
        return remaining == 0;
    }

    public DeliveryResult {
        if (placed < 0 || remaining < 0) {
            throw new IllegalArgumentException("placed/remaining cannot be negative");
        }
    }
}
