package com.pokeemc.exchange.price;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.pokeemc.PokeEMC;
import com.poketrade.api.TradeItemId;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端数据包监听器：聚合 {@code data/pixelmon/npc/preset/shopkeeper/**}
 * 全部商店预设的官方双价。重载完成经 {@link ExchangePriceService#rebuild()} 重建目录。
 */
public class OfficialPriceLoader extends SimpleJsonResourceReloadListener {

    public static final OfficialPriceLoader INSTANCE = new OfficialPriceLoader();

    /** 官方原始双价快照（volatile 发布；数据包重载后替换，重载前为空）。 */
    private static volatile Map<TradeItemId, OfficialPriceParser.DoublePrice> prices = Map.of();

    /** 当前快照（不可变、保持收录顺序；首次数据包重载前为空）。 */
    public static Map<TradeItemId, OfficialPriceParser.DoublePrice> prices() {
        return prices;
    }

    private OfficialPriceLoader() {
        super(new Gson(), "pixelmon/npc/preset/shopkeeper");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<TradeItemId, OfficialPriceParser.DoublePrice> merged = new LinkedHashMap<>();
        for (JsonElement element : resources.values()) {
            for (Map.Entry<TradeItemId, OfficialPriceParser.DoublePrice> e
                    : OfficialPriceParser.parse(element).entrySet()) {
                merged.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        // 不可变快照发布：LinkedHashMap 仅保证文件内条目相对顺序（文件间顺序由资源合并决定，不确定）；
        // PriceCatalog 构造时会按分类/名称重排，目录 UI 不依赖此顺序。
        prices = Collections.unmodifiableMap(new LinkedHashMap<>(merged));
        PokeEMC.LOGGER.info("PokeEMC: synced {} official shop prices", merged.size());
        // 官方价快照已更新，重建交易所价格目录（覆盖价合并、防套利校验）
        ExchangePriceService.forServer().rebuild();
    }
}
