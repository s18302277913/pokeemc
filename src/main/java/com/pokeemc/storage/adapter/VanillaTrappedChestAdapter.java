package com.pokeemc.storage.adapter;

import static com.poketrade.api.storage.StorageCapability.AUTOMATION;
import static com.poketrade.api.storage.StorageCapability.EXTRACT;
import static com.poketrade.api.storage.StorageCapability.INSERT;
import static com.poketrade.api.storage.StorageCapability.MULTI_BLOCK;
import static com.poketrade.api.storage.StorageCapability.SELL_SOURCE;
import static com.poketrade.api.storage.StorageCapability.SNAPSHOT;

import com.pokeemc.storage.StorageKey;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 陷阱箱适配器（typeId {@code vanilla_trapped_chest}）。
 *
 * <p>陷阱箱可单可双：双箱成员同样归一为主半区位置，物理部件为两个方块实体；
 * 单箱退化为 27 槽普通容器。</p>
 */
public final class VanillaTrappedChestAdapter extends AbstractContainerAdapter implements StorageAdapterExt {

    public VanillaTrappedChestAdapter() {
        super("vanilla_trapped_chest", SNAPSHOT, INSERT, EXTRACT, SELL_SOURCE, AUTOMATION, MULTI_BLOCK);
    }

    @Override
    public StorageKey canonicalize(StorageKey key) {
        Level level = resolveLevel(key.dimension());
        BlockPos pos = parsePos(key.location());
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return key;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TrappedChestBlock)
                || !ChestPairSupport.isDoubleChest(state)) {
            return key;
        }
        BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
        if (!level.isLoaded(other)) {
            return key;
        }
        return StorageKey.of(key.dimension(), key.adapterType(),
                toLocation(ChestPairSupport.primaryOf(pos, other)));
    }

    @Override
    protected boolean matches(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof TrappedChestBlock;
    }

    @Override
    protected Optional<ContainerAccess> resolve(Level level, BlockPos pos) {
        ChestBlockEntity[] pair = ChestPairSupport.orderedPair(level, pos);
        if (pair != null) {
            return Optional.of(simple(new DoubleContainer(pair[0], pair[1])));
        }
        // 单陷阱箱
        if (level.getBlockEntity(pos) instanceof ChestBlockEntity single) {
            return Optional.of(simple(single));
        }
        return Optional.empty();
    }
}
