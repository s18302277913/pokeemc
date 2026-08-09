package com.pokeemc.storage.adapter;

import com.pokeemc.storage.StorageKey;
import com.poketrade.api.storage.StorageAdapter;

/**
 * 需要"键规范化"的适配器扩展标记（根模组内部）。
 *
 * <p>多部件仓储（如双箱）无论从哪个部件访问都应指向同一仓储键；实现方通过
 * {@link #canonicalize(StorageKey)} 把键归一为主部件的稳定位置。</p>
 */
public interface StorageAdapterExt extends StorageAdapter {

    /**
     * 返回规范化后的仓储键；非多部件仓储或无法解析时原样返回。
     *
     * <p>仅在服务端主线程调用（需要读取世界状态）。</p>
     */
    StorageKey canonicalize(StorageKey key);
}
