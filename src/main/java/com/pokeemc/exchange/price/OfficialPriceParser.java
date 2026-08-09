package com.pokeemc.exchange.price;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pokeemc.PokeEMC;
import com.poketrade.api.TradeItemId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析 Pixelmon shopkeeper 商店预设 JSON（官方双价来源）。
 *
 * <p>实际结构（Pixelmon 商店预设把商品嵌套在互动结果里）：
 * {@code interactions.values[].interactions[].results.value[].items[]}，
 * 每项 {@code { item: {id, count, components?}, buyPrice: double, sellPrice: double }}。</p>
 *
 * <p>仅当 buy/sell 至少一个 &gt; 0 时收录；负数按 0 处理，与 {@code PriceQuote} 的非负契约对齐；
 * 价格以 double 原样保留，由 {@link ExchangePriceService} 负责「×10 + long 精确换算」。
 * 同一物品的多个组件变体（如不同精灵球）只保留首个价格：先收录者优先，避免覆盖。</p>
 */
public final class OfficialPriceParser {

    /** 官方原始双价（double 精度，尚未乘倍率）。 */
    public record DoublePrice(double buy, double sell) {
    }

    private OfficialPriceParser() {
    }

    public static Map<TradeItemId, DoublePrice> parse(JsonElement root) {
        Map<TradeItemId, DoublePrice> out = new LinkedHashMap<>();
        if (root == null || !root.isJsonObject()) {
            return out;
        }
        JsonElement interactions = root.getAsJsonObject().get("interactions");
        if (interactions == null || !interactions.isJsonObject()) {
            return out;
        }
        JsonElement values = interactions.getAsJsonObject().get("values");
        if (values == null || !values.isJsonArray()) {
            return out;
        }
        for (JsonElement groupEl : values.getAsJsonArray()) {
            if (!groupEl.isJsonObject()) {
                continue;
            }
            JsonElement groupInteractions = groupEl.getAsJsonObject().get("interactions");
            if (groupInteractions == null || !groupInteractions.isJsonArray()) {
                continue;
            }
            for (JsonElement interactionEl : groupInteractions.getAsJsonArray()) {
                if (!interactionEl.isJsonObject()) {
                    continue;
                }
                JsonElement results = interactionEl.getAsJsonObject().get("results");
                if (results == null || !results.isJsonObject()) {
                    continue;
                }
                JsonElement resultValues = results.getAsJsonObject().get("value");
                if (resultValues == null || !resultValues.isJsonArray()) {
                    continue;
                }
                for (JsonElement resultEl : resultValues.getAsJsonArray()) {
                    if (!resultEl.isJsonObject()) {
                        continue;
                    }
                    JsonElement items = resultEl.getAsJsonObject().get("items");
                    if (items == null || !items.isJsonArray()) {
                        continue;
                    }
                    for (JsonElement itemEl : items.getAsJsonArray()) {
                        parseItem(itemEl, out);
                    }
                }
            }
        }
        return out;
    }

    private static void parseItem(JsonElement el, Map<TradeItemId, DoublePrice> out) {
        if (!el.isJsonObject()) {
            return;
        }
        JsonObject itemObj = el.getAsJsonObject();
        JsonObject item = itemObj.has("item") && itemObj.get("item").isJsonObject()
                ? itemObj.getAsJsonObject("item") : null;
        if (item == null || !item.has("id") || !item.get("id").isJsonPrimitive()) {
            return;
        }
        String id = item.get("id").getAsString();
        TradeItemId tradeId;
        try {
            tradeId = TradeItemId.parse(id);
        } catch (IllegalArgumentException e) {
            PokeEMC.LOGGER.debug("PokeEMC: skip invalid item id in shopkeeper json: {}", id);
            return;
        }
        // 负数 clamp 为 0，避免穿透到 PriceQuote 的非负校验
        double buy = Math.max(0.0, numberOrZero(itemObj, "buyPrice"));
        double sell = Math.max(0.0, numberOrZero(itemObj, "sellPrice"));
        if (buy <= 0 && sell <= 0) {
            return; // 无有效价格，禁止交易
        }
        out.putIfAbsent(tradeId, new DoublePrice(buy, sell));
    }

    private static double numberOrZero(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return 0.0;
        }
        return el.getAsDouble();
    }
}
