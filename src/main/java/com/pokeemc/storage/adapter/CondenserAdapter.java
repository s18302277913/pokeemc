package com.pokeemc.storage.adapter;

import static com.poketrade.api.storage.StorageCapability.EXTRACT;
import static com.poketrade.api.storage.StorageCapability.INSERT;
import static com.poketrade.api.storage.StorageCapability.SNAPSHOT;

import com.pokeemc.blockentity.CondenserBlockEntity;
import com.pokeemc.registry.ModBlocks;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 能量凝聚器适配器（typeId {@code poketrade_condenser}）。
 *
 * <p>凝聚器存储视图 = 输入槽（槽位 0）+ 输出槽（槽位 1）。槽位过滤：
 * 只允许向输入槽插入、只允许从输出槽提取，防止绕过凝聚流程。</p>
 */
public final class CondenserAdapter extends AbstractContainerAdapter {

    public CondenserAdapter() {
        super("poketrade_condenser", SNAPSHOT, INSERT, EXTRACT);
    }

    @Override
    protected boolean matches(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() == ModBlocks.CONDENSER.get();
    }

    @Override
    protected Optional<ContainerAccess> resolve(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof CondenserBlockEntity condenser)) {
            return Optional.empty();
        }
        return Optional.of(filtered(new TwoSlotContainer(condenser.getInputContainer(),
                        condenser.getOutputContainer()),
                slot -> slot == 0,
                slot -> slot == 1));
    }
}
