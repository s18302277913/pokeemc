package com.poketrade.api.storage;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 仓储浏览查询返回的单条描述。
 *
 * <p>服务端在返回前已按调用者的 VIEW 或可执行动作过滤；未获授权的仓储不会出现。
 * 集合字段对调用方做防御性复制。</p>
 *
 * <p>[CHANGED] 会话 #21-E：追加 {@code createdAtEpochMillis}（仓储放置时间戳），
 * 供客户端「放置时间」排序与同类型标记（①-⑳）基准使用。</p>
 */
public record StorageDescriptor(
        StorageId storageId,
        String displayName,
        int distance,
        boolean claimed,
        UUID ownerId,
        String ownerName,
        Set<StorageCapability> capabilities,
        int slotCount,
        int usedSlots,
        long revision,
        boolean scanComplete,
        long createdAtEpochMillis) {

    public StorageDescriptor {
        Objects.requireNonNull(storageId, "storageId");
        Objects.requireNonNull(displayName, "displayName");
        if (distance < 0) {
            throw new IllegalArgumentException("distance must be non-negative");
        }
        if (slotCount < 0 || usedSlots < 0 || usedSlots > slotCount) {
            throw new IllegalArgumentException(
                    "slotCount/usedSlots out of range: " + usedSlots + "/" + slotCount);
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (createdAtEpochMillis < 0) {
            throw new IllegalArgumentException("createdAtEpochMillis must be non-negative");
        }
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        if (ownerName != null) {
            ownerName = ownerName.trim();
        }
    }

    /** 兼容构造：未携带放置时间戳时以 0 表示（旧调用方/纯 JVM 测试）。 */
    public StorageDescriptor(
            StorageId storageId,
            String displayName,
            int distance,
            boolean claimed,
            UUID ownerId,
            String ownerName,
            Set<StorageCapability> capabilities,
            int slotCount,
            int usedSlots,
            long revision,
            boolean scanComplete) {
        this(storageId, displayName, distance, claimed, ownerId, ownerName, capabilities,
                slotCount, usedSlots, revision, scanComplete, 0L);
    }

    @Override
    public Set<StorageCapability> capabilities() {
        return capabilities;
    }
}
