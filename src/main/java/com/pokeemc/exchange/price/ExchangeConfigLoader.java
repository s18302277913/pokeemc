package com.pokeemc.exchange.price;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.pokeemc.PokeEMC;
import com.pokeemc.exchange.market.SellRules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Map;

/**
 * 交易所配置数据包监听（{@code data/poketrade/exchange/}）：
 * 读取 prices.json（覆盖价）与 sell_rules.json（出售规则），重载后重建价格目录。
 */
public class ExchangeConfigLoader extends SimpleJsonResourceReloadListener {

    public static final ExchangeConfigLoader INSTANCE = new ExchangeConfigLoader();

    private ExchangeConfigLoader() {
        super(new Gson(), "poketrade/exchange");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        resources.forEach((path, element) -> {
            // SimpleJsonResourceReloadListener 的 key 是相对目录路径：根级文件为 "prices.json"、
            // 子目录文件为 "sub/prices.json"。仅匹配末尾文件名（不要求前导 /），
            // 否则标准位置的 data/<ns>/poketrade/exchange/prices.json 会被静默跳过。
            String p = path.getPath();
            if (p.endsWith("/prices.json") || p.equals("prices.json")) {
                PriceOverrides.applyBuiltIn(element);
            } else if (p.endsWith("/sell_rules.json") || p.equals("sell_rules.json")) {
                SellRules.apply(SellRules.parse(element));
            }
        });
        ExchangePriceService.forServer().rebuild();
        PokeEMC.LOGGER.info("PokeEMC: exchange configs reloaded");
    }
}
