package com.pokeemc.trade.service;

import com.pokeemc.trade.model.TradeOffer;

import java.time.Instant;
import java.util.UUID;

/**
 * 手续费报价上下文（计划 5.4）：quote 必须绑定 tradeId + revision + 策略版本。
 * 服务端配置热重载只影响新 quote，已进入 LOCKED/COMMITTING 的交易使用冻结版本。
 */
public record TradeFeeContext(
        UUID tradeId,
        long revision,
        TradeOffer leftOffer,
        TradeOffer rightOffer,
        UUID initiatorId,
        Instant now
) {
}
