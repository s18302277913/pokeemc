package com.poketrade.api;

public enum TradeResult {
    SUCCESS("success"),
    UNKNOWN_ITEM("unknown_item"),
    INVALID_QUANTITY("invalid_quantity"),
    INSUFFICIENT_FUNDS("insufficient_funds"),
    OUTPUT_BLOCKED("output_blocked"),
    INTERNAL_ERROR("internal_error");

    private final String code;

    TradeResult(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
