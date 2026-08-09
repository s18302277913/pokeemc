package com.pokeemc.trade.asset;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * 将 Minecraft {@link Inventory}（ServerPlayer 背包）桥接为 {@link PlayerInventoryStore}。
 * 快照使用完整 ItemStack 序列化 NBT（含组件），保证托管前后身份/组件一致。
 */
public final class MinecraftPlayerInventoryStore implements PlayerInventoryStore {

    private final Inventory inventory;
    private final HolderLookup.Provider registries;

    private MinecraftPlayerInventoryStore(Inventory inventory, HolderLookup.Provider registries) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.registries = Objects.requireNonNull(registries, "registries");
    }

    public static MinecraftPlayerInventoryStore of(Inventory inventory, HolderLookup.Provider registries) {
        return new MinecraftPlayerInventoryStore(inventory, registries);
    }

    @Override
    public int size() {
        return inventory.getContainerSize();
    }

    @Override
    public ItemSnapshot get(int slot) {
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return ItemSnapshot.empty();
        }
        return new ItemSnapshot(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(),
                (CompoundTag) stack.save(registries));
    }

    @Override
    public void set(int slot, ItemSnapshot snapshot) {
        if (slot < 0 || slot >= size()) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        if (snapshot == null || snapshot.isEmpty()) {
            inventory.setItem(slot, ItemStack.EMPTY);
            return;
        }
        ItemStack stack = ItemStack.parse(registries, snapshot.nbt()).orElse(ItemStack.EMPTY);
        inventory.setItem(slot, stack);
    }

    @Override
    public int maxStack(int slot) {
        ItemStack current = inventory.getItem(slot);
        return current.isEmpty() ? 64 : current.getMaxStackSize();
    }

    @Override
    public boolean isFull() {
        return inventory.getFreeSlot() == -1;
    }

    @Override
    public void setChanged() {
        inventory.setChanged();
    }
}
