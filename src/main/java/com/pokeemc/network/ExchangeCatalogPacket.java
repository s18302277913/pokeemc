package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.exchange.market.SellRules;
import com.pokeemc.exchange.price.ExchangePriceService;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.price.PriceCatalog;
import com.poketrade.api.price.PriceCatalogEntry;
import com.poketrade.api.price.PriceSort;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 交易所目录：客户端请求 → 服务端全量下发（条目精简：displayName 由客户端从注册表本地取，
 * 控制 payload 体积；上限 5000 条，覆盖官方商店 + PKM 兜底后的完整可交易清单，
 * 超出截断并靠搜索/分类在服务端预筛）。
 */
public final class ExchangeCatalogPacket {

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
     * <p>字段共 7 个，超过 {@link StreamCodec#composite} 的 6 组件上限，因此改用
     * {@link StreamCodec#of} 手动编解码（编解码顺序与 record 字段顺序一一对应）。</p>
     */
    public record Response(String sessionId, List<EntryWire> entries, List<String> categories,
                           long requireConfirmValue, List<String> blockedItems, List<String> allowedItems,
                           boolean allowlistEnabled, boolean buyEnabled,
                           boolean sellEnabled) implements CustomPacketPayload {
        public static final Type<Response> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "exchange_catalog_response"));
        private static final StreamCodec<ByteBuf, List<String>> STRING_LIST =
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());
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
                        ByteBufCodecs.BOOL.decode(buf)));

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
            List<String> categories = catalog.entries().stream()
                    .map(e -> e.category().isEmpty() ? "unknown" : e.category())
                    .distinct()
                    .sorted()
                    .toList();
            List<EntryWire> wires = catalog.sorted(sort).stream()
                    .filter(e -> category.isBlank()
                            || (e.category().isEmpty() ? "unknown" : e.category()).equals(category))
                    .filter(e -> search.isBlank()
                            || e.quote().itemId().toString().toLowerCase().contains(search)
                            || e.category().toLowerCase().contains(search)
                            || itemDisplayName(e.quote().itemId()).contains(search))
                    // 双保险：服务端再次剔除 AIR / 注册表无法解析的条目，
                    // 保证客户端目录/购物车不渲染空气物品图标（即使上游 isObtainable 有漏）
                    .filter(e -> isRealItem(e.quote().itemId()))
                    .limit(5000)
                    .map(e -> new EntryWire(e.quote().itemId().toString(),
                            e.quote().buyPrice(), e.quote().sellPrice(),
                            e.category(), e.rarity(), e.modId()))
                    .toList();
            SellRules rules = SellRules.current();
            context.reply(new Response(packet.sessionId(), wires, categories,
                    rules.requireConfirmValue(),
                    rules.blacklist().stream().map(TradeItemId::toString).sorted().toList(),
                    rules.whitelist().stream().map(TradeItemId::toString).sorted().toList(),
                    rules.allowlistEnabled(),
                    PokeTradeConfig.exchangeBuyEnabled(),
                    PokeTradeConfig.exchangeSellEnabled()));
        });
    }

    /**
     * 物品的服务端默认显示名（小写，用于搜索匹配）。解析失败或注册表查不到时返回空串，
     * 使该条目不因显示名命中而入选，但不影响 itemId/category 的既有匹配。
     */
    private static String itemDisplayName(TradeItemId id) {
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id.toString()));
            if (item == null || item == Items.AIR) {
                return "";
            }
            return item.getDefaultInstance().getHoverName().getString().toLowerCase();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * 服务端二次门：真实可渲染物品（非 AIR 且注册表能解析到具体 Item 实例）。
     * 用于在发送前剔除 any 的残差，防止客户端出现空气槽位。
     */
    private static boolean isRealItem(TradeItemId id) {
        try {
            ResourceLocation rl = ResourceLocation.parse(id.toString());
            String path = rl.getPath();
            if ("air".equals(path) || "cave_air".equals(path) || "void_air".equals(path)) {
                return false;
            }
            Item item = BuiltInRegistries.ITEM.get(rl);
            return item != null && item != Items.AIR;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 客户端投递：把目录响应交给当前屏幕（实现 {@link com.pokeemc.client.ExchangeCatalogHost}）。 */
    public static void handleResponse(Response packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen
                    instanceof com.pokeemc.client.ExchangeCatalogHost host) {
                host.onCatalogResponse(packet);
            }
        });
    }
}
