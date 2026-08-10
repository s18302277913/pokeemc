package com.pokeemc.emc;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话 #16：球种级 PKM 值（{@link PKMManager#setBallManual}）行为契约——
 * 大师球 tooltip 显示 256 修复的数据层回归。
 */
class PKMManagerTest {

    @Test
    void ballManualValueSurvivesClearComputed() {
        // clearComputed()（数据包重载/合成树清除时调用）不得丢失球种级手工 PKM 值
        PKMManager.setBallManual("master_ball", 5_000_000L);
        try {
            PKMManager.clearComputed();
            assertEquals(5_000_000L, PKMManager.getBallValue("master_ball"),
                    "clearComputed 必须保留球种级手工 PKM 值");
        } finally {
            // 残留无害：真实服务器数据包重载会用数据文件值覆盖；测试环境无后续读取该键
        }
    }

    @Test
    void ballManualDoesNotPollutePkmSnapshot() {
        // setBallManual 只写球层，不得写入 PKM_VALUES——否则 pixelmon:<球种> 幽灵 id
        // 会经 pkmFallback 流入目录兜底（会话 #14 已废弃幽灵键写法）
        PKMManager.setBallManual("ultra_ball", 1024L);
        ResourceLocation ghost = ResourceLocation.fromNamespaceAndPath("pixelmon", "ultra_ball");
        assertFalse(PKMManager.snapshot().containsKey(ghost),
                "setBallManual 不得写入 PKM_VALUES（幽灵键会流入目录兜底）");
    }

    @Test
    void ballManualIncrementsVersion() {
        // 球价变更必须递增版本号，触发 ExchangePriceService.catalog() 惰性重建
        long before = PKMManager.version();
        PKMManager.setBallManual("premier_ball", 256L);
        assertTrue(PKMManager.version() > before,
                "setBallManual 必须递增版本号以触发目录惰性重建");
    }
}
