package com.poketrade.api.storage;

import java.util.Optional;
import java.util.Set;

/**
 * 仓储适配器：将容器（箱子、双箱、冷凝器、模组容器）映射为统一槽位接口。
 *
 * <p>适配器必须声明稳定的 {@code typeId}、能力集合、规范化 ID 规则和事务能力。
 * 不支持的容器由 {@link #supports(StorageAdapterContext)} 判定；查询/事务期间
 * 只访问已加载的区块。</p>
 */
public interface StorageAdapter {

    /**
     * 适配器稳定标识，如 {@code vanilla_chest}。
     */
    String typeId();

    /**
     * 该适配器可提供的能力。能力声明必须与实际实现一致。
     */
    Set<StorageCapability> capabilities();

    /**
     * 判断上下文对应的容器是否可被本适配器处理。
     */
    boolean supports(StorageAdapterContext context);

    /**
     * 打开仓储句柄。不可用时返回空（区块未加载、容器缺失等）。
     */
    Optional<StorageHandle> open(StorageAdapterContext context);
}
