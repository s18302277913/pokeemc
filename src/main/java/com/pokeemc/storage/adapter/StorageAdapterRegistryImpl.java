package com.pokeemc.storage.adapter;

import com.pokeemc.storage.StorageKey;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterRegistry;
import com.mojang.logging.LogUtils;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/**
 * {@link StorageAdapterRegistry} 的根模组实现。
 *
 * <p>注册顺序保持稳定（{@code LinkedHashMap}）；重复 {@code typeId} 抛出
 * {@link IllegalArgumentException}，防止适配器类型歧义。</p>
 */
public final class StorageAdapterRegistryImpl implements StorageAdapterRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final LinkedHashMap<String, StorageAdapter> adapters = new LinkedHashMap<>();

    @Override
    public synchronized void register(StorageAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        String typeId = adapter.typeId();
        if (typeId == null || typeId.isBlank()) {
            throw new IllegalArgumentException("adapter typeId must be non-blank");
        }
        if (adapters.containsKey(typeId)) {
            throw new IllegalArgumentException("adapter type already registered: " + typeId);
        }
        adapters.put(typeId, adapter);
        LOGGER.info("Registered storage adapter '{}' with capabilities {}", typeId, adapter.capabilities());
    }

    @Override
    public Optional<StorageAdapter> byTypeId(String typeId) {
        return Optional.ofNullable(adapters.get(typeId));
    }

    @Override
    public Set<String> typeIds() {
        return Set.copyOf(adapters.keySet());
    }

    /** 该类型 ID 是否已注册（供 SavedData 加载校验适配器 ID 合法性）。 */
    public boolean isRegistered(String typeId) {
        return adapters.containsKey(typeId);
    }

    /** 已注册适配器数量（测试与启动日志用）。 */
    public int size() {
        return adapters.size();
    }

    /**
     * 将仓储键规范化为稳定主键：支持多部件仓储（如双箱）的适配器会把
     * 任意半区位置归一到主半区，保证同一物理仓储键唯一。
     */
    public StorageKey canonicalize(StorageKey key) {
        StorageAdapter adapter = adapters.get(key.adapterType());
        if (adapter instanceof StorageAdapterExt ext) {
            return ext.canonicalize(key);
        }
        return key;
    }
}
