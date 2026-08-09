package com.pokeemc.menu;

import com.pokeemc.blockentity.CondenserBlockEntity;
import com.pokeemc.emc.PKMManager;
import com.pokeemc.registry.ModMenuTypes;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 能量凝聚器菜单：
 * <ul>
 *   <li>Slot 0：输入槽（放入物品自动换算成 PKM 缓冲）</li>
 *   <li>Slot 1：输出槽（凝聚器自动产出的目标物品）</li>
 *   <li>Slot 2-28：玩家背包 27 格</li>
 *   <li>Slot 29-37：快捷栏 9 格</li>
 * </ul>
 * PKM 缓冲用两个 DataSlot（高 32 位 + 低 32 位）同步；目标物品由
 * {@link com.pokeemc.network.SetCondenserTargetPacket} 从客户端设置。
 */
public class CondenserMenu extends AbstractContainerMenu {

    private static final int INPUT_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;
    private static final int PLAYER_INV_START = 2;
    private static final int PLAYER_INV_END = 29;
    private static final int HOTBAR_START = 29;
    private static final int HOTBAR_END = 38;

    private final CondenserBlockEntity condenser;
    private final DataSlot pkmHigh;
    private final DataSlot pkmLow;
    private boolean processingInput = false;

    /** 当前目标物品（客户端本地显示 + 服务端权威） */
    private ItemStack target;

    // 客户端构造（由 MenuType 调用）
    public CondenserMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null);
    }

    // 服务端构造
    public CondenserMenu(int containerId, Inventory playerInventory, CondenserBlockEntity condenser) {
        super(ModMenuTypes.CONDENSER.get(), containerId);
        this.condenser = condenser;
        this.target = condenser != null ? condenser.getTarget() : null;

        this.addSlot(new Slot(condenser != null ? condenser.getInputContainer() : new net.minecraft.world.SimpleContainer(1), 0, 44, 37) {
            @Override
            public void setChanged() {
                super.setChanged();
                CondenserMenu.this.onInputChanged();
            }
        });
        this.addSlot(new Slot(condenser != null ? condenser.getOutputContainer() : new net.minecraft.world.SimpleContainer(1), 0, 114, 37) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false; // 输出槽不可手动放入
            }
        });

        // 玩家背包（放在物品列表下方，避免与列表面板重叠）
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 164 + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 226));
        }

        // PKM 缓冲同步
        this.pkmHigh = DataSlot.standalone();
        this.pkmLow = DataSlot.standalone();
        this.addDataSlot(pkmHigh);
        this.addDataSlot(pkmLow);
        if (condenser != null) {
            syncPkmFromCondenser();
        }
    }

    private void syncPkmFromCondenser() {
        long pkm = condenser.getPkm();
        pkmHigh.set((int) (pkm >>> 32));
        pkmLow.set((int) (pkm & 0xFFFFFFFFL));
    }

    /** 客户端/服务端读取当前 PKM 缓冲 */
    public long getPkm() {
        return (((long) pkmHigh.get()) << 32) | (pkmLow.get() & 0xFFFFFFFFL);
    }

    public ItemStack getTarget() {
        return target == null ? ItemStack.EMPTY : target;
    }

    /** 服务端设置目标（由网络包调用），并更新方块实体持久化 */
    public void setTarget(ItemStack target) {
        ItemStack requested = target.copyWithCount(1);
        ItemStack normalized = requested;
        if (condenser != null) {
            normalized = PKMManager.snapshotStacks().stream()
                    .map(PKMManager.PricedStack::stack)
                    .filter(stack -> ItemStack.isSameItemSameComponents(stack, requested))
                    .findFirst()
                    .map(ItemStack::copy)
                    .orElse(ItemStack.EMPTY);
            if (normalized.isEmpty()) {
                return;
            }
            condenser.setTarget(normalized);
        }
        this.target = normalized;
        broadcastChanges();
    }

    /** 输入槽内容变化（服务端）：自动换算成 PKM 缓冲 */
    private void onInputChanged() {
        if (condenser == null || processingInput) {
            return;
        }
        processingInput = true;
        try {
            ItemStack stack = condenser.getInputContainer().getItem(0);
            if (stack.isEmpty()) {
                return;
            }
            long value = PKMManager.getPkm(stack);
            if (value <= 0) {
                return; // 无 PKM 值的物品无法存入
            }
            long total;
            try {
                total = Math.multiplyExact(value, stack.getCount());
            } catch (ArithmeticException ignored) {
                return;
            }
            condenser.addPkm(total);
            condenser.getInputContainer().setItem(0, ItemStack.EMPTY);
            syncPkmFromCondenser();
            broadcastChanges();
        } finally {
            processingInput = false;
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            itemstack = stack.copy();
            if (index == OUTPUT_SLOT) {
                if (!this.moveItemStackTo(stack, PLAYER_INV_START, HOTBAR_END, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (index == INPUT_SLOT) {
                if (!this.moveItemStackTo(stack, PLAYER_INV_START, HOTBAR_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (this.moveItemStackTo(stack, INPUT_SLOT, INPUT_SLOT + 1, false)) {
            } else if (index >= PLAYER_INV_START && index < PLAYER_INV_END) {
                if (!this.moveItemStackTo(stack, HOTBAR_START, HOTBAR_END, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index >= HOTBAR_START && index < HOTBAR_END) {
                if (!this.moveItemStackTo(stack, PLAYER_INV_START, PLAYER_INV_END, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stack.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stack);
        }
        return itemstack;
    }

    @Override
    public boolean stillValid(Player player) {
        return condenser == null || condenser.getBlockPos().closerToCenterThan(player.position(), 8.0);
    }
}
