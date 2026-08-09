package com.poketrade.api.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 仓储服务：浏览、快照与事务的单一授权入口。
 *
 * <p>实现位于根模组。所有操作都会以 {@code actorId} 重新鉴权；
 * 查询不授予任何权限，事务必须通过权限判定。</p>
 */
public interface StorageService {

    /**
     * 按查询浏览仓储。结果已按调用者权限过滤。
     */
    List<StorageDescriptor> query(StorageQuery query);

    /**
     * 获取单个仓储的槽位快照。
     *
     * @param storageId        仓储标识
     * @param expectedRevision 客户端已知修订；{@code -1} 表示总是返回最新
     * @return 快照；仓储不存在或调用者无 VIEW 权限时为空
     */
    Optional<StorageSnapshot> snapshot(UUID actorId, StorageId storageId, long expectedRevision);

    /**
     * 执行仓储事务（存入/取出/仓储间转移）。
     */
    StorageTransactionResult execute(StorageTransaction transaction);
}
