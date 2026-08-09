package com.pokeemc.trade.asset;

import net.minecraft.nbt.CompoundTag;

/**
 * 槽位物品快照（纯数据，JVM 可测）：{@code itemId} 为注册表键（"命名空间:id"），
 * {@code nbt} 为完整 ItemStack 序列化 NBT（含组件），用于托管/归还/交付的身份与完整性。
 */
public record ItemSnapshot(String itemId, int count, CompoundTag nbt) {

    public ItemSnapshot {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId cannot be blank");
        }
        if (count < 0) {
            throw new IllegalArgumentException("count cannot be negative");
        }
        if (nbt == null) {
            throw new IllegalArgumentException("nbt cannot be null");
        }
    }

    /** 空槽快照 */
    public static ItemSnapshot empty() {
        return new ItemSnapshot("minecraft:air", 0, new CompoundTag());
    }

    public boolean isEmpty() {
        return count <= 0 || itemId.equals("minecraft:air");
    }
}
