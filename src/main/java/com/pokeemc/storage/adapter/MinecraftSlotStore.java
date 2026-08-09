package com.pokeemc.storage.adapter;

import java.util.Objects;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 将 Minecraft {@link Container} 桥接为 {@link SlotStore}。
 *
 * <p>物品 ID 使用注册表键（"命名空间:id"）；指纹由物品 ID、数量与组件内容哈希得出，
 * 用于事务冲突校验。</p>
 */
public final class MinecraftSlotStore implements SlotStore {

    private final Container container;

    private MinecraftSlotStore(Container container) {
        this.container = Objects.requireNonNull(container, "container");
    }

    public static MinecraftSlotStore of(Container container) {
        return new MinecraftSlotStore(container);
    }

    public Container container() {
        return container;
    }

    @Override
    public int size() {
        return container.getContainerSize();
    }

    @Override
    public String itemId(int slot) {
        // [CHANGED] Bug 1：球类经 PokeballIdentity 编码（pixelmon:poke_ball#<球种>），
        // 保留球种组件身份，避免大师球与精灵球在仓储链路中互相混淆。
        return PokeballIdentity.encode(container.getItem(slot));
    }

    @Override
    public int count(int slot) {
        return container.getItem(slot).getCount();
    }

    @Override
    public int maxStack(int slot, String itemId) {
        ItemStack current = container.getItem(slot);
        if (!current.isEmpty()) {
            return container.getMaxStackSize(current);
        }
        // [CHANGED] Bug 1：经 baseItem 去掉球种后缀解析 base 注册表键。
        Item item = PokeballIdentity.baseItem(itemId);
        // 注意：不能传 ItemStack.EMPTY 查询 getMaxStackSize —— NeoForge 的 IItemExtension
        // 实现为 stack.getOrDefault(MAX_STACK_SIZE, 1)，EMPTY 无组件恒返回 1。
        return item == null ? 1 : item.getDefaultMaxStackSize();
    }

    @Override
    public long fingerprint(int slot) {
        return fingerprint(container.getItem(slot));
    }

    @Override
    public void set(int slot, String itemId, int count) {
        if (itemId == null || count <= 0) {
            container.setItem(slot, ItemStack.EMPTY);
            return;
        }
        // [CHANGED] Bug 1：统一经 PokeballIdentity 解码——球类还原球种组件；
        // 未知球种/非法编码返回 null 时抛异常（宁失败不静默降级成精灵球）。
        ItemStack s = PokeballIdentity.decode(itemId, count);
        if (s == null) {
            throw new IllegalArgumentException("cannot restore item identity: " + itemId);
        }
        container.setItem(slot, s);
    }

    @Override
    public void setChanged() {
        container.setChanged();
    }

    /** 空栈指纹为 0；否则由物品、数量与组件内容稳定哈希。 */
    public static long fingerprint(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0L;
        }
        long h = 1125899906842597L;
        h = 31L * h + stack.getItem().hashCode();
        h = 31L * h + stack.getCount();
        h = 31L * h + stack.getComponents().hashCode();
        return h;
    }
}
