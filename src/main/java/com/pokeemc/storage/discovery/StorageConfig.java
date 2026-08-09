package com.pokeemc.storage.discovery;

/**
 * 仓储发现与认领的集中配置。
 *
 * <p>数值与计划文档 4.2「发现与扫描」小节一致。所有取值在构造时强校验，
 * 任何非法值（非正、越界）都会抛出 {@link IllegalArgumentException}，
 * 保证运行期预算值始终处于受控范围。</p>
 */
public final class StorageConfig {

    /** 玩家查询的默认半径（方块）。 */
    public static final int DEFAULT_RADIUS = 32;
    /** 普通玩家可查询的最大半径（会话 #9 起提升到 648，配合交易所范围按钮档位上限）。 */
    public static final int MAX_PLAYER_RADIUS = 648;
    /** 管理员（有 manage 权限的 actor）可查询的最大半径（同玩家上限；大范围扫描受 MAX_SCANNED_PER_QUERY 硬上限保护）。 */
    public static final int MAX_ADMIN_RADIUS = 648;
    /** 单次查询返回结果的硬上限。 */
    public static final int DEFAULT_MAX_RESULTS = 200;
    /** 后台增量刷新时每个 tick 最多扫描的区块数。 */
    public static final int MAX_CHUNKS_PER_TICK = 2;
    /** 后台增量刷新时每个 tick 最多检查的 block entity 数。 */
    public static final int MAX_BLOCK_ENTITIES_PER_TICK = 512;
    /** 同一 actor 两次查询之间的最小间隔（tick），超限返回缓存。 */
    public static final int QUERY_COOLDOWN_TICKS = 10;
    /** 玩家移动超过该格数（方块）时触发增量刷新。 */
    public static final int MOVE_REFRESH_THRESHOLD_BLOCKS = 4;
    /** 脏区块去重集合的容量上限（10,000 次重复标脏去重）。 */
    public static final int DIRTY_DEDUPE_CAPACITY = 10_000;
    /** 单次同步查询最多扫描的仓储数，超过则结果标记为不完整。 */
    public static final int MAX_SCANNED_PER_QUERY = 2_000;
    /** 后台增量刷新队列的容量上限，满时丢弃最旧任务并告警。 */
    public static final int REFRESH_QUEUE_CAPACITY = 8;

    private final int defaultRadius;
    private final int maxPlayerRadius;
    private final int maxAdminRadius;
    private final int maxResults;
    private final int maxChunksPerTick;
    private final int maxBlockEntitiesPerTick;
    private final int queryCooldownTicks;
    private final int moveRefreshThresholdBlocks;
    private final int dirtyDedupeCapacity;
    private final int maxScannedPerQuery;
    private final int refreshQueueCapacity;

    public StorageConfig() {
        this(DEFAULT_RADIUS, MAX_PLAYER_RADIUS, MAX_ADMIN_RADIUS, DEFAULT_MAX_RESULTS,
                MAX_CHUNKS_PER_TICK, MAX_BLOCK_ENTITIES_PER_TICK, QUERY_COOLDOWN_TICKS,
                MOVE_REFRESH_THRESHOLD_BLOCKS, DIRTY_DEDUPE_CAPACITY, MAX_SCANNED_PER_QUERY,
                REFRESH_QUEUE_CAPACITY);
    }

    public StorageConfig(int defaultRadius, int maxPlayerRadius, int maxAdminRadius, int maxResults,
                         int maxChunksPerTick, int maxBlockEntitiesPerTick, int queryCooldownTicks,
                         int moveRefreshThresholdBlocks, int dirtyDedupeCapacity,
                         int maxScannedPerQuery, int refreshQueueCapacity) {
        this.defaultRadius = positive(defaultRadius, "defaultRadius");
        this.maxPlayerRadius = positive(maxPlayerRadius, "maxPlayerRadius");
        this.maxAdminRadius = positive(maxAdminRadius, "maxAdminRadius");
        this.maxResults = positive(maxResults, "maxResults");
        this.maxChunksPerTick = positive(maxChunksPerTick, "maxChunksPerTick");
        this.maxBlockEntitiesPerTick = positive(maxBlockEntitiesPerTick, "maxBlockEntitiesPerTick");
        this.queryCooldownTicks = positive(queryCooldownTicks, "queryCooldownTicks");
        this.moveRefreshThresholdBlocks = positive(moveRefreshThresholdBlocks, "moveRefreshThresholdBlocks");
        this.dirtyDedupeCapacity = positive(dirtyDedupeCapacity, "dirtyDedupeCapacity");
        this.maxScannedPerQuery = positive(maxScannedPerQuery, "maxScannedPerQuery");
        this.refreshQueueCapacity = positive(refreshQueueCapacity, "refreshQueueCapacity");
        if (defaultRadius > maxPlayerRadius || maxPlayerRadius > maxAdminRadius) {
            throw new IllegalArgumentException(
                    "radius tiers must satisfy 0 < default <= player < admin, got "
                            + defaultRadius + " <= " + maxPlayerRadius + " <= " + maxAdminRadius);
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
        return value;
    }

    /**
     * 将请求半径截断到合法区间：非正数回退到默认半径；超过 actor 上限时按
     * 玩家/管理员上限截断。
     */
    public int clampRadius(int requested, boolean admin) {
        if (requested <= 0) {
            return defaultRadius;
        }
        int max = admin ? maxAdminRadius : maxPlayerRadius;
        return Math.min(requested, max);
    }

    /** 将请求的结果数量截断到 {@code [1, maxResults]}。 */
    public int clampMaxResults(int requested) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, maxResults);
    }

    public int defaultRadius() {
        return defaultRadius;
    }

    public int maxPlayerRadius() {
        return maxPlayerRadius;
    }

    public int maxAdminRadius() {
        return maxAdminRadius;
    }

    public int maxResults() {
        return maxResults;
    }

    public int maxChunksPerTick() {
        return maxChunksPerTick;
    }

    public int maxBlockEntitiesPerTick() {
        return maxBlockEntitiesPerTick;
    }

    public int queryCooldownTicks() {
        return queryCooldownTicks;
    }

    public int moveRefreshThresholdBlocks() {
        return moveRefreshThresholdBlocks;
    }

    public int dirtyDedupeCapacity() {
        return dirtyDedupeCapacity;
    }

    public int maxScannedPerQuery() {
        return maxScannedPerQuery;
    }

    public int refreshQueueCapacity() {
        return refreshQueueCapacity;
    }
}
