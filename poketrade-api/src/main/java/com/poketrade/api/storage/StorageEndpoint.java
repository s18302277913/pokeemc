package com.poketrade.api.storage;

import java.util.Objects;

/**
 * 事务端点：玩家背包槽位或仓储槽位。
 */
public record StorageEndpoint(Kind kind, StorageId storageId, int slotIndex) {

    public enum Kind {
        /** 玩家背包槽位（service 层按槽位解析到物品） */
        INVENTORY,
        /** 仓储槽位 */
        STORAGE
    }

    public StorageEndpoint {
        Objects.requireNonNull(kind, "kind");
        if (slotIndex < 0) {
            throw new IllegalArgumentException("slotIndex must be non-negative");
        }
        if (kind == Kind.STORAGE && storageId == null) {
            throw new IllegalArgumentException("storage endpoint requires a storage id");
        }
        if (kind == Kind.INVENTORY && storageId != null) {
            throw new IllegalArgumentException("inventory endpoint must not carry a storage id");
        }
    }

    public static StorageEndpoint inventory(int slotIndex) {
        return new StorageEndpoint(Kind.INVENTORY, null, slotIndex);
    }

    public static StorageEndpoint storage(StorageId storageId, int slotIndex) {
        return new StorageEndpoint(Kind.STORAGE, storageId, slotIndex);
    }
}
