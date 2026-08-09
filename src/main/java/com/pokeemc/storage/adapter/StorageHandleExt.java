package com.pokeemc.storage.adapter;

import com.poketrade.api.storage.StorageHandle;

/**
 * 根模组内部扩展的句柄接口，在公共 API 之外补充容器规模等信息。
 *
 * <p>公共 API 不暴露 Minecraft/容器类型；槽位总数等实现细节仅根模组内部使用，
 * 因此放在扩展接口而非 {@link StorageHandle} 上。</p>
 */
public interface StorageHandleExt extends StorageHandle {

    /** 底层容器槽位总数（用于描述符 slotCount）。 */
    int slotCount();

    /** 槽位物品 ID；空槽返回 {@code null}。仅供根模组内部事务逻辑使用。 */
    String itemId(int slot);

    /** 槽位当前数量。仅供根模组内部事务逻辑使用。 */
    int count(int slot);

    /** 槽位内容指纹；空槽为 0。仅供根模组内部事务冲突校验使用。 */
    long fingerprint(int slot);
}
