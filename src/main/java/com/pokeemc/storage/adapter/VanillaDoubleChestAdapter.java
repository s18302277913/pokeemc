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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 原版双箱适配器（typeId {@code vanilla_double_chest}）。
 *
 * <p>多部件仓储：无论从哪一半访问都返回同一 {@link StorageKey}（归一为主半区位置），
 * 统一槽位顺序（主半区 0-26、次半区 27-53），物理部件集合为两个半区的方块实体。</p>
 */
public final class VanillaDoubleChestAdapter extends AbstractContainerAdapter implements StorageAdapterExt {

    public VanillaDoubleChestAdapter() {
        super("vanilla_double_chest", SNAPSHOT, INSERT, EXTRACT, SELL_SOURCE, AUTOMATION, MULTI_BLOCK);
    }

    @Override
    public StorageKey canonicalize(StorageKey key) {
        Level level = resolveLevel(key.dimension());
        BlockPos pos = parsePos(key.location());
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return key;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() != Blocks.CHEST || !ChestPairSupport.isDoubleChest(state)) {
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
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == Blocks.CHEST && ChestPairSupport.isDoubleChest(state);
    }

    @Override
    protected Optional<ContainerAccess> resolve(Level level, BlockPos pos) {
        ChestBlockEntity[] pair = ChestPairSupport.orderedPair(level, pos);
        if (pair == null) {
            return Optional.empty();
        }
        return Optional.of(simple(new DoubleContainer(pair[0], pair[1])));
    }
}
