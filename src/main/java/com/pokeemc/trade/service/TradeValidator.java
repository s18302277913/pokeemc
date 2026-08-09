package com.pokeemc.trade.service;

import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * 交易操作前置校验（Task 6）：纯函数、无副作用，统一从 TradeService 抽出。
 * 所有返回 {@link TradeError#NONE} 表示校验通过。
 */
public final class TradeValidator {

    private TradeValidator() {
    }

    /** 邀请前置校验：目标在线、非本人、双方均无活动交易 */
    public static TradeError validateInvite(UUID initiatorId, UUID targetId,
                                            TradeRepository repo, PlayerStorageResolver resolver) {
        Objects.requireNonNull(initiatorId, "initiatorId");
        Objects.requireNonNull(targetId, "targetId");
        if (initiatorId.equals(targetId)) {
            return TradeError.SELF_TRADE;
        }
        if (!resolver.isOnline(targetId)) {
            return TradeError.TARGET_OFFLINE;
        }
        if (repo.findTradeOf(initiatorId).isPresent()) {
            return TradeError.ALREADY_IN_TRADE;
        }
        if (repo.findTradeOf(targetId).isPresent()) {
            return TradeError.ALREADY_IN_TRADE;
        }
        return TradeError.NONE;
    }

    /** 玩家是否为交易参与者 */
    public static TradeError validateParticipant(PlayerTrade trade, UUID playerId) {
        if (!trade.isParticipant(playerId)) {
            return TradeError.NOT_PARTICIPANT;
        }
        return TradeError.NONE;
    }

    /** revision 必须与当前一致（客户端需刷新） */
    public static TradeError validateRevision(PlayerTrade trade, long expectedRevision) {
        if (expectedRevision != trade.revision()) {
            return TradeError.STALE_REVISION;
        }
        return TradeError.NONE;
    }

    /** 交易必须处于 OPEN 状态 */
    public static TradeError validateOpen(PlayerTrade trade) {
        if (trade.status() != TradeStatus.OPEN) {
            return TradeError.INVALID_STATE;
        }
        return TradeError.NONE;
    }

    /** 交易未过期 */
    public static TradeError validateNotExpired(PlayerTrade trade, long nowEpochMillis) {
        if (trade.expired(nowEpochMillis)) {
            return TradeError.TRADE_EXPIRED;
        }
        return TradeError.NONE;
    }
}
