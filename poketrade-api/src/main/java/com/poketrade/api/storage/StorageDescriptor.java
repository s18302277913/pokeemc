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
 */
public record StorageDescriptor(
        StorageId storageId,
        String displayName,
        int distance,
        boolean claimed,
        UUID ownerId,
        Set<StorageCapability> capabilities,
        int slotCount,
        int usedSlots,
        long revision,
        boolean scanComplete) {

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
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    @Override
    public Set<StorageCapability> capabilities() {
        return capabilities;
    }
}
