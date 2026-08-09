package com.pokeemc.trade.service;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.model.TradeStatus;

import java.util.UUID;

/**
 * 交易快照（Task 6）：按请求者视角构造，{@code self} 为请求者本人。
 * 网络层据此生成 TradeSnapshotPacket；报价携带完整资产（含 NBT），
 * 隐私过滤（对手可见范围）由网络层负责，服务层不做裁剪。
 */
public record TradeSnapshot(
        TradeId tradeId,
        TradeStatus status,
        long revision,
        UUID selfPlayerId,
        UUID otherPlayerId,
        TradeOffer selfOffer,
        TradeOffer otherOffer,
        boolean selfConfirmed,
        boolean otherConfirmed,
        long expiresAtEpochMillis,
        long lockDeadlineEpochMillis,
        DeliveryPreference selfPreference,
        TradeFeeQuote feeQuote
) {
    public TradeSnapshot {
        if (tradeId == null || selfPlayerId == null || otherPlayerId == null) {
            throw new IllegalArgumentException("tradeId/selfPlayerId/otherPlayerId cannot be null");
        }
        if (selfOffer == null || otherOffer == null) {
            throw new IllegalArgumentException("offers cannot be null");
        }
        if (selfPreference == null) {
            throw new IllegalArgumentException("selfPreference cannot be null");
        }
    }
}
