package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.exchange.history.SalesHistory;
import com.pokeemc.exchange.market.SellRules;
import com.pokeemc.exchange.price.ExchangePriceService;
import com.pokeemc.storage.adapter.PokeballIdentity;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.price.PriceCatalog;
import com.poketrade.api.price.PriceCatalogEntry;
import com.poketrade.api.price.PriceSort;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Map;

/**
 * 交易所目录：客户端请求 → 服务端全量下发（条目精简：displayName 由客户端从注册表本地取，
 * 控制 payload 体积；上限 5000 条，覆盖官方商店 + PKM 兜底后的完整可交易清单，
 * 超出截断并靠搜索/分类在服务端预筛）。
 */
public final class ExchangeCatalogPacket {

    /** 浏览态目录上限（搜索态不截断；会话 #16 修复截断导致「有价无列表」）。 */
    public static final int MAX_BROWSE_ENTRIES = 5000;

    /** 目录条目线协议（不含 displayName，客户端用 BuiltInRegistries 解析本地化名）。 */
    public record EntryWire(String itemId, long buyPrice, long sellPrice,
                            String category, String rarity, String modId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, EntryWire> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, EntryWire::itemId,
                        ByteBufCodecs.VAR_LONG, EntryWire::buyPrice,
                        ByteBufCodecs.VAR_LONG, EntryWire::sellPrice,
                        ByteBufCodecs.STRING_UTF8, EntryWire::category,
                        ByteBufCodecs.STRING_UTF8, EntryWire::rarity,
                        ByteBufCodecs.STRING_UTF8, EntryWire::modId,
                        EntryWire::new);
    }

    public record Request(String sessionId, String search, String category,
                          PriceSort sort) implements CustomPacketPayload {
        public static final Type<Request> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "exchange_catalog_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Request::sessionId,
                        ByteBufCodecs.STRING_UTF8, Request::search,
                        ByteBufCodecs.STRING_UTF8, Request::category,
                        ByteBufCodecs.VAR_INT, r -> r.sort().ordinal(),
                        (sessionId, search, category, sortOrdinal) -> new Request(
                                sessionId, search, category, fromOrdinal(sortOrdinal)));

        /** 解码期序号边界防护：恶意/损坏的 ordinal 回退默认排序，避免抛数组越界断连。 */
        private static PriceSort fromOrdinal(int ordinal) {
            PriceSort[] values = PriceSort.values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PriceSort.CATEGORY;
        }

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /**
     * 目录响应：条目 + 分类 + 出售规则（二次确认阈值与黑白名单，供客户端出售预览预过滤）。
     *
     * <p>字段超过 {@link StreamCodec#composite} 的 6 组件上限，因此改用
     * {@link StreamCodec#of} 手动编解码（编解码顺序与 record 字段顺序一一对应）。</p>
     * <p>[CHANGED] 会话 #16：追加 {@code catalogVersion}（客户端陈旧检测/目录变更推送）与
     * {@code truncated}（浏览态超过上限 5000 时的截断标记）。</p>
     * <p>[NEW] 会话 #21-H：追加 {@code mode}（服务端当前目录模式 "LEARNING"/"FULL"，客户端仅作 UI 指示）。</p>
     * <p>[NEW] 会话 #21-H 修订：追加 {@code sellPrices}（全量出售价表，与浏览目录解耦）——
     * 学习模式目录只含「卖过」的物品，但出售预览必须覆盖全部有卖价的物品，
     * 否则新物品查不到卖价 → 学习模式卖不了东西。</p>
     */
    public record Response(String sessionId, List<EntryWire> entries, List<String> categories,
                           long requireConfirmValue, List<String> blockedItems, List<String> allowedItems,
                           boolean allowlistEnabled, boolean buyEnabled,
                           boolean sellEnabled, long catalogVersion,
                           boolean truncated, String mode,
                           Map<String, Long> sellPrices) implements CustomPacketPayload {
        public static final Type<Response> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "exchange_catalog_response"));
        private static final StreamCodec<ByteBuf, List<String>> STRING_LIST =
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());
        /** [NEW] 会话 #21-H 修订：全量出售价表（itemId → sellPrice>0）。手动 map codec。 */
        private static final StreamCodec<ByteBuf, Map<String, Long>> SELL_PRICE_MAP = StreamCodec.of(
                (buf, m) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, m.size());
                    for (Map.Entry<String, Long> e : m.entrySet()) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, e.getKey());
                        ByteBufCodecs.VAR_LONG.encode(buf, e.getValue());
                    }
                },
                buf -> {
                    int size = ByteBufCodecs.VAR_INT.decode(buf);
                    Map<String, Long> m = new java.util.HashMap<>(size);
                    for (int i = 0; i < size; i++) {
                        m.put(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf));
                    }
                    return m;
                });
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> STREAM_CODEC = StreamCodec.of(
                (buf, r) -> {
                    ByteBufCodecs.STRING_UTF8.encode(buf, r.sessionId());
                    EntryWire.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, r.entries());
                    STRING_LIST.encode(buf, r.categories());
                    ByteBufCodecs.VAR_LONG.encode(buf, r.requireConfirmValue());
                    STRING_LIST.encode(buf, r.blockedItems());
                    STRING_LIST.encode(buf, r.allowedItems());
                    ByteBufCodecs.BOOL.encode(buf, r.allowlistEnabled());
                    ByteBufCodecs.BOOL.encode(buf, r.buyEnabled());
                    ByteBufCodecs.BOOL.encode(buf, r.sellEnabled());
                    ByteBufCodecs.VAR_LONG.encode(buf, r.catalogVersion());
                    ByteBufCodecs.BOOL.encode(buf, r.truncated());
                    ByteBufCodecs.STRING_UTF8.encode(buf, r.mode());
                    SELL_PRICE_MAP.encode(buf, r.sellPrices());
                },
                buf -> new Response(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        EntryWire.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                        STRING_LIST.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf),
                        STRING_LIST.decode(buf),
                        STRING_LIST.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        SELL_PRICE_MAP.decode(buf)));

        @Override
        public Type<Response> type() {
            return TYPE;
        }
    }

    /**
     * 服务端执行：全目录排序 → 分类过滤 → 搜索过滤 → 截断 5000 条。
     *
     * <p>排序基于全目录（{@link PriceCatalog#sorted}），随后用流式过滤保持排序不变；
     * 分类过滤语义与 {@link PriceCatalog#filterByCategory} 一致（空分类归入 "unknown"）。</p>
     */
    public static void handle(Request packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            PriceCatalog catalog = ExchangePriceService.forServer().catalog();
            String search = packet.search() == null ? "" : packet.search().trim().toLowerCase();
            String category = packet.category() == null ? "" : packet.category().trim();
            PriceSort sort = packet.sort() == null ? PriceSort.CATEGORY : packet.sort();
            // [CHANGED] 会话 #21-H 修订：目录模式过滤。学习模式=个性化——只显示当前玩家
            // 「出售过」的物品（卖过才能买回），未出售的物品隐藏；每玩家一份历史（存档持久化）。
            // 全高亮模式=保留全部有价条目。过滤在应答时实时推导，价格/历史变更自动生效；
            // 分类列表同步过滤，避免出现「只剩不可买条目」的空分类。
            SellRules rules = SellRules.current();
            PokeTradeConfig.ExchangeMode mode = PokeTradeConfig.exchangeMode();
            boolean learning = mode == PokeTradeConfig.ExchangeMode.LEARNING;
            // 仅学习模式取该玩家出售历史（lambda 捕获需 effectively final）
            final java.util.Set<TradeItemId> sold;
            if (learning && context.player() instanceof ServerPlayer sp) {
                sold = SalesHistory.soldItems(sp.getUUID());
            } else {
                sold = java.util.Collections.emptySet();
            }
            java.util.function.Predicate<PriceCatalogEntry> visible =
                    learning ? e -> learningVisible(e, sold) : e -> true;
            List<String> categories = catalog.entries().stream()
                    .filter(visible)
                    .map(e -> e.category().isEmpty() ? "unknown" : e.category())
                    .distinct()
                    .sorted()
                    .toList();
            List<EntryWire> wiresAll = catalog.sorted(sort).stream()
                    .filter(visible)
                    .filter(e -> category.isBlank()
                            || (e.category().isEmpty() ? "unknown" : e.category()).equals(category))
                    .filter(e -> search.isBlank()
                            || e.quote().itemId().toString().toLowerCase().contains(search)
                            || e.category().toLowerCase().contains(search)
                            || itemDisplayName(e.quote().itemId()).contains(search))
                    // 双保险：服务端再次剔除 AIR / 注册表无法解析的条目，
                    // 保证客户端目录/购物车不渲染空气物品图标（即使上游 isObtainable 有漏）
                    .filter(e -> isRealItem(e.quote().itemId()))
                    .map(e -> new EntryWire(e.quote().itemId().toString(),
                            e.quote().buyPrice(), e.quote().sellPrice(),
                            e.category(), e.rarity(), e.modId()))
                    .toList();
            // [CHANGED] 会话 #16：浏览态（无搜索词）用上限控制 payload；搜索态已按关键词
            // 预筛且目录总量受浏览上限约束，不截断——避免「有价却因 limit 截断不出现在列表」。
            boolean truncated = false;
            List<EntryWire> wires = wiresAll;
            if (search.isBlank() && wiresAll.size() > MAX_BROWSE_ENTRIES) {
                wires = wiresAll.subList(0, MAX_BROWSE_ENTRIES);
                truncated = true;
            }
            // [NEW] 会话 #21-H 修订：全量出售价表（所有 sellPrice>0 条目，与学习过滤无关）——
            // 客户端出售预览/出售扫描用它，保证学习模式下也能出售任何有卖价的物品
            // （修复「学习模式卖不了东西」：此前 sellPrices 基于被过滤的浏览目录）。
            Map<String, Long> allSell = new java.util.LinkedHashMap<>();
            for (PriceCatalogEntry e : catalog.entries()) {
                if (e.quote().sellPrice() > 0) {
                    allSell.put(e.quote().itemId().toString(), e.quote().sellPrice());
                }
            }
            context.reply(new Response(packet.sessionId(), wires, categories,
                    rules.requireConfirmValue(),
                    rules.blacklist().stream().map(TradeItemId::toString).sorted().toList(),
                    rules.whitelist().stream().map(TradeItemId::toString).sorted().toList(),
                    rules.allowlistEnabled(),
                    PokeTradeConfig.exchangeBuyEnabled(),
                    PokeTradeConfig.exchangeSellEnabled(),
                    ExchangePriceService.forServer().catalogVersion(),
                    truncated,
                    mode.name(),
                    allSell));
        });
    }

    /**
     * [CHANGED] 会话 #21-H 修订：学习模式目录可见性——该玩家「出售过」该物品 且 当前有买入价。
     * 出售历史按玩家个性化（{@link SalesHistory}）；buyAvailable 保证列表里的条目都能买，
     * 卖过但当前仅可卖（buy=0）的不显示（点了也买不了）。纯函数，可单测。
     */
    static boolean learningVisible(PriceCatalogEntry e, java.util.Set<TradeItemId> sold) {
        return sold.contains(e.quote().itemId()) && e.quote().buyAvailable();
    }

    /**
     * 物品的服务端默认显示名（小写，用于搜索匹配）。解析失败或注册表查不到时返回空串，
     * 使该条目不因显示名命中而入选，但不影响 itemId/category 的既有匹配。
     * [CHANGED] 会话 #14：球类 itemId 含 '#'（pixelmon:poke_ball#master_ball），
     * ResourceLocation.parse 会抛异常 → 球类显示名搜索失配。改经
     * {@link PokeballIdentity#displayName} 还原球种名（大师球），中文名/球种名搜索可命中。
     */
    private static String itemDisplayName(TradeItemId id) {
        try {
            String name = PokeballIdentity.displayName(id.toString());
            return name == null ? "" : name.toLowerCase();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * 服务端二次门：真实可渲染物品（非 AIR 且注册表能解析到具体 Item 实例）。
     * 用于在发送前剔除 any 的残差，防止客户端出现空气槽位。
     * [CHANGED] 会话 #14：球类 itemId 含 '#'，ResourceLocation.parse 抛异常 → 球类条目
     * 被二次过滤剔除（大师球目录消失）。改经 {@link PokeballIdentity#decode} 校验：
     * 球类同时校验 base 注册表与具体球种（未知球种返回 null → 剔除），普通物品校验
     * 注册表；AIR/cave_air/void_air 的 Item 即为 AIR，decode 天然返回 null → 剔除。
     */
    private static boolean isRealItem(TradeItemId id) {
        try {
            ItemStack s = PokeballIdentity.decode(id.toString(), 1);
            return s != null && !s.isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 客户端投递：无条件缓存最新目录（无屏在途响应不再丢失），再交给当前屏幕消费。 */
    public static void handleResponse(Response packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // [CHANGED] 会话 #16：先写缓存——即使此刻没有交易所屏，开屏时也能立即消费最新目录。
            com.pokeemc.client.ClientCatalogCache.latest = packet;
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof com.pokeemc.client.ExchangeCatalogHost host) {
                host.onCatalogResponse(packet);
            }
        });
    }
}
