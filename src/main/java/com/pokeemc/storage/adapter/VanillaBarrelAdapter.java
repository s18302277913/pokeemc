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
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 原版木桶适配器（typeId {@code vanilla_barrel}）。
 */
public final class VanillaBarrelAdapter extends AbstractContainerAdapter {

    public VanillaBarrelAdapter() {
        super("vanilla_barrel", SNAPSHOT, INSERT, EXTRACT, SELL_SOURCE, AUTOMATION);
    }

    @Override
    protected boolean matches(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() == Blocks.BARREL;
    }

    @Override
    protected Optional<ContainerAccess> resolve(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BarrelBlockEntity barrel)) {
            return Optional.empty();
        }
        return Optional.of(simple(barrel));
    }
}
