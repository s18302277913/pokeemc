package com.pokeemc.storage.adapter;

import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/**
 * 基于 {@link SlotStore} 的 {@link com.poketrade.api.storage.StorageHandle} 实现。
 *
 * <p>simulate/commit 全部为纯逻辑（槽位索引、堆叠上限、过滤谓词、指纹），不依赖
 * Minecraft 类型，可在 JVM 单测中直接验证。游戏侧 {@link MinecraftSlotStore} 只负责
 * 把 {@code Container} 桥接成 {@link SlotStore}。</p>
 */
public final class StorageHandleImpl implements StorageHandleExt {

    private final StorageId storageId;      // 背包/临时端点可为 null（不参与快照）
    private final SlotStore slots;
    private final IntPredicate insertable;
    private final IntPredicate extractable;
    private final LongSupplier revisionSource;

    public StorageHandleImpl(StorageId storageId, SlotStore slots,
                             IntPredicate insertable, IntPredicate extractable,
                             LongSupplier revisionSource) {
        this.storageId = storageId;
        this.slots = Objects.requireNonNull(slots, "slots");
        this.insertable = Objects.requireNonNull(insertable, "insertable");
        this.extractable = Objects.requireNonNull(extractable, "extractable");
        this.revisionSource = Objects.requireNonNull(revisionSource, "revisionSource");
    }

    /** 全槽位可读写、revision 恒为 0 的便捷构造（用于背包等临时端点）。 */
    public static StorageHandleImpl of(StorageId storageId, SlotStore slots) {
        return new StorageHandleImpl(storageId, slots, s -> true, s -> true, () -> 0L);
    }

    @Override
    public StorageSnapshot snapshot() {
        if (storageId == null) {
            throw new IllegalStateException("temporary handle has no storage id");
        }
        Map<Integer, StorageItemSlot> map = new LinkedHashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            String id = slots.itemId(i);
            if (id == null) {
                continue;
            }
            map.put(i, new StorageItemSlot(i, id, slots.count(i), slots.fingerprint(i)));
        }
        return new StorageSnapshot(storageId, revisionSource.getAsLong(), map);
    }

    @Override
    public boolean simulateInsert(int slotIndex, String itemId, int count) {
        if (itemId == null || count <= 0) {
            return false;
        }
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return false;
        }
        if (!insertable.test(slotIndex)) {
            return false;
        }
        String current = slots.itemId(slotIndex);
        if (current == null) {
            return count <= slots.maxStack(slotIndex, itemId);
        }
        if (!current.equals(itemId)) {
            return false;
        }
        return (long) slots.count(slotIndex) + count <= slots.maxStack(slotIndex, itemId);
    }

    @Override
    public boolean simulateExtract(int slotIndex, String itemId, int count) {
        if (itemId == null || count <= 0) {
            return false;
        }
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return false;
        }
        if (!extractable.test(slotIndex)) {
            return false;
        }
        String current = slots.itemId(slotIndex);
        return current != null && current.equals(itemId) && slots.count(slotIndex) >= count;
    }

    @Override
    public void commitInsert(int slotIndex, String itemId, int count) {
        if (!simulateInsert(slotIndex, itemId, count)) {
            throw new IllegalArgumentException(
                    "cannot insert " + count + "x" + itemId + " into slot " + slotIndex);
        }
        String existing = slots.itemId(slotIndex);
        if (existing == null || !existing.equals(itemId)) {
            slots.set(slotIndex, itemId, count);
        } else {
            slots.set(slotIndex, itemId, slots.count(slotIndex) + count);
        }
        slots.setChanged();
    }

    @Override
    public void commitExtract(int slotIndex, String itemId, int count) {
        if (!simulateExtract(slotIndex, itemId, count)) {
            throw new IllegalArgumentException(
                    "cannot extract " + count + "x" + itemId + " from slot " + slotIndex);
        }
        int left = slots.count(slotIndex) - count;
        slots.set(slotIndex, left <= 0 ? null : itemId, left);
        slots.setChanged();
    }

    @Override
    public int slotCount() {
        return slots.size();
    }

    @Override
    public String itemId(int slot) {
        return slots.itemId(slot);
    }

    @Override
    public int count(int slot) {
        return slots.count(slot);
    }

    @Override
    public long fingerprint(int slot) {
        return slots.fingerprint(slot);
    }

    @Override
    public void close() {
        // 无外部资源；容器变更已由 commit 直接写入。
    }
}
