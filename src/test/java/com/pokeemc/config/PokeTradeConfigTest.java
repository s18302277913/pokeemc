package com.pokeemc.config;

import com.pokeemc.storage.discovery.StorageConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端配置默认值契约：配置未加载（JUnit 环境）时读取值必须与既有硬编码默认
 * （{@link StorageConfig#DEFAULT_*} 与 {@code trade.*} 计划值）完全一致，
 * 保证升级后行为不变。
 */
class PokeTradeConfigTest {

    @Test
    void storageDefaultsMatchStorageConfigConstants() {
        StorageConfig config = PokeTradeConfig.storageConfig();
        assertEquals(StorageConfig.DEFAULT_RADIUS, config.defaultRadius());
        assertEquals(StorageConfig.MAX_PLAYER_RADIUS, config.maxPlayerRadius());
        assertEquals(StorageConfig.MAX_ADMIN_RADIUS, config.maxAdminRadius());
        assertEquals(StorageConfig.DEFAULT_MAX_RESULTS, config.maxResults());
        assertEquals(StorageConfig.MAX_CHUNKS_PER_TICK, config.maxChunksPerTick());
        assertEquals(StorageConfig.MAX_BLOCK_ENTITIES_PER_TICK, config.maxBlockEntitiesPerTick());
        assertEquals(StorageConfig.QUERY_COOLDOWN_TICKS, config.queryCooldownTicks());
        assertEquals(StorageConfig.MOVE_REFRESH_THRESHOLD_BLOCKS, config.moveRefreshThresholdBlocks());
        assertEquals(StorageConfig.DIRTY_DEDUPE_CAPACITY, config.dirtyDedupeCapacity());
        assertEquals(StorageConfig.MAX_SCANNED_PER_QUERY, config.maxScannedPerQuery());
        assertEquals(StorageConfig.REFRESH_QUEUE_CAPACITY, config.refreshQueueCapacity());
    }

    @Test
    void storageRadiusTiersRespectOrdering() {
        StorageConfig config = PokeTradeConfig.storageConfig();
        assertTrue(config.defaultRadius() <= config.maxPlayerRadius());
        assertTrue(config.maxPlayerRadius() <= config.maxAdminRadius());
    }

    @Test
    void tradeDefaults() {
        assertTrue(PokeTradeConfig.tradeEnabled());
        assertEquals(20, PokeTradeConfig.sweepIntervalTicks());
        assertEquals(0, PokeTradeConfig.feePercent());
    }

    @Test
    void shiftSellHandFallsBackToLeftWhenClientSpecUnloaded() {
        // [CHANGED] 会话 #10：CLIENT spec 在纯 JUnit/服务端/GameTest 下 isLoaded()==false，
        // shiftSellHand() 必须回退默认 LEFT，不得抛 IllegalStateException。
        assertEquals(PokeTradeConfig.ShiftSellHand.LEFT, PokeTradeConfig.shiftSellHand());
    }
}
