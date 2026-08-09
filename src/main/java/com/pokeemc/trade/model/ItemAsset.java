package com.pokeemc.trade.model;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * 物品托管资产（计划 3.2）：完整 ItemStack NBT 已从原背包槽移入交易托管。
 * 身份使用完整 NBT + 数量，不按 registry id 判断。
 */
public record ItemAsset(
        UUID assetId,
        UUID originalOwner,
        CompoundTag stackNbt
) implements TradeAsset {

    public ItemAsset {
        if (assetId == null || originalOwner == null) {
            throw new IllegalArgumentException("assetId/originalOwner cannot be null");
        }
        if (stackNbt == null) {
            throw new IllegalArgumentException("stackNbt cannot be null");
        }
    }

    @Override
    public String kind() {
        return "ITEM";
    }

    /** 构造物品资产；assetId 由调用方生成并记录到交易日志 */
    public static ItemAsset create(UUID originalOwner, CompoundTag stackNbt) {
        return new ItemAsset(UUID.randomUUID(), originalOwner, stackNbt);
    }
}
