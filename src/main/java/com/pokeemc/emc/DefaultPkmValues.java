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
        // 基础资源给手动值，让合成树（PkmRecipeCalculator）自动推导出全部可合成物品；
        // 掉落物/锻造等无合成配方物品也必须给手动值，否则永远无价。
        put(VANILLA_BASE, "minecraft:cobblestone", 1);
        put(VANILLA_BASE, "minecraft:stone", 1);
        put(VANILLA_BASE, "minecraft:dirt", 1);
        put(VANILLA_BASE, "minecraft:sand", 1);
        put(VANILLA_BASE, "minecraft:gravel", 1);
        put(VANILLA_BASE, "minecraft:oak_log", 32);
        put(VANILLA_BASE, "minecraft:stripped_oak_log", 32);
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

        // ===== [CHANGED] Bug B/C：扩充基础资源，让合成树覆盖全部木材/铜系/常见建材 =====
        // 木材全系（每种都是独立注册名，无配方可互相推导，必须逐一手动）
        String[] logs = {"spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry"};
        for (String wood : logs) {
            put(VANILLA_BASE, "minecraft:" + wood + "_log", 32);
            put(VANILLA_BASE, "minecraft:" + wood + "_planks", 8);
            put(VANILLA_BASE, "minecraft:stripped_" + wood + "_log", 32);
        }
        put(VANILLA_BASE, "minecraft:crimson_stem", 32);
        put(VANILLA_BASE, "minecraft:warped_stem", 32);
        put(VANILLA_BASE, "minecraft:crimson_planks", 8);
        put(VANILLA_BASE, "minecraft:warped_planks", 8);
        put(VANILLA_BASE, "minecraft:stripped_crimson_stem", 32);
        put(VANILLA_BASE, "minecraft:stripped_warped_stem", 32);
        put(VANILLA_BASE, "minecraft:bamboo", 16);
        put(VANILLA_BASE, "minecraft:bamboo_block", 64);
        // 基础石材（可推导出石砖/深板岩砖/凝灰岩砖等）
        put(VANILLA_BASE, "minecraft:deepslate", 1);
        put(VANILLA_BASE, "minecraft:cobbled_deepslate", 1);
        put(VANILLA_BASE, "minecraft:granite", 1);
        put(VANILLA_BASE, "minecraft:diorite", 1);
        put(VANILLA_BASE, "minecraft:andesite", 1);
        put(VANILLA_BASE, "minecraft:tuff", 1);
        put(VANILLA_BASE, "minecraft:calcite", 1);
        put(VANILLA_BASE, "minecraft:dripstone_block", 1);
        put(VANILLA_BASE, "minecraft:basalt", 1);
        put(VANILLA_BASE, "minecraft:smooth_basalt", 1);
        put(VANILLA_BASE, "minecraft:blackstone", 1);
        put(VANILLA_BASE, "minecraft:netherrack", 1);
        put(VANILLA_BASE, "minecraft:end_stone", 1);
        // 金属与矿物（原矿经熔炼反向传播自动有价，无需手动）
        put(VANILLA_BASE, "minecraft:copper_ingot", 512);
        put(VANILLA_BASE, "minecraft:amethyst_shard", 32);
        put(VANILLA_BASE, "minecraft:clay_ball", 16);
        put(VANILLA_BASE, "minecraft:glowstone_dust", 768);
        // 常见掉落/作物（无合成或合成无法回推的基础原料）
        put(VANILLA_BASE, "minecraft:sugar_cane", 8);
        put(VANILLA_BASE, "minecraft:kelp", 8);
        put(VANILLA_BASE, "minecraft:honeycomb", 32);
        put(VANILLA_BASE, "minecraft:honey_bottle", 128);
        put(VANILLA_BASE, "minecraft:glow_berries", 16);
        put(VANILLA_BASE, "minecraft:sweet_berries", 8);
        put(VANILLA_BASE, "minecraft:rotten_flesh", 16);
        put(VANILLA_BASE, "minecraft:spider_eye", 64);
        put(VANILLA_BASE, "minecraft:egg", 16);
        put(VANILLA_BASE, "minecraft:rabbit_hide", 16);
        put(VANILLA_BASE, "minecraft:phantom_membrane", 128);
        put(VANILLA_BASE, "minecraft:echo_shard", 1024);
        put(VANILLA_BASE, "minecraft:ink_sac", 16);
        put(VANILLA_BASE, "minecraft:glow_ink_sac", 128);
        put(VANILLA_BASE, "minecraft:cocoa_beans", 16);
        put(VANILLA_BASE, "minecraft:cactus", 8);
        put(VANILLA_BASE, "minecraft:melon_slice", 8);
        put(VANILLA_BASE, "minecraft:pumpkin", 16);
        put(VANILLA_BASE, "minecraft:carrot", 16);
        put(VANILLA_BASE, "minecraft:potato", 16);
        put(VANILLA_BASE, "minecraft:beetroot", 16);
        put(VANILLA_BASE, "minecraft:nether_wart", 24);

        // ===== [CHANGED] Bug C：1.21 / 1.21.1 新增原版道具手动定价 =====
        // 以下物品无法经合成树推导（Breeze/试炼密室/犰狳掉落、锻造台产出、钥匙），
        // 不给手动值则永久无价、不出现在交易所目录。
        put(VANILLA_BASE, "minecraft:breeze_rod", 2048);           // Breeze 掉落（烈焰棒同量级）
        put(VANILLA_BASE, "minecraft:wind_charge", 512);           // 风弹（=breeze_rod/4，配方推导兜底手动）
        put(VANILLA_BASE, "minecraft:heavy_core", 16384);          // Vault 稀有掉落（沉重核）
        put(VANILLA_BASE, "minecraft:mace", 18432);                // 沉重之锤（≈breeze_rod+heavy_core）
        put(VANILLA_BASE, "minecraft:armadillo_scute", 64);        // 犰狳掉落（鳞甲）
        put(VANILLA_BASE, "minecraft:wolf_armor", 128);            // 锻造台产出（addition 为鳞甲，base 无价无法回推）
        put(VANILLA_BASE, "minecraft:trial_key", 4096);            // 试炼密室钥匙
        put(VANILLA_BASE, "minecraft:ominous_trial_key", 8192);    // 不祥试炼钥匙
        put(VANILLA_BASE, "minecraft:ominous_bottle", 64);         // 袭击队长掉落（不祥之瓶）
        // wind_charge / mace / copper 系 / 凝灰岩砖系：配方依赖的风弹/铜锭/凝灰岩已有价后自动推导
        // [REMOVED] 全部 PIXELMON 定价条目（缺陷 #8）：死代码，已按用户决策删除。
        // Pixelmon 物品定价统一由 data/poketrade/pkm/pixelmon.json 数据包维护。
    }

    private static void put(Map<String, Long> map, String key, long value) {
        map.put(key, value);
    }

    private DefaultPkmValues() {}
}
