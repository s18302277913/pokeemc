package com.poketrade.api.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 仓储事务结果。
 *
 * <p>拒绝原因使用固定代码：{@value #NOT_FOUND}、{@value #CHUNK_UNLOADED}、
 * {@value #ADAPTER_UNAVAILABLE}、{@value #NOT_CLAIMED}、{@value #PERMISSION_DENIED}、
 * {@value #REVISION_CONFLICT}。成功时为 {@value #SUCCESS}。</p>
 */
public record StorageTransactionResult(
        boolean success,
        String code,
        String message,
        List<StorageItemSlot> changedSlots,
        Map<StorageId, Long> newRevisions,
        Map<StorageId, StorageSnapshot> updatedSnapshots,
        UUID auditEntryId) {

    public static final String SUCCESS = "success";
    public static final String NOT_FOUND = "not_found";
    public static final String CHUNK_UNLOADED = "chunk_unloaded";
    public static final String ADAPTER_UNAVAILABLE = "adapter_unavailable";
    public static final String NOT_CLAIMED = "not_claimed";
    public static final String PERMISSION_DENIED = "permission_denied";
    public static final String REVISION_CONFLICT = "revision_conflict";

    public StorageTransactionResult {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(changedSlots, "changedSlots");
        changedSlots = List.copyOf(changedSlots);
        Objects.requireNonNull(newRevisions, "newRevisions");
        newRevisions = Collections.unmodifiableMap(new LinkedHashMap<>(newRevisions));
        Objects.requireNonNull(updatedSnapshots, "updatedSnapshots");
        updatedSnapshots = Collections.unmodifiableMap(new LinkedHashMap<>(updatedSnapshots));
    }

    public static StorageTransactionResult success(String message,
                                                   List<StorageItemSlot> changedSlots,
                                                   Map<StorageId, Long> newRevisions,
                                                   Map<StorageId, StorageSnapshot> updatedSnapshots,
                                                   UUID auditEntryId) {
        return new StorageTransactionResult(true, SUCCESS, message, changedSlots,
                newRevisions, updatedSnapshots, auditEntryId);
    }

    public static StorageTransactionResult failure(String code, String message) {
        return new StorageTransactionResult(false, code, message,
                List.of(), Map.of(), Map.of(), null);
    }

    @Override
    public List<StorageItemSlot> changedSlots() {
        return changedSlots;
    }

    @Override
    public Map<StorageId, Long> newRevisions() {
        return newRevisions;
    }

    @Override
    public Map<StorageId, StorageSnapshot> updatedSnapshots() {
        return updatedSnapshots;
    }
}
