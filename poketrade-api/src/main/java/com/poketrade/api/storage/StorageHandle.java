package com.poketrade.api.storage;

import java.util.Set;

/**
 * 仓储句柄：槽位快照与两阶段事务（simulate 后 commit）。
 *
 * <p>调用方必须先 {@link #simulateInsert}/{@link #simulateExtract} 校验，
 * 再 {@link #commitInsert}/{@link #commitExtract} 提交。commit 仅应在
 * simulate 通过后调用；commit 失败必须回滚并抛出异常，由上层转换结果。</p>
 */
public interface StorageHandle extends AutoCloseable {

    /**
     * 当前快照。多次调用应返回相同修订（除非发生外部修改）。
     */
    StorageSnapshot snapshot();

    /**
     * 校验向指定槽位放入物品是否可行（容量、堆叠等）。
     */
    boolean simulateInsert(int slotIndex, String itemId, int count);

    /**
     * 校验从指定槽位取出物品是否可行（数量、物品匹配）。
     */
    boolean simulateExtract(int slotIndex, String itemId, int count);

    /**
     * 提交放入。仅在 {@link #simulateInsert} 通过后调用。
     */
    void commitInsert(int slotIndex, String itemId, int count);

    /**
     * 提交取出。仅在 {@link #simulateExtract} 通过后调用。
     */
    void commitExtract(int slotIndex, String itemId, int count);

    @Override
    void close();
}
