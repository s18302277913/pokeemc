package com.poketrade.api.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单个仓储的槽位内容快照。
 *
 * <p>槽位以不可变映射保存，键为槽位索引；对调用方做防御性复制。
 * {@code revision} 为该快照对应的仓储版本，用于并发校验。</p>
 */
public record StorageSnapshot(StorageId storageId, long revision, Map<Integer, StorageItemSlot> slots) {

    public StorageSnapshot {
        Objects.requireNonNull(storageId, "storageId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        Objects.requireNonNull(slots, "slots");
        Map<Integer, StorageItemSlot> copy = new LinkedHashMap<>(slots.size());
        slots.forEach((index, slot) -> {
            if (index == null || index < 0) {
                throw new IllegalArgumentException("slot index must be non-negative");
            }
            Objects.requireNonNull(slot, "slot " + index);
            copy.put(index, slot);
        });
        slots = Collections.unmodifiableMap(copy);
    }

    @Override
    public Map<Integer, StorageItemSlot> slots() {
        return slots;
    }
}
