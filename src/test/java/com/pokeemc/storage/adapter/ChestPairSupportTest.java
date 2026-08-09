package com.pokeemc.storage.adapter;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双箱配对排序纯逻辑测试：主半区 = 先 X 后 Z 再 Y 排序靠前的半区。
 */
class ChestPairSupportTest {

    @Test
    void primaryIsOrdersByXThenZThenY() {
        // 先 X
        assertTrue(ChestPairSupport.primaryIs(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)));
        assertFalse(ChestPairSupport.primaryIs(new BlockPos(1, 64, 0), new BlockPos(0, 64, 0)));
        // 同 X 后 Z
        assertTrue(ChestPairSupport.primaryIs(new BlockPos(0, 64, 0), new BlockPos(0, 64, 1)));
        assertFalse(ChestPairSupport.primaryIs(new BlockPos(0, 64, 1), new BlockPos(0, 64, 0)));
        // 同 X 同 Z 后 Y（等于时左侧为主）
        assertTrue(ChestPairSupport.primaryIs(new BlockPos(0, 63, 0), new BlockPos(0, 64, 0)));
        assertFalse(ChestPairSupport.primaryIs(new BlockPos(0, 65, 0), new BlockPos(0, 64, 0)));
        assertTrue(ChestPairSupport.primaryIs(new BlockPos(0, 64, 0), new BlockPos(0, 64, 0)),
                "同位置视为自身为主");
    }

    @Test
    void primaryOfReturnsLeadingHalfRegardlessOfArgumentOrder() {
        BlockPos a = new BlockPos(5, 64, 9);
        BlockPos b = new BlockPos(5, 64, 10);
        assertEquals(a, ChestPairSupport.primaryOf(a, b));
        assertEquals(a, ChestPairSupport.primaryOf(b, a));
    }

    @Test
    void primaryOfIsStableForIdenticalPositions() {
        BlockPos p = new BlockPos(3, 64, 3);
        assertEquals(p, ChestPairSupport.primaryOf(p, p));
    }

    @Test
    void trappedChestIsChestFamilyMember() {
        // 实证 MC 1.21.1 类继承：TrappedChestBlock extends ChestBlock（共享 TYPE 属性）。
        // 因此 ChestPairSupport.isDoubleChest 的 instanceof ChestBlock 判定对陷阱箱同样生效，
        // 双陷阱箱可正常走 DoubleContainer 分支（Bug #9 复核结论：无配对逻辑缺陷）。
        assertTrue(ChestBlock.class.isAssignableFrom(TrappedChestBlock.class),
                "MC 结构变更会导致本断言失效，需复查双箱配对逻辑");
        assertTrue(AbstractChestBlock.class.isAssignableFrom(ChestBlock.class));
    }
}
