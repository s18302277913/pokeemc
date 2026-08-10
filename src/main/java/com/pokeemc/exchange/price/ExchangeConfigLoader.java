package com.pokeemc.exchange.price;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pokeemc.PokeEMC;
import com.pokeemc.exchange.market.SellRules;
import com.poketrade.api.TradeItemId;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交易所配置数据包监听（{@code data/poketrade/exchange/}）：
 * 读取 prices.json（覆盖价）、sell_rules.json（出售规则）与 categories.json（分类覆盖），重载后重建价格目录。
 */
public class ExchangeConfigLoader extends SimpleJsonResourceReloadListener {

    public static final ExchangeConfigLoader INSTANCE = new ExchangeConfigLoader();

    private ExchangeConfigLoader() {
        // [CHANGED] 会话 #23：目录参数必须是「exchange」——SimpleJsonResourceReloadListener
        // 自动按各命名空间扫描 data/<ns>/<directory>/，若写成 "poketrade/exchange" 会找
        // data/<ns>/poketrade/exchange/（比实际多一层目录）→ resources 恒空，
        // prices.json（数据包覆盖）/sell_rules.json/categories.json 全部静默失效。
        super(new Gson(), "exchange");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        resources.forEach((path, element) -> {
            // 只处理本模组命名空间的 exchange 数据，避免误吃其他模组的 data/<ns>/exchange/。
            if (!"poketrade".equals(path.getNamespace())) {
                return;
            }
            // SimpleJsonResourceReloadListener 经 FileToIdConverter 把 data/poketrade/exchange/<file>.json
            // 映射为 key poketrade:<file>（去目录前缀与 .json 后缀），故 getPath() 为 "prices"/"sell_rules"/"categories"。
            String p = path.getPath();
            if (p.endsWith("/prices") || p.equals("prices")) {
                PriceOverrides.applyBuiltIn(element);
            } else if (p.endsWith("/sell_rules") || p.equals("sell_rules")) {
                SellRules.apply(SellRules.parse(element));
            } else if (p.endsWith("/categories") || p.equals("categories")) {
                // [CHANGED] 会话 #16：数据驱动的物品→分类覆盖（修复「分类: unknown」+ 分类种类扩展）
                ExchangePriceService.applyCategoryOverrides(parseCategories(element));
            }
        });
        ExchangePriceService.forServer().rebuild();
        PokeEMC.LOGGER.info("PokeEMC: exchange configs reloaded");
    }

    /**
     * 解析 categories.json（{@code {"categories":{<itemId>:<categoryKey>}}}）。
     * itemId 支持球类 `#` 编码（如 pixelmon:poke_ball#master_ball）；非法/空键跳过。
     */
    static Map<TradeItemId, String> parseCategories(JsonElement root) {
        Map<TradeItemId, String> out = new LinkedHashMap<>();
        if (root != null && root.isJsonObject()) {
            JsonElement cats = root.getAsJsonObject().get("categories");
            if (cats != null && cats.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : cats.getAsJsonObject().entrySet()) {
                    if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString()) {
                        continue;
                    }
                    String category = e.getValue().getAsString();
                    if (category.isBlank()) {
                        continue;
                    }
                    try {
                        out.put(TradeItemId.parse(e.getKey()), category);
                    } catch (IllegalArgumentException ex) {
                        PokeEMC.LOGGER.debug("PokeEMC: skip invalid category item id: {}", e.getKey());
                    }
                }
            }
        }
        return out;
    }
}
