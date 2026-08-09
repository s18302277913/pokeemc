package com.pokeemc.trade.persistence;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.TradeAsset;

import java.util.Objects;
import java.util.UUID;

/**
 * 待交付收件箱条目（计划 3.1/4.2）：提交点把托管资产原子移入接收者收件箱，
 * 附带接收者在该 revision 冻结的收货偏好。交付是提交后的幂等副作用。
 *
 * <p>entryId 稳定唯一；state 从 {@link InboxState#PENDING} 经交付尝试变为
 * {@link InboxState#DELIVERED} 或 {@link InboxState#FAILED}（保留在收件箱可重试）。</p>
 */
public record InboxEntry(
        UUID entryId,
        UUID tradeId,
        UUID recipientId,
        TradeAsset asset,
        DeliveryPreference preference,
        long revision,
        long createdAtEpochMillis,
        InboxState state
) {

    public InboxEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(preference, "preference");
        Objects.requireNonNull(state, "state");
    }

    public static InboxEntry pending(
            UUID tradeId, UUID recipientId, TradeAsset asset,
            DeliveryPreference preference, long revision, long nowEpochMillis) {
        return new InboxEntry(
                UUID.randomUUID(), tradeId, recipientId, asset,
                preference, revision, nowEpochMillis, InboxState.PENDING);
    }

    public InboxEntry withState(InboxState newState) {
        return new InboxEntry(
                entryId, tradeId, recipientId, asset,
                preference, revision, createdAtEpochMillis, newState);
    }

    /** 收件箱条目交付状态 */
    public enum InboxState {
        /** 待交付（默认；持久化后原样恢复） */
        PENDING,
        /** 已交付到目标位置或背包 */
        DELIVERED,
        /** 交付失败（目标无容量），保留在收件箱等待 claim 重试 */
        FAILED
    }
}
