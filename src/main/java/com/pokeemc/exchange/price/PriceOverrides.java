package com.pokeemc.exchange.price;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pokeemc.PokeEMC;
import com.poketrade.api.TradeItemId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交易所覆盖价数据包（{@code data/poketrade/exchange/prices.json}）。
 *
 * <p>大师球购买价固定为 {@value #MASTER_BALL_BUY_PRICE} PKM（规格硬规则）：数据包未给出时自动注入默认值；
 * 给出且 buy 不等于固定值时抛出 {@link IllegalStateException} 报告配置错误（不静默回退）。
 * <b>卖出价尊重数据包定义</b>（由 {@code sellPrice} 决定，默认 0 = 不回收）；不再强制归零，
 * 保证「数据包已定义价格、玩家即可卖入转化桌」（Bug #3 修复）。
 * 解析规则与 {@link OfficialPriceParser} 一致：负数 clamp 为 0、非法 id 跳过、双零条目忽略。</p>
 */
public final class PriceOverrides {

    /** 大师球固定购买价。 */
    public static final long MASTER_BALL_BUY_PRICE = 5_000_000L;

    /**
     * 大师球物品 id（覆盖价硬校验键）。
     * [CHANGED] 会话 #14：Pixelmon 球类共用注册键 pixelmon:poke_ball，球种由
     * PokeBall DataComponent 区分；覆盖价键改为球种感知编码 pixelmon:poke_ball#master_ball，
     * 与仓储/目录 itemId 对齐（旧幽灵键 pixelmon:master_ball 注册表不存在，校验永不触发）。
     */
    private static final TradeItemId MASTER_BALL = TradeItemId.parse("pixelmon:poke_ball#master_ball");

    /** 当前覆盖价快照（数据包重载后由 ExchangePriceService 重建；模块初始化时读取内置默认）。 */
    private static volatile Map<TradeItemId, OverridePrice> loaded = Map.of();

    /** 覆盖价：buy/sell 为最终价（不再乘倍率）。 */
    public record OverridePrice(long buy, long sell) {
        public OverridePrice {
            if (buy < 0 || sell < 0) {
                throw new IllegalArgumentException("prices must be >= 0");
            }
        }
    }

    private PriceOverrides() {
    }

    public static Map<TradeItemId, OverridePrice> parse(JsonElement root) {
        Map<TradeItemId, OverridePrice> out = new LinkedHashMap<>();
        if (root != null && root.isJsonObject()) {
            JsonElement items = root.getAsJsonObject().get("items");
            if (items != null && items.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : items.getAsJsonObject().entrySet()) {
                    TradeItemId id;
                    try {
                        id = TradeItemId.parse(e.getKey());
                    } catch (IllegalArgumentException ex) {
                        PokeEMC.LOGGER.debug("PokeEMC: skip invalid override item id: {}", e.getKey());
                        continue;
                    }
                    if (!e.getValue().isJsonObject()) {
                        continue; // 非对象条目忽略
                    }
                    JsonObject o = e.getValue().getAsJsonObject();
                    long buy = longOrZero(o, "buyPrice");
                    long sell = longOrZero(o, "sellPrice");
                    if (buy <= 0 && sell <= 0) {
                        continue;
                    }
                    out.put(id, new OverridePrice(buy, sell));
                }
            }
        }
        // 大师球：购买价固定（硬校验，防止配置打破经济）；卖出价尊重数据包 sellPrice
        //（[CHANGED] Bug #3：不再强制 sell=0——物品已在数据包定义价格就应能卖入转化桌）。
        OverridePrice mb = out.get(MASTER_BALL);
        if (mb == null) {
            // 数据包未给出大师球时注入默认（买 500 万，默认不回收）
            out.put(MASTER_BALL, new OverridePrice(MASTER_BALL_BUY_PRICE, 0L));
        } else if (mb.buy() != MASTER_BALL_BUY_PRICE) {
            throw new IllegalStateException(
                    "配置错误：大师球购买价必须为 " + MASTER_BALL_BUY_PRICE + "，实际 " + mb.buy());
        }
        // 否则 buy 已校验 == 固定值，sell 保持数据包原值（尊重作者配置，可设 0 关闭回收）
        // 保序发布（迭代顺序 = 收录顺序）
        return Collections.unmodifiableMap(out);
    }

    /** 当前覆盖价快照（数据包重载后由 ExchangePriceService 重建；模块初始化时读取内置默认）。 */
    public static Map<TradeItemId, OverridePrice> load() {
        return loaded;
    }

    /** 内置数据包默认值装载（首次启动、数据包重载前保证大师球固定价可用）。 */
    public static void applyBuiltIn(JsonElement root) {
        loaded = parse(root);
    }

    private static long longOrZero(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return 0L;
        }
        return Math.max(0L, el.getAsLong()); // 负数 clamp 为 0，对齐 OfficialPriceParser
    }
}
