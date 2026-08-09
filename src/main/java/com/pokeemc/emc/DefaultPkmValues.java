package com.pokeemc.emc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内置默认 PKM 定价（宝可梦金钱）。
 * <p>
 * 定价依据：
 * - 原版基础物品沿用 ProjectE 惯例（煤炭 128、铁锭 256、金锭 2048、钻石 8192、红石 64 等）
 * - Pixelmon 物品按稀有度/获取难度定价：精灵球、进化石、药品、道具、Mega 石等
 * <p>
 * 这些值会被数据包（data/pokeemc/pkm/*.json）覆盖，也会被配方自动计算补充。
 */
public final class DefaultPkmValues {

    /** 原版基础定价（基准燃料/材料），仅在数据包加载前作为离线兜底。 */
    public static final Map<String, Long> VANILLA_BASE = new LinkedHashMap<>();

    // [REMOVED] PIXELMON 映射：缺陷 #8 死代码（全工程无人读取），与数据包
    // pixelmon.json 权威值冲突（如 master_ball 32768 vs 5,000,000）。已按用户决策
    // "数据包为准"删除；Pixelmon 物品定价统一由 data/poketrade/pkm/pixelmon.json 维护。

    static {
        // ===== 原版基准（参考 ProjectE 1.1.0 常用值）=====
        put(VANILLA_BASE, "minecraft:cobblestone", 1);
        put(VANILLA_BASE, "minecraft:stone", 1);
        put(VANILLA_BASE, "minecraft:dirt", 1);
        put(VANILLA_BASE, "minecraft:sand", 1);
        put(VANILLA_BASE, "minecraft:gravel", 1);
        put(VANILLA_BASE, "minecraft:oak_log", 32);
        put(VANILLA_BASE, "minecraft:oak_planks", 8);
        put(VANILLA_BASE, "minecraft:stick", 4);
        put(VANILLA_BASE, "minecraft:coal", 128);
        put(VANILLA_BASE, "minecraft:charcoal", 128);
        put(VANILLA_BASE, "minecraft:iron_ingot", 256);
        put(VANILLA_BASE, "minecraft:gold_ingot", 2048);
        put(VANILLA_BASE, "minecraft:diamond", 8192);
        put(VANILLA_BASE, "minecraft:emerald", 16384);
        put(VANILLA_BASE, "minecraft:redstone", 64);
        put(VANILLA_BASE, "minecraft:lapis_lazuli", 864);
        put(VANILLA_BASE, "minecraft:quartz", 256);
        put(VANILLA_BASE, "minecraft:netherite_ingot", 73728);
        put(VANILLA_BASE, "minecraft:flint", 4);
        put(VANILLA_BASE, "minecraft:iron_nugget", 28);
        put(VANILLA_BASE, "minecraft:gold_nugget", 227);
        put(VANILLA_BASE, "minecraft:obsidian", 64);
        put(VANILLA_BASE, "minecraft:blaze_rod", 1536);
        put(VANILLA_BASE, "minecraft:blaze_powder", 768);
        put(VANILLA_BASE, "minecraft:ender_pearl", 1024);
        put(VANILLA_BASE, "minecraft:ghast_tear", 4096);
        put(VANILLA_BASE, "minecraft:wheat", 24);
        put(VANILLA_BASE, "minecraft:apple", 64);
        put(VANILLA_BASE, "minecraft:bone", 96);
        put(VANILLA_BASE, "minecraft:gunpowder", 192);
        put(VANILLA_BASE, "minecraft:string", 12);
        put(VANILLA_BASE, "minecraft:leather", 64);
        put(VANILLA_BASE, "minecraft:feather", 16);
        put(VANILLA_BASE, "minecraft:slime_ball", 32);
        put(VANILLA_BASE, "minecraft:nether_star", 139264);
        put(VANILLA_BASE, "minecraft:shulker_shell", 2048);
        put(VANILLA_BASE, "minecraft:prismarine_shard", 64);
        put(VANILLA_BASE, "minecraft:nautilus_shell", 1024);
        put(VANILLA_BASE, "minecraft:heart_of_the_sea", 8192);
        // [REMOVED] 全部 PIXELMON 定价条目（缺陷 #8）：死代码，已按用户决策删除。
        // Pixelmon 物品定价统一由 data/poketrade/pkm/pixelmon.json 数据包维护。
    }

    private static void put(Map<String, Long> map, String key, long value) {
        map.put(key, value);
    }

    private DefaultPkmValues() {}
}
