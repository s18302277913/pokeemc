package com.pokeemc.config;

import com.pokeemc.storage.discovery.StorageConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * PokeTrade 服务端配置（NeoForge {@code ModConfigSpec}，写入
 * {@code config/poketrade-server.toml}，随存档 serverconfig 持久化）。
 *
 * <p>默认值与既有硬编码常量保持一致（见 {@code StorageConfig} 的
 * {@code DEFAULT_*} 与 {@code PokeEMC.TRADE_SWEEP_INTERVAL_TICKS}），
 * 保证未改动配置时行为与升级前完全一致。所有取值在模组构造阶段静态装配，
 * 服务端读取时通过 {@link ModConfigSpec.ConfigValue#get()} 获取当前值。</p>
 *
 * <p>分组：{@code storage.*} 仓储发现/扫描预算；{@code trade.*} 玩家交易
 * 服务器总开关与过期扫描间隔、PKM 百分比手续费（预留，见
 * {@code TRADE_FEE_PERCENT} 注释）。</p>
 */
public final class PokeTradeConfig {

    private PokeTradeConfig() {
    }

    /** [CHANGED] 会话 #10：Shift 直接贩卖归属键。OFF=关闭；LEFT/RIGHT=对应侧 Shift 作为贩卖键，
     *  另一侧 Shift 保持原版语义（仓储取出/快移），实现键位隔离。 */
    public enum ShiftSellHand {
        OFF, LEFT, RIGHT
    }

    /** 完整服务端配置规格（模组构造阶段注册到 {@code ModLoadingContext}）。 */
    public static final ModConfigSpec SPEC;

    /** [CHANGED] 会话 #10：客户端配置规格（写入 {@code config/poketrade-client.toml}，
     *  仅物理客户端加载；服务端/GameTest/JUnit 下 {@code isLoaded()} 为 false，走守卫回退）。 */
    public static final ModConfigSpec CLIENT_SPEC;
    /** [CHANGED] 会话 #10：Shift 直接贩卖归属键（私有，仅经 {@link #shiftSellHand()} 读取）。 */
    private static final ModConfigSpec.EnumValue<ShiftSellHand> SHIFT_SELL_HAND;

    // ------------------------------------------------------------ storage 组

    /** 玩家查询默认半径（方块）。 */
    public static final ModConfigSpec.IntValue STORAGE_DEFAULT_RADIUS;
    /** 普通玩家可查询的最大半径（方块）。 */
    public static final ModConfigSpec.IntValue STORAGE_MAX_PLAYER_RADIUS;
    /** 管理员（有 manage 权限）可查询的最大半径（方块）。 */
    public static final ModConfigSpec.IntValue STORAGE_MAX_ADMIN_RADIUS;
    /** 单次查询返回结果的硬上限。 */
    public static final ModConfigSpec.IntValue STORAGE_MAX_RESULTS;
    /** 后台增量刷新时每个 tick 最多扫描的区块数。 */
    public static final ModConfigSpec.IntValue STORAGE_MAX_CHUNKS_PER_TICK;
    /** 后台增量刷新时每个 tick 最多检查的 block entity 数。 */
    public static final ModConfigSpec.IntValue STORAGE_MAX_BLOCK_ENTITIES_PER_TICK;
    /** 同一 actor 两次查询之间的最小间隔（tick），超限返回缓存。 */
    public static final ModConfigSpec.IntValue STORAGE_QUERY_COOLDOWN_TICKS;
    /** 玩家移动超过该格数（方块）时触发增量刷新。 */
    public static final ModConfigSpec.IntValue STORAGE_MOVE_REFRESH_THRESHOLD;
    /** 脏区块去重集合的容量上限。 */
    public static final ModConfigSpec.IntValue STORAGE_DIRTY_DEDUPE_CAPACITY;
    /** 单次同步查询最多扫描的仓储数，超过则结果标记为不完整。 */
    public static final ModConfigSpec.IntValue STORAGE_MAX_SCANNED_PER_QUERY;
    /** 后台增量刷新队列的容量上限，满时丢弃最旧任务并告警。 */
    public static final ModConfigSpec.IntValue STORAGE_REFRESH_QUEUE_CAPACITY;

    // ------------------------------------------------------------ exchange 组

    /** 交易所买入总开关（false → 全部买入被拒绝，客户端禁用买入按钮）。 */
    public static final ModConfigSpec.BooleanValue EXCHANGE_BUY_ENABLED;
    /** 交易所出售总开关（false → 背包/仓储出售被拒绝，客户端禁用出售按钮）。 */
    public static final ModConfigSpec.BooleanValue EXCHANGE_SELL_ENABLED;
    /** 买入价倍率（%），仅作用于官方价与 PKM 兜底价；覆盖价（大师球等）保持固定。 */
    public static final ModConfigSpec.IntValue EXCHANGE_BUY_MULTIPLIER_PERCENT;
    /** 出售价倍率（%）。 */
    public static final ModConfigSpec.IntValue EXCHANGE_SELL_MULTIPLIER_PERCENT;
    /** 贵重物品二次确认阈值（PKM）。 */
    public static final ModConfigSpec.LongValue EXCHANGE_SELL_CONFIRM_VALUE;

    // ------------------------------------------------------------ trade 组

    /** 服务器玩家交易总开关（false → 全体 DISABLED_BY_SERVER）。 */
    public static final ModConfigSpec.BooleanValue TRADE_ENABLED;
    /** 交易过期扫描间隔（tick），越小越及时但消耗更多服务端 tick。 */
    public static final ModConfigSpec.IntValue TRADE_SWEEP_INTERVAL_TICKS;
    /**
     * PKM 百分比手续费（%）。当前生产钱包（Pixelmon）不支持幂等操作，
     * 因此该值仅作为预留配置：仍为 0 时走零手续费；大于 0 时生产装配会
     * 记录警告并继续使用零手续费，待钱包幂等支持落地后启用。
     */
    public static final ModConfigSpec.IntValue TRADE_FEE_PERCENT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("storage");
        STORAGE_DEFAULT_RADIUS = builder
                .comment("玩家查询的默认半径（方块）", "默认: 32")
                .defineInRange("defaultRadius", 32, 1, 4096);
        STORAGE_MAX_PLAYER_RADIUS = builder
                .comment("普通玩家可查询的最大半径（方块）", "默认: 128")
                .defineInRange("maxPlayerRadius", 128, 1, 8192);
        STORAGE_MAX_ADMIN_RADIUS = builder
                .comment("管理员（有 manage 权限的 actor）可查询的最大半径（方块）", "默认: 256")
                .defineInRange("maxAdminRadius", 256, 1, 16384);
        STORAGE_MAX_RESULTS = builder
                .comment("单次查询返回结果的硬上限", "默认: 200")
                .defineInRange("maxResults", 200, 1, 10_000);
        STORAGE_MAX_CHUNKS_PER_TICK = builder
                .comment("后台增量刷新时每个 tick 最多扫描的区块数", "默认: 2")
                .defineInRange("maxChunksPerTick", 2, 1, 64);
        STORAGE_MAX_BLOCK_ENTITIES_PER_TICK = builder
                .comment("后台增量刷新时每个 tick 最多检查的 block entity 数", "默认: 512")
                .defineInRange("maxBlockEntitiesPerTick", 512, 1, 100_000);
        STORAGE_QUERY_COOLDOWN_TICKS = builder
                .comment("同一 actor 两次查询之间的最小间隔（tick），超限返回缓存", "默认: 10")
                .defineInRange("queryCooldownTicks", 10, 0, 6000);
        STORAGE_MOVE_REFRESH_THRESHOLD = builder
                .comment("玩家移动超过该格数（方块）时触发增量刷新", "默认: 4")
                .defineInRange("moveRefreshThresholdBlocks", 4, 0, 128);
        STORAGE_DIRTY_DEDUPE_CAPACITY = builder
                .comment("脏区块去重集合的容量上限", "默认: 10000")
                .defineInRange("dirtyDedupeCapacity", 10_000, 1, 1_000_000);
        STORAGE_MAX_SCANNED_PER_QUERY = builder
                .comment("单次同步查询最多扫描的仓储数，超过则结果标记为不完整", "默认: 2000")
                .defineInRange("maxScannedPerQuery", 2_000, 1, 100_000);
        STORAGE_REFRESH_QUEUE_CAPACITY = builder
                .comment("后台增量刷新队列的容量上限，满时丢弃最旧任务并告警", "默认: 8")
                .defineInRange("refreshQueueCapacity", 8, 1, 1024);
        builder.pop();

        builder.push("exchange");
        EXCHANGE_BUY_ENABLED = builder
                .comment("交易所买入总开关", "默认: true")
                .define("buyEnabled", true);
        EXCHANGE_SELL_ENABLED = builder
                .comment("交易所出售总开关", "默认: true")
                .define("sellEnabled", true);
        EXCHANGE_BUY_MULTIPLIER_PERCENT = builder
                .comment("买入价倍率（%），默认 100", "仅作用于官方价与 PKM 兜底价；覆盖价固定")
                .defineInRange("buyMultiplierPercent", 100, 1, 10000);
        EXCHANGE_SELL_MULTIPLIER_PERCENT = builder
                .comment("出售价倍率（%），默认 100")
                .defineInRange("sellMultiplierPercent", 100, 1, 10000);
        EXCHANGE_SELL_CONFIRM_VALUE = builder
                .comment("贵重物品二次确认阈值（PKM），默认 100000")
                .defineInRange("sellConfirmValue", 100_000L, 0L, Long.MAX_VALUE / 2);
        builder.pop();

        builder.push("trade");
        TRADE_ENABLED = builder
                .comment("服务器玩家交易总开关（false → 全体玩家显示 DISABLED_BY_SERVER）", "默认: true")
                .define("enabled", true);
        TRADE_SWEEP_INTERVAL_TICKS = builder
                .comment("交易过期扫描间隔（tick）", "默认: 20（约 1 秒）")
                .defineInRange("sweepIntervalTicks", 20, 1, 6000);
        TRADE_FEE_PERCENT = builder
                .comment("PKM 百分比手续费（%）。预留项：当前生产钱包不支持幂等，>0 时仅记录警告并继续零手续费", "默认: 0")
                .defineInRange("feePercent", 0, 0, 100);
        builder.pop();

        SPEC = builder.build();

        // [CHANGED] 会话 #10：客户端配置（Shift 直接贩卖归属键）。独立 builder 构建，
        // 与服务端 SPEC 同块初始化，避免 getter 引用顺序混乱。
        ModConfigSpec.Builder clientBuilder = new ModConfigSpec.Builder();
        SHIFT_SELL_HAND = clientBuilder
                .comment("Shift 直接贩卖归属键",
                        "OFF=关闭 / LEFT=左Shift贩卖(背包)、右Shift保持原版(仓储取出/快移) / RIGHT=反向",
                        "默认: LEFT")
                .defineEnum("shiftSellHand", ShiftSellHand.LEFT);
        CLIENT_SPEC = clientBuilder.build();
    }

    // ------------------------------------------------------------ 安全读取便捷方法

    /**
     * 组装仓储发现配置。配置未加载（如 JUnit 环境、GameTest 冷启动阶段）
     * 时回退到 {@link StorageConfig} 内置默认值，保证行为与升级前一致。
     */
    public static StorageConfig storageConfig() {
        if (!SPEC.isLoaded()) {
            return new StorageConfig();
        }
        return new StorageConfig(
                STORAGE_DEFAULT_RADIUS.get(),
                STORAGE_MAX_PLAYER_RADIUS.get(),
                STORAGE_MAX_ADMIN_RADIUS.get(),
                STORAGE_MAX_RESULTS.get(),
                STORAGE_MAX_CHUNKS_PER_TICK.get(),
                STORAGE_MAX_BLOCK_ENTITIES_PER_TICK.get(),
                STORAGE_QUERY_COOLDOWN_TICKS.get(),
                STORAGE_MOVE_REFRESH_THRESHOLD.get(),
                STORAGE_DIRTY_DEDUPE_CAPACITY.get(),
                STORAGE_MAX_SCANNED_PER_QUERY.get(),
                STORAGE_REFRESH_QUEUE_CAPACITY.get());
    }

    /** 交易所买入总开关（未加载时回退默认 true）。 */
    public static boolean exchangeBuyEnabled() {
        return !SPEC.isLoaded() || EXCHANGE_BUY_ENABLED.get();
    }

    /** 交易所出售总开关（未加载时回退默认 true）。 */
    public static boolean exchangeSellEnabled() {
        return !SPEC.isLoaded() || EXCHANGE_SELL_ENABLED.get();
    }

    /** 买入价倍率（%），未加载时回退默认 100。 */
    public static int exchangeBuyMultiplierPercent() {
        return SPEC.isLoaded() ? EXCHANGE_BUY_MULTIPLIER_PERCENT.get() : 100;
    }

    /** 出售价倍率（%），未加载时回退默认 100。 */
    public static int exchangeSellMultiplierPercent() {
        return SPEC.isLoaded() ? EXCHANGE_SELL_MULTIPLIER_PERCENT.get() : 100;
    }

    /** 贵重物品二次确认阈值（PKM），未加载时回退默认 100000。 */
    public static long exchangeSellConfirmValue() {
        return SPEC.isLoaded() ? EXCHANGE_SELL_CONFIRM_VALUE.get() : 100_000L;
    }

    /** 玩家交易总开关（未加载时回退默认 {@code true}）。 */
    public static boolean tradeEnabled() {
        return !SPEC.isLoaded() || TRADE_ENABLED.get();
    }

    /** 交易过期扫描间隔（tick），未加载时回退默认 20。 */
    public static int sweepIntervalTicks() {
        return SPEC.isLoaded() ? TRADE_SWEEP_INTERVAL_TICKS.get() : 20;
    }

    /** PKM 百分比手续费（%），未加载时回退默认 0。 */
    public static int feePercent() {
        return SPEC.isLoaded() ? TRADE_FEE_PERCENT.get() : 0;
    }

    /** [CHANGED] 会话 #10：Shift 直接贩卖归属键。客户端 spec 未加载（服务端/GameTest/JUnit）
     *  时回退默认 {@link ShiftSellHand#LEFT}。 */
    public static ShiftSellHand shiftSellHand() {
        return CLIENT_SPEC.isLoaded() ? SHIFT_SELL_HAND.get() : ShiftSellHand.LEFT;
    }
}
