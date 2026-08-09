package com.pokeemc.storage.adapter;

import java.util.Objects;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 双箱统一视图：主半区槽位 0-26，次半区槽位 27-53。
 *
 * <p>无论从哪个半区打开，槽位顺序都保持一致，保证双箱任一位置访问同一
 * {@code StorageKey} 时内容视图完全相同。</p>
 */
final class DoubleContainer implements Container {

    private final Container primary;
    private final Container secondary;
    private final int split;

    DoubleContainer(Container primary, Container secondary) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.secondary = Objects.requireNonNull(secondary, "secondary");
        this.split = primary.getContainerSize();
    }

    private boolean inPrimary(int index) {
        return index < split;
    }

    private Container part(int index) {
        return inPrimary(index) ? primary : secondary;
    }

    private int local(int index) {
        return inPrimary(index) ? index : index - split;
    }

    @Override
    public int getContainerSize() {
        return split + secondary.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return primary.isEmpty() && secondary.isEmpty();
    }

    @Override
    public ItemStack getItem(int index) {
        return part(index).getItem(local(index));
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        return part(index).removeItem(local(index), count);
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        return part(index).removeItemNoUpdate(local(index));
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        part(index).setItem(local(index), stack);
    }

    @Override
    public void setChanged() {
        primary.setChanged();
        secondary.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return primary.stillValid(player) || secondary.stillValid(player);
    }

    @Override
    public void clearContent() {
        primary.clearContent();
        secondary.clearContent();
    }
}
