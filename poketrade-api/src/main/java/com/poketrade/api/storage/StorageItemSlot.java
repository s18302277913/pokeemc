package com.poketrade.api.storage;

import java.util.Objects;

/**
 * 单个槽位的物品摘要：稳定物品 ID、数量与指纹。
 *
 * <p>公共 API 不暴露 {@code ItemStack}，因此槽位内容用稳定物品键与数量摘要表示；
 * 指纹用于事务校验，由服务端按槽位内容计算。</p>
 */
public record StorageItemSlot(int slotIndex, String itemId, int count, long fingerprint) {

    public StorageItemSlot {
        if (slotIndex < 0) {
            throw new IllegalArgumentException("slotIndex must be non-negative");
        }
        Objects.requireNonNull(itemId, "itemId");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
