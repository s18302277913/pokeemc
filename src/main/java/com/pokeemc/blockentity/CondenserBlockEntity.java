package com.pokeemc.blockentity;

import com.pokeemc.emc.PKMManager;
import com.pokeemc.menu.CondenserMenu;
import com.pokeemc.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 能量凝聚器方块实体：存储 PKM 缓冲，并自动把缓冲凝聚成选定的目标物品。
 * <p>
 * 玩法：玩家把任意有价值的材料投入输入槽 → 换算成 PKM 存入缓冲 →
 * 每 tick 检查缓冲是否足够兑换目标物品，足够则自动产出 1 个到输出槽。
 * 缓冲为方块本地数值（单位与玩家钱包同为宝可元），不与玩家钱包直接互通。
 */
public class CondenserBlockEntity extends BlockEntity {

    public static final String PKM_TAG = "pkm_buffer";
    public static final String TARGET_TAG = "target";
    public static final long MAX_PKM = Long.MAX_VALUE / 4;

    private long pkmBuffer = 0;
    private ItemStack target = ItemStack.EMPTY;

    private final SimpleContainer inputContainer = new SimpleContainer(1);
    private final SimpleContainer outputContainer = new SimpleContainer(1);

    public CondenserBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONDENSER.get(), pos, state);
    }

    // ---------- PKM 缓冲 ----------

    public long getPkm() {
        return pkmBuffer;
    }

    /** 存入 PKM，返回实际存入量 */
    public long addPkm(long amount) {
        if (amount <= 0) {
            return 0;
        }
        long actual = Math.min(amount, MAX_PKM - pkmBuffer);
        if (actual > 0) {
            pkmBuffer += actual;
            setChanged();
        }
        return actual;
    }

    /** 取出 PKM，返回实际取出量；不足返回 0 */
    public long consumePkm(long amount) {
        if (amount <= 0) {
            return 0;
        }
        long actual = Math.min(amount, pkmBuffer);
        if (actual > 0) {
            pkmBuffer -= actual;
            setChanged();
        }
        return actual;
    }

    // ---------- 目标物品 ----------

    public ItemStack getTarget() {
        return target.copy();
    }

    public void setTarget(ItemStack target) {
        this.target = target.copyWithCount(1);
        setChanged();
    }

    // ---------- 容器 ----------

    public Container getInputContainer() {
        return inputContainer;
    }

    public Container getOutputContainer() {
        return outputContainer;
    }

    // ---------- 自动凝聚 ----------

    public void tickServer() {
        if (target.isEmpty() || level == null || level.isClientSide()) {
            return;
        }
        ItemStack targetItem = target.copyWithCount(1);
        if (targetItem.isEmpty()) {
            return;
        }
        long value = PKMManager.getPkm(targetItem);
        if (value <= 0) {
            return;
        }
        // 缓冲足够 + 输出槽可放入才凝聚
        ItemStack out = outputContainer.getItem(0);
        if (pkmBuffer < value) {
            return;
        }
        if (!out.isEmpty() && (!ItemStack.isSameItemSameComponents(out, targetItem)
                || out.getCount() >= out.getMaxStackSize())) {
            return;
        }
        pkmBuffer -= value;
        if (out.isEmpty()) {
            outputContainer.setItem(0, targetItem.copy());
        } else {
            out.grow(1);
        }
        setChanged();
    }

    // ---------- NBT ----------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong(PKM_TAG, pkmBuffer);
        if (!target.isEmpty()) {
            tag.put(TARGET_TAG, target.save(registries));
        }
        if (!inputContainer.getItem(0).isEmpty()) {
            tag.put("input", inputContainer.getItem(0).save(registries));
        }
        if (!outputContainer.getItem(0).isEmpty()) {
            tag.put("output", outputContainer.getItem(0).save(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        pkmBuffer = tag.getLong(PKM_TAG);
        if (tag.contains(TARGET_TAG, CompoundTag.TAG_COMPOUND)) {
            target = ItemStack.parse(registries, tag.getCompound(TARGET_TAG)).orElse(ItemStack.EMPTY);
        } else if (tag.contains(TARGET_TAG, CompoundTag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(tag.getString(TARGET_TAG));
            target = id == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(id));
        } else {
            target = ItemStack.EMPTY;
        }
        inputContainer.setItem(0, ItemStack.parse(registries, tag.getCompound("input")).orElse(ItemStack.EMPTY));
        outputContainer.setItem(0, ItemStack.parse(registries, tag.getCompound("output")).orElse(ItemStack.EMPTY));
    }

    public MenuProvider getMenuProvider() {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("block.poketrade.condenser");
            }

            @Override
            @Nullable
            public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
                return new CondenserMenu(containerId, playerInventory, CondenserBlockEntity.this);
            }
        };
    }
}
