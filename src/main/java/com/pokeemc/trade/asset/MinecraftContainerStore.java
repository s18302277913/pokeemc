package com.pokeemc.trade.asset;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * 将任意 {@link Container}（末影箱等）桥接为 {@link PlayerInventoryStore}，
 * 供交易交付使用；逻辑与 {@link MinecraftPlayerInventoryStore} 一致。
 */
public final class MinecraftContainerStore implements PlayerInventoryStore {

    private final Container container;
    private final HolderLookup.Provider registries;

    private MinecraftContainerStore(Container container, HolderLookup.Provider registries) {
        this.container = Objects.requireNonNull(container, "container");
        this.registries = Objects.requireNonNull(registries, "registries");
    }

    public static MinecraftContainerStore of(Container container, HolderLookup.Provider registries) {
        return new MinecraftContainerStore(container, registries);
    }

    @Override
    public int size() {
        return container.getContainerSize();
    }

    @Override
    public ItemSnapshot get(int slot) {
        ItemStack stack = container.getItem(slot);
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
            container.setItem(slot, ItemStack.EMPTY);
            return;
        }
        ItemStack stack = ItemStack.parse(registries, snapshot.nbt()).orElse(ItemStack.EMPTY);
        container.setItem(slot, stack);
    }

    @Override
    public int maxStack(int slot) {
        ItemStack current = container.getItem(slot);
        return current.isEmpty() ? 64 : current.getMaxStackSize();
    }

    @Override
    public boolean isFull() {
        for (int i = 0; i < size(); i++) {
            if (container.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void setChanged() {
        container.setChanged();
    }
}
