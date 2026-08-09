package com.poketrade.api.storage;

import java.util.Optional;
import java.util.Set;

/**
 * 适配器注册表：按稳定 {@code typeId} 管理适配器。
 *
 * <p>重复的 {@code typeId} 必须被拒绝（注册冲突直接失败并记录错误）。</p>
 */
public interface StorageAdapterRegistry {

    /**
     * 注册适配器。
     *
     * @throws IllegalArgumentException 若 {@code typeId} 已注册
     */
    void register(StorageAdapter adapter);

    /**
     * 按类型标识查找适配器。
     */
    Optional<StorageAdapter> byTypeId(String typeId);

    /**
     * 已注册的类型标识集合。
     */
    Set<String> typeIds();
}
