package com.pokeemc.storage.adapter;

import java.util.Objects;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 凝聚器两槽视图：槽位 0 = 输入槽，槽位 1 = 输出槽。
 *
 * <p>输入槽接收任意可凝聚材料，输出槽只放凝聚产物。存储适配器通过该容器
 * 配合槽位过滤（只能插入槽 0、只能从槽 1 提取）实现"凝聚器槽位过滤"。</p>
 */
final class TwoSlotContainer implements Container {

    private final Container input;
    private final Container output;

    TwoSlotContainer(Container input, Container output) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public int getContainerSize() {
        return 2;
    }

    @Override
    public boolean isEmpty() {
        return input.isEmpty() && output.isEmpty();
    }

    @Override
    public ItemStack getItem(int index) {
        return part(index).getItem(0);
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        return part(index).removeItem(0, count);
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        return part(index).removeItemNoUpdate(0);
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        part(index).setItem(0, stack);
    }

    @Override
    public void setChanged() {
        input.setChanged();
        output.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        input.clearContent();
        output.clearContent();
    }

    private Container part(int index) {
        return index == 0 ? input : output;
    }
}
