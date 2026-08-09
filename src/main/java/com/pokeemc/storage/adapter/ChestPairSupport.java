package com.pokeemc.storage.adapter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * 双箱配对解析工具（普通箱与陷阱箱共用）。
 *
 * <p>核心约定：主半区 = 位置排序靠前的半区（先 X 后 Z 再 Y）。无论从哪个半区发起，
 * 都能推导出相同的主半区位置与统一的槽位顺序（主半区槽位 0-26，次半区 27-53）。</p>
 */
public final class ChestPairSupport {

    private ChestPairSupport() {
    }

    /** 状态是否为双箱成员（TYPE != SINGLE）。 */
    public static boolean isDoubleChest(BlockState state) {
        return state.getBlock() instanceof ChestBlock
                && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }

    /**
     * 返回 [主半区, 次半区] 两个 {@link ChestBlockEntity}；非双箱、另一半未加载或
     * 方块实体缺失时返回 {@code null}。
     */
    static ChestBlockEntity[] orderedPair(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!isDoubleChest(state)) {
            return null;
        }
        Direction connected = ChestBlock.getConnectedDirection(state);
        BlockPos other = pos.relative(connected);
        if (!level.isLoaded(other)) {
            return null;
        }
        BlockEntity a = level.getBlockEntity(pos);
        BlockEntity b = level.getBlockEntity(other);
        if (!(a instanceof ChestBlockEntity chestA) || !(b instanceof ChestBlockEntity chestB)) {
            return null;
        }
        return primaryIs(chestA.getBlockPos(), chestB.getBlockPos())
                ? new ChestBlockEntity[]{chestA, chestB}
                : new ChestBlockEntity[]{chestB, chestA};
    }

    /** 两个半区是否已经是主-次顺序（主在前）。 */
    static boolean primaryIs(BlockPos a, BlockPos b) {
        int c = Integer.compare(a.getX(), b.getX());
        if (c != 0) {
            return c < 0;
        }
        c = Integer.compare(a.getZ(), b.getZ());
        if (c != 0) {
            return c < 0;
        }
        return a.getY() <= b.getY();
    }

    /** 两个半区中的主半区位置。 */
    public static BlockPos primaryOf(BlockPos a, BlockPos b) {
        return primaryIs(a, b) ? a : b;
    }

    /** 位置上的方块是否为任意箱类（普通箱或陷阱箱）。 */
    static boolean isChestBlock(BlockState state) {
        return state.getBlock() instanceof ChestBlock;
    }
}
