package com.pokeemc.exchange.price;

import com.pokeemc.PokeEMC;
import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.emc.PKMManager;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.price.PriceCatalog;
import com.poketrade.api.price.PriceCatalogEntry;
import com.poketrade.api.price.PriceQuote;
import com.poketrade.api.price.PriceSource;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 交易所价格服务（阶段 2 权威价格源）。
 *
 * <ul>
 *   <li>官方价：{@code buyPrice = round(officialBuy) × 10}，{@code sellPrice = round(officialSell) × 10}
 *       （规格经济规则：统一通胀倍率 10）；</li>
 *   <li>覆盖价：直接采用，不再乘倍率（大师球 500 万）；</li>
 *   <li>防套利：可买入价低于卖价（buy &gt; 0 且 buy &lt; sell）的条目（覆盖价或官方价）视为配置错误——
 *       覆盖价抛 {@link IllegalStateException} 拒绝加载，官方价剔除该条目并 warn 降级（防止整个目录崩溃）；
 *       官方价 ×10 溢出的条目同样剔除降级；sell-only 条目（buy=0，不可买入）合法，与 {@code PriceQuote#buyAvailable} 语义一致；</li>
 *   <li>全量金额 long 精确计算，乘法带溢出检查；</li>
 *   <li>服务端每次提交重新 {@link #quote}，客户端价格仅展示。</li>
 * </ul>
 */
public final class ExchangePriceService {

    public static final long BUY_MULTIPLIER = 10L;
    public static final long SELL_MULTIPLIER = 10L;

    private static volatile ExchangePriceService serverInstance;

    private final Map<TradeItemId, OfficialPriceParser.DoublePrice> official;
    private final Map<TradeItemId, PriceOverrides.OverridePrice> overrides;
    /** PKM（EMC 价值体系）兜底价：无官方/覆盖价的物品以 EMC 价值作为回收价。 */
    private final Map<TradeItemId, Long> pkm;
    /** 生产装配实例标记：rebuild 时重读最新静态快照（数据包重载即时生效）；测试实例用注入快照。 */
    private final boolean live;
    private volatile PriceCatalog catalog = PriceCatalog.empty();
    /** 目录重建时记录的 PKM 快照版本；catalog() 读取时检测变化自动重建（Bug A/B 修复）。 */
    private volatile long catalogPkmVersion = -1;

    /** 测试用构造：注入官方价与覆盖价（不读取全局静态快照）。 */
    public ExchangePriceService(
            Map<TradeItemId, OfficialPriceParser.DoublePrice> official,
            Map<TradeItemId, PriceOverrides.OverridePrice> overrides) {
        this(official, overrides, Map.of(), false);
    }

    /** 测试用构造：额外注入 PKM 兜底价（不读取全局静态快照）。 */
    public ExchangePriceService(
            Map<TradeItemId, OfficialPriceParser.DoublePrice> official,
            Map<TradeItemId, PriceOverrides.OverridePrice> overrides,
            Map<TradeItemId, Long> pkm) {
        this(official, overrides, pkm, false);
    }

    /** 生产装配（live=true 读取全局静态快照）或测试装配（live=false 用注入快照）；包私有供同包回归测试。 */
    ExchangePriceService(
            Map<TradeItemId, OfficialPriceParser.DoublePrice> official,
            Map<TradeItemId, PriceOverrides.OverridePrice> overrides,
            Map<TradeItemId, Long> pkm,
            boolean live) {
        this.official = Objects.requireNonNullElseGet(official, Map::of);
        this.overrides = Objects.requireNonNullElseGet(overrides, Map::of);
        this.pkm = Objects.requireNonNullElseGet(pkm, Map::of);
        this.live = live;
        rebuild();
    }

    /** 服务端生产装配：价格快照取自 {@link OfficialPriceLoader} 与覆盖价数据包。 */
    public static ExchangePriceService forServer() {
        ExchangePriceService current = serverInstance;
        if (current == null) {
            synchronized (ExchangePriceService.class) {
                current = serverInstance;
                if (current == null) {
                    current = new ExchangePriceService(
                            OfficialPriceLoader.prices(),
                            PriceOverrides.load(),
                            pkmFallback(),
                            true);
                    serverInstance = current;
                }
            }
        }
        return current;
    }

    /** 重建目录（数据包重载后由 {@link OfficialPriceLoader#apply} / {@link ExchangeConfigLoader#apply} 调用）。 */
    public void rebuild() {
        // 生产实例每次重建都读取最新静态快照，保证数据包重载即时生效；
        // 测试实例保持构造时注入的快照语义。
        Map<TradeItemId, OfficialPriceParser.DoublePrice> officialSrc = live ? OfficialPriceLoader.prices() : official;
        Map<TradeItemId, PriceOverrides.OverridePrice> overrideSrc = live ? PriceOverrides.load() : overrides;
        Map<TradeItemId, Long> pkmSrc = live ? pkmFallback() : pkm;
        Map<TradeItemId, PriceQuote> quotes = new LinkedHashMap<>();
        for (Map.Entry<TradeItemId, OfficialPriceParser.DoublePrice> e : officialSrc.entrySet()) {
            TradeItemId id = e.getKey();
            if (!isObtainable(id)) {
                continue;
            }
            PriceQuote q;
            try {
                q = scaledOfficial(id, e.getValue());
                q = new PriceQuote(id,
                        applyMultiplier(q.buyPrice(), PokeTradeConfig.exchangeBuyMultiplierPercent()),
                        applyMultiplier(q.sellPrice(), PokeTradeConfig.exchangeSellMultiplierPercent()),
                        q.source());
            } catch (ArithmeticException ex) {
                // 官方价 ×10 溢出（超出 long 范围）：剔除该条目并降级，避免整个目录重建失败
                PokeEMC.LOGGER.warn("PokeEMC: 官方价溢出，跳过条目 {}（超出 long 范围）", id);
                continue;
            }
            // 防套利（与覆盖价同规则）：可买入价低于卖价视为配置错误，剔除条目防止无限套利
            if (q.buyPrice() > 0 && q.sellPrice() > 0 && q.buyPrice() < q.sellPrice()) {
                PokeEMC.LOGGER.warn("PokeEMC: 官方价配置错误：{} 买价({})低于卖价({})，跳过该条目防止套利",
                        id, q.buyPrice(), q.sellPrice());
                continue;
            }
            quotes.put(id, q);
        }
        for (Map.Entry<TradeItemId, PriceOverrides.OverridePrice> e : overrideSrc.entrySet()) {
            TradeItemId id = e.getKey();
            if (!isObtainable(id)) {
                continue;
            }
            PriceOverrides.OverridePrice v = e.getValue();
            // 防套利：仅当可买入（buy > 0）且买价低于卖价时视为配置错误；
            // sell-only 覆盖（buy=0，不可买入）合法，与 Task 4 解析语义一致。
            if (v.buy() > 0 && v.sell() > 0 && v.buy() < v.sell()) {
                throw new IllegalStateException(
                        "覆盖价配置错误：物品 " + id + " 买价(" + v.buy() + ") 低于卖价(" + v.sell() + ")，存在套利空间");
            }
            quotes.put(id, new PriceQuote(id, v.buy(), v.sell(), PriceSource.OVERRIDE));
        }
        // PKM 兜底：官方/覆盖价缺口的物品以 EMC 价值同时作为买价与回收价
        // （买价=卖价，无套利空间），保证目录里每个有价物品都能买也能卖；
        // 有官方/覆盖价的物品不覆盖。
        for (Map.Entry<TradeItemId, Long> e : pkmSrc.entrySet()) {
            TradeItemId id = e.getKey();
            if (quotes.containsKey(id) || !isObtainable(id)) {
                continue;
            }
            long v = e.getValue();
            if (v <= 0) {
                continue;
            }
            long buy = applyMultiplier(v, PokeTradeConfig.exchangeBuyMultiplierPercent());
            long sell = applyMultiplier(v, PokeTradeConfig.exchangeSellMultiplierPercent());
            if (buy > 0 && sell > 0 && buy < sell) {
                PokeEMC.LOGGER.warn("PokeEMC: 倍率配置使 {} 买价({})低于卖价({})，跳过该条目防止套利",
                        id, buy, sell);
                continue;
            }
            quotes.put(id, new PriceQuote(id, buy, sell, PriceSource.PKM));
        }
        List<PriceCatalogEntry> entries = new ArrayList<>();
        for (PriceQuote q : quotes.values()) {
            entries.add(new PriceCatalogEntry(
                    q, categoryOf(q.itemId()), "", rarityOf(q.itemId()), q.itemId().namespace()));
        }
        this.catalog = new PriceCatalog(entries);
        this.catalogPkmVersion = PKMManager.version();
        PokeEMC.LOGGER.info("PokeEMC: exchange catalog rebuilt with {} entries", entries.size());
    }

    /**
     * 过滤条件：生存可获得、非空气、非命令专属、非刷怪蛋、非 OP 命令方块类。
     * 规则：
     * <ul>
     *   <li>注册表不存在或解析失败 → 剔除</li>
     *   <li>Items.AIR（含 minecraft:air / cave_air / void_air）→ 剔除</li>
     *   <li>刷怪蛋（path 以 "spawn_egg" 结尾，或 ForgedSpawnEggItem 子类）→ 剔除</li>
     *   <li>物品在任意 CreativeModeTab 中均不可见（既非 search，也未出现在任何分类标签里）
     *       且 Item#isEnabled 返回 false（或 features 里被配置禁用）→ 剔除</li>
     * </ul>
     * 以上所有判定仅基于静态注册表/物品属性，不依赖世界状态。
     */
    private static boolean isObtainable(TradeItemId id) {
        ResourceLocation rl = ResourceLocation.tryBuild(id.namespace(), id.path());
        if (rl == null) {
            return false;
        }
        Item item;
        try {
            item = BuiltInRegistries.ITEM.get(rl);
        } catch (LinkageError | RuntimeException e) {
            // 未就绪（测试环境 Bootstrap 未启动）时保守地保留该条目，由后续渲染再兜底
            return true;
        }
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return false;
        }
        String path = rl.getPath();
        // 空气类方块（洞穴/虚空空气只有 Block 存在，对应 Item 就是 AIR；这里按 path 兜底防止有别名直接进入）
        if ("air".equals(path) || "cave_air".equals(path) || "void_air".equals(path)) {
            return false;
        }
        // 刷怪蛋：通用命名 + 常见模组别名
        if (path.endsWith("_spawn_egg") || path.endsWith("spawn_egg")
                || "spawn_egg".equals(path) || "spawn_eggs".equals(path)) {
            return false;
        }
        // 命令方块 / 结构方块 / 屏障 / 光 / 调试棒 / 命令方块矿车（原版 creative-only），
        // 以及自定义 Item 子类继承自 SpawnEggItem（Forge/NeoForge 模组刷怪蛋）
        Class<?> itemClass = item.getClass();
        String className = itemClass.getName();
        if (className.contains("SpawnEgg") || className.contains("spawn_egg")
                || className.endsWith("ForgedSpawnEggItem")) {
            return false;
        }
        // 特征标签：启用特性判定——若物品在原版 VANILLA_SET 特征集中不可用则视为不可获得
        try {
            if (!item.isEnabled(net.minecraft.world.flag.FeatureFlags.VANILLA_SET)) {
                return false;
            }
        } catch (LinkageError | RuntimeException ignored) {
            // 老版本测试：VANILLA_SET 不存在则跳过该门
        }
        // 命令/结构类名称关键字命中（pixelmon/neoforge 扩展的命令方块同样剔除）
        if (path.contains("command_block") || path.contains("structure_block")
                || path.contains("jigsaw") || path.equals("barrier") || path.equals("light")
                || path.equals("debug_stick") || path.equals("command_block_minecart")
                || path.equals("chain_command_block") || path.equals("repeating_command_block")) {
            return false;
        }
        return true;
    }

    /** 官方双价 → 最终价（×10、long、溢出检查）。 */
    private static PriceQuote scaledOfficial(TradeItemId id, OfficialPriceParser.DoublePrice d) {
        long buy = scaled(d.buy(), BUY_MULTIPLIER);
        long sell = scaled(d.sell(), SELL_MULTIPLIER);
        return new PriceQuote(id, buy, sell, PriceSource.OFFICIAL);
    }

    /** PKM（EMC 价值体系）快照 → TradeItemId 兜底价（仅保留正向价值，非 TradeItemId 路径跳过）。 */
    static Map<TradeItemId, Long> pkmFallback() {
        Map<TradeItemId, Long> out = new LinkedHashMap<>();
        // [CHANGED] 官方 API：Object2LongMap 装箱 entrySet 已弃用，用原语遍历避免自动装箱
        for (Object2LongMap.Entry<ResourceLocation> e : PKMManager.snapshot().object2LongEntrySet()) {
            long v = e.getLongValue();
            if (v <= 0) {
                continue;
            }
            try {
                out.put(TradeItemId.parse(e.getKey().toString()), v);
            } catch (IllegalArgumentException ex) {
                // 非 TradeItemId 兼容路径（如含非法字符）的资源键跳过
            }
        }
        return out;
    }

    private static long scaled(double value, long multiplier) {
        long raw = Math.round(value);
        return Math.multiplyExact(raw, multiplier);
    }

    /** 应用配置倍率（%）：value × percent / 100，溢出抛 ArithmeticException。 */
    private static long applyMultiplier(long value, int percent) {
        if (percent == 100) {
            return value;
        }
        return Math.multiplyExact(value, percent) / 100;
    }

    private static String categoryOf(TradeItemId id) {
        ResourceLocation rl = ResourceLocation.tryBuild(id.namespace(), id.path());
        if (rl == null) {
            return "unknown";
        }
        try {
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return "unknown";
            }
            ItemStack stack = new ItemStack(item);
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                if (tab.getType() != CreativeModeTab.Type.CATEGORY || !tab.contains(stack)) {
                    continue;
                }
                String name = tab.getDisplayName().getString();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (LinkageError | RuntimeException e) {
            // 注册表未就绪（如纯 JVM 单测环境未执行 Bootstrap.bootStrap）时回退 unknown；
            // 服务端数据包重载路径注册表已就绪，不受影响。
            PokeEMC.LOGGER.debug("PokeEMC: category lookup failed for {}", id, e);
        }
        return "unknown";
    }

    private static String rarityOf(TradeItemId id) {
        return ""; // 稀有度默认留空；未来可由覆盖数据包扩展
    }

    public PriceCatalog catalog() {
        // [CHANGED] Bug A/B：生产实例的 PKM 快照可能晚于目录构建发生变化
        // （如合成树计算在 ServerStartedEvent 完成、数据包重载后配方驱动的新值）。
        // 读取前按版本号惰性重建，保证服务端 quote 与客户端目录永远覆盖最新有价物品；
        // 测试实例（live=false）快照固定，不参与版本检测。
        if (live && catalogPkmVersion != PKMManager.version()) {
            rebuild();
        }
        return catalog;
    }

    public Optional<PriceQuote> quote(TradeItemId itemId) {
        // [CHANGED] Bug A 修复：必须经 catalog() 触发 PKM 版本检测后再查价——
        // 服务端每次出售走 pricing.quote() 重新查价，若直接读 catalog 字段会绕过
        // 惰性重建，导致合成树补充的新值永远查不到（「有价却卖不了」）。
        return catalog().quoteOf(itemId);
    }
}
