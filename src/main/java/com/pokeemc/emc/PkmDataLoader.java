package com.pokeemc.emc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.pokeemc.PokeEMC;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.Map;

/**
 * 从数据包 data/pokeemc/pkm/*.json 加载手工 PKM 定价。
 * <p>
 * JSON 格式：
 * <pre>
 * {
 *   "values": {
 *     "pixelmon:poke_ball": 256,
 *     "minecraft:coal": 128
 *   }
 * }
 * </pre>
 */
public class PkmDataLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public static final PkmDataLoader INSTANCE = new PkmDataLoader();

    private PkmDataLoader() {
        super(GSON, "pkm");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager resourceManager, ProfilerFiller profiler) {
        // 先清除配方计算值（保留手工值）
        PKMManager.clearComputed();
        int count = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject obj = value.getAsJsonObject();
            if (!obj.has("values") || !obj.get("values").isJsonObject()) {
                continue;
            }
            JsonObject values = obj.getAsJsonObject("values");
            for (Map.Entry<String, JsonElement> v : values.entrySet()) {
                ResourceLocation key = ResourceLocation.tryParse(v.getKey());
                if (key == null) {
                    LOGGER.warn("PokeEMC: invalid item id '{}' in {}", v.getKey(), entry.getKey());
                    continue;
                }
                long pkm = v.getValue().getAsLong();
                PKMManager.setManual(key, pkm);
                count++;
            }
        }
        PokeEMC.LOGGER.info("PokeEMC: loaded {} manual values from data packs (total {})", count, PKMManager.size());
    }
}
