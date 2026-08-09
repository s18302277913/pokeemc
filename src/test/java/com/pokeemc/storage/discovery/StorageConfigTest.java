package com.pokeemc.storage.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link StorageConfig} 的半径/结果数 clamp 与构造校验（Task 6）。
 */
class StorageConfigTest {

    private final StorageConfig config = new StorageConfig();

    @Test
    void clampRadiusFallsBackToDefaultForNonPositive() {
        assertEquals(StorageConfig.DEFAULT_RADIUS, config.clampRadius(0, false));
        assertEquals(StorageConfig.DEFAULT_RADIUS, config.clampRadius(-7, true));
    }

    @Test
    void clampRadiusKeepsInRangeRequest() {
        assertEquals(50, config.clampRadius(50, false));
        assertEquals(1, config.clampRadius(1, true));
    }

    @Test
    void clampRadiusCapsAtPlayerAdminTiers() {
        assertEquals(StorageConfig.MAX_PLAYER_RADIUS, config.clampRadius(10_000, false));
        assertEquals(StorageConfig.MAX_ADMIN_RADIUS, config.clampRadius(10_000, true));
        // 上限以内放行（会话 #9 起玩家/管理员上限均为 648，200 不再被截断）
        assertEquals(200, config.clampRadius(200, true));
        assertEquals(200, config.clampRadius(200, false));
    }

    @Test
    void clampMaxResultsClampsToRange() {
        assertEquals(1, config.clampMaxResults(0));
        assertEquals(1, config.clampMaxResults(-5));
        assertEquals(50, config.clampMaxResults(50));
        assertEquals(StorageConfig.DEFAULT_MAX_RESULTS, config.clampMaxResults(10_000));
    }

    @Test
    void constructorRejectsNonPositiveValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageConfig(0, 128, 256, 200,
                        2, 512, 10, 4, 10_000, 2_000, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageConfig(32, 128, 256, 0,
                        2, 512, 10, 4, 10_000, 2_000, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageConfig(32, 128, 256, 200,
                        2, 512, 10, 4, 10_000, 2_000, 0));
    }

    @Test
    void constructorRejectsBrokenRadiusTiers() {
        // default > player
        assertThrows(IllegalArgumentException.class,
                () -> new StorageConfig(64, 32, 256, 200,
                        2, 512, 10, 4, 10_000, 2_000, 8));
        // player > admin
        assertThrows(IllegalArgumentException.class,
                () -> new StorageConfig(32, 256, 128, 200,
                        2, 512, 10, 4, 10_000, 2_000, 8));
    }
}
