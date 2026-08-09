package com.poketrade.api.storage;

import java.util.Objects;

/**
 * 适配器运行上下文。由根模组实现层提供，桥接 Minecraft 世界对象。
 *
 * <p>公共 API 不暴露 Minecraft/NeoForge 类型；根模组在实现
 * {@code StorageAdapterContext} 时携带游戏对象，并在适配器内部转换。</p>
 */
public record StorageAdapterContext(StorageId storageId) {

    public StorageAdapterContext {
        Objects.requireNonNull(storageId, "storageId");
    }
}
