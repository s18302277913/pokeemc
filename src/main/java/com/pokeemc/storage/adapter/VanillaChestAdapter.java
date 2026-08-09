package com.pokeemc.storage.adapter;

import static com.poketrade.api.storage.StorageCapability.AUTOMATION;
import static com.poketrade.api.storage.StorageCapability.EXTRACT;
import static com.poketrade.api.storage.StorageCapability.INSERT;
import static com.poketrade.api.storage.StorageCapability.SELL_SOURCE;
import static com.poketrade.api.storage.StorageCapability.SNAPSHOT;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * 原版单箱适配器（typeId {@code vanilla_chest}）。
 *
 * <p>只匹配普通箱（{@code Blocks.CHEST}）且为非双箱成员（TYPE == SINGLE）的状态；
 * 双箱由 {@link VanillaDoubleChestAdapter} 处理。</p>
 */
public final class VanillaChestAdapter extends AbstractContainerAdapter {

    public VanillaChestAdapter() {
        super("vanilla_chest", SNAPSHOT, INSERT, EXTRACT, SELL_SOURCE, AUTOMATION);
    }

    @Override
    protected boolean matches(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == Blocks.CHEST
                && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) == ChestType.SINGLE;
    }

    @Override
    protected Optional<ContainerAccess> resolve(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof ChestBlockEntity chest)) {
            return Optional.empty();
        }
        return Optional.of(simple(chest));
    }
}
