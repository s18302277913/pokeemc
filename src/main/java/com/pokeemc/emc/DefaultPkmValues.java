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

    /** 原版基础定价（基准燃料/材料） */
    public static final Map<String, Long> VANILLA_BASE = new LinkedHashMap<>();

    /** Pixelmon 核心物品定价 */
    public static final Map<String, Long> PIXELMON = new LinkedHashMap<>();

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

        // ===== Pixelmon 精灵球（按商店价格比例）=====
        put(PIXELMON, "pixelmon:poke_ball", 256);
        put(PIXELMON, "pixelmon:great_ball", 512);
        put(PIXELMON, "pixelmon:ultra_ball", 1024);
        put(PIXELMON, "pixelmon:master_ball", 32768);
        put(PIXELMON, "pixelmon:heal_ball", 384);
        put(PIXELMON, "pixelmon:quick_ball", 384);
        put(PIXELMON, "pixelmon:timer_ball", 384);
        put(PIXELMON, "pixelmon:premier_ball", 256);
        put(PIXELMON, "pixelmon:friend_ball", 768);
        put(PIXELMON, "pixelmon:love_ball", 768);
        put(PIXELMON, "pixelmon:lure_ball", 768);
        put(PIXELMON, "pixelmon:heavy_ball", 768);
        put(PIXELMON, "pixelmon:level_ball", 768);
        put(PIXELMON, "pixelmon:moon_ball", 768);
        put(PIXELMON, "pixelmon:dream_ball", 1536);
        put(PIXELMON, "pixelmon:beast_ball", 2048);
        put(PIXELMON, "pixelmon:dive_ball", 640);
        put(PIXELMON, "pixelmon:net_ball", 640);
        put(PIXELMON, "pixelmon:nest_ball", 640);
        put(PIXELMON, "pixelmon:repeat_ball", 640);
        put(PIXELMON, "pixelmon:fast_ball", 640);
        put(PIXELMON, "pixelmon:safari_ball", 512);
        put(PIXELMON, "pixelmon:sport_ball", 1024);
        put(PIXELMON, "pixelmon:cherish_ball", 2048);
        put(PIXELMON, "pixelmon:park_ball", 512);
        put(PIXELMON, "pixelmon:luxury_ball", 1024);
        put(PIXELMON, "pixelmon:dusk_ball", 640);
        put(PIXELMON, "pixelmon:ancient_poke_ball", 512);
        put(PIXELMON, "pixelmon:ancient_great_ball", 1024);
        put(PIXELMON, "pixelmon:ancient_ultra_ball", 2048);
        put(PIXELMON, "pixelmon:ancient_heavy_ball", 1536);

        // ===== Pixelmon 进化石 =====
        put(PIXELMON, "pixelmon:fire_stone", 2048);
        put(PIXELMON, "pixelmon:water_stone", 2048);
        put(PIXELMON, "pixelmon:thunder_stone", 2048);
        put(PIXELMON, "pixelmon:leaf_stone", 2048);
        put(PIXELMON, "pixelmon:moon_stone", 2048);
        put(PIXELMON, "pixelmon:sun_stone", 2048);
        put(PIXELMON, "pixelmon:ice_stone", 2048);
        put(PIXELMON, "pixelmon:shiny_stone", 2048);
        put(PIXELMON, "pixelmon:dusk_stone", 2048);
        put(PIXELMON, "pixelmon:dawn_stone", 2048);
        put(PIXELMON, "pixelmon:oval_stone", 1024);
        put(PIXELMON, "pixelmon:fire_gem", 768);
        put(PIXELMON, "pixelmon:water_gem", 768);
        put(PIXELMON, "pixelmon:link_cable", 2048);
        put(PIXELMON, "pixelmon:electirizer", 2048);
        put(PIXELMON, "pixelmon:magmarizer", 2048);
        put(PIXELMON, "pixelmon:king_s_rock", 2048);
        put(PIXELMON, "pixelmon:metal_coat", 2048);
        put(PIXELMON, "pixelmon:protector", 2048);
        put(PIXELMON, "pixelmon:razor_claw", 2048);
        put(PIXELMON, "pixelmon:razor_fang", 2048);
        put(PIXELMON, "pixelmon:dragon_scale", 2048);
        put(PIXELMON, "pixelmon:upgrade", 2048);
        put(PIXELMON, "pixelmon:deep_sea_scale", 1024);
        put(PIXELMON, "pixelmon:deep_sea_tooth", 1024);
        put(PIXELMON, "pixelmon:ever_stone", 1024);
        put(PIXELMON, "pixelmon:reaper_cloth", 2048);
        put(PIXELMON, "pixelmon:dubious_disc", 2048);
        put(PIXELMON, "pixelmon:whipped_dream", 2048);
        put(PIXELMON, "pixelmon:sachet", 2048);
        put(PIXELMON, "pixelmon:prism_scale", 2048);
        put(PIXELMON, "pixelmon:black_sludge", 1536);

        // ===== Pixelmon 药品/治疗 =====
        put(PIXELMON, "pixelmon:antidote", 128);
        put(PIXELMON, "pixelmon:awakening", 128);
        put(PIXELMON, "pixelmon:burn_heal", 128);
        put(PIXELMON, "pixelmon:ice_heal", 128);
        put(PIXELMON, "pixelmon:paralyze_heal", 128);
        put(PIXELMON, "pixelmon:full_heal", 384);
        put(PIXELMON, "pixelmon:potion", 256);
        put(PIXELMON, "pixelmon:super_potion", 512);
        put(PIXELMON, "pixelmon:hyper_potion", 1024);
        put(PIXELMON, "pixelmon:max_potion", 2048);
        put(PIXELMON, "pixelmon:full_restore", 4096);
        put(PIXELMON, "pixelmon:revive", 2048);
        put(PIXELMON, "pixelmon:max_revive", 8192);
        put(PIXELMON, "pixelmon:ether", 1024);
        put(PIXELMON, "pixelmon:max_ether", 4096);
        put(PIXELMON, "pixelmon:elixir", 2048);
        put(PIXELMON, "pixelmon:max_elixir", 8192);
        put(PIXELMON, "pixelmon:rare_candy", 8192);
        put(PIXELMON, "pixelmon:ability_capsule", 4096);
        put(PIXELMON, "pixelmon:ability_patch", 8192);

        // ===== Pixelmon 通用道具 =====
        put(PIXELMON, "pixelmon:amulet_coin", 4096);
        put(PIXELMON, "pixelmon:lucky_egg", 4096);
        put(PIXELMON, "pixelmon:exp_share", 2048);
        put(PIXELMON, "pixelmon:soothe_bell", 1024);
        put(PIXELMON, "pixelmon:choice_band", 3072);
        put(PIXELMON, "pixelmon:choice_scarf", 3072);
        put(PIXELMON, "pixelmon:choice_specs", 3072);
        put(PIXELMON, "pixelmon:assault_vest", 3072);
        put(PIXELMON, "pixelmon:focus_band", 2048);
        put(PIXELMON, "pixelmon:focus_sash", 2048);
        put(PIXELMON, "pixelmon:leftovers", 4096);
        put(PIXELMON, "pixelmon:life_orb", 4096);
        put(PIXELMON, "pixelmon:air_balloon", 2048);
        put(PIXELMON, "pixelmon:light_clay", 1024);
        put(PIXELMON, "pixelmon:rocky_helmet", 2048);
        put(PIXELMON, "pixelmon:weakness_policy", 3072);
        put(PIXELMON, "pixelmon:protective_pads", 1536);
        put(PIXELMON, "pixelmon:safety_goggles", 1536);
        put(PIXELMON, "pixelmon:loaded_dice", 1024);
        put(PIXELMON, "pixelmon:room_service", 1536);
        put(PIXELMON, "pixelmon:throat_spray", 1536);
        put(PIXELMON, "pixelmon:blunder_policy", 3072);
        put(PIXELMON, "pixelmon:razor_claw", 2048);

        // ===== Pixelmon Mega 石（统一较高价值）=====
        String[] megastones = {
                "abomasite", "absolite", "aerodactylite", "aggronite", "alakazite",
                "altarianite", "ampharosite", "audinite", "banettite", "beedrillite",
                "blastoisinite", "blazikenite", "cameruptite", "charizardite_x",
                "charizardite_y", "diancite", "galladite", "garchompite", "gardevoirite",
                "gengarite", "glalitite", "gyaradosite", "heracronite", "houndoominite",
                "kangaskhanite", "latiasite", "latiosite", "lopunnite", "lucarionite",
                "manectite", "mawilite", "medichamite", "metagrossite", "mewtwonite_x",
                "mewtwonite_y", "pinsirite", "sablenite", "salamencite", "sceptilite",
                "scizorite", "sharpedonite", "slowbronite", "steelixite", "swampertite",
                "tyranitarite", "venusaurite", "pidgeotite"
        };
        for (String stone : megastones) {
            put(PIXELMON, "pixelmon:" + stone, 8192);
        }

        // ===== Pixelmon 神兽专属道具（高价值）=====
        put(PIXELMON, "pixelmon:adamant_crystal", 16384);
        put(PIXELMON, "pixelmon:adamant_orb", 16384);
        put(PIXELMON, "pixelmon:griseous_orb", 16384);
        put(PIXELMON, "pixelmon:lustrous_orb", 16384);
        put(PIXELMON, "pixelmon:soul_dew", 16384);
        put(PIXELMON, "pixelmon:azelf_tooth", 4096);
        put(PIXELMON, "pixelmon:mesprit_tooth", 4096);
        put(PIXELMON, "pixelmon:uxie_tooth", 4096);
        put(PIXELMON, "pixelmon:alpha_shard", 2048);

        // ===== Pixelmon 常见矿石/材料 =====
        put(PIXELMON, "pixelmon:aluminum_ingot", 256);
        put(PIXELMON, "pixelmon:amethyst", 4096);
        put(PIXELMON, "pixelmon:sun_stone_shard", 512);
        put(PIXELMON, "pixelmon:moon_stone_shard", 512);
        put(PIXELMON, "pixelmon:fire_stone_shard", 512);
        put(PIXELMON, "pixelmon:water_stone_shard", 512);
        put(PIXELMON, "pixelmon:thunder_stone_shard", 512);
        put(PIXELMON, "pixelmon:leaf_stone_shard", 512);
        put(PIXELMON, "pixelmon:ice_stone_shard", 512);
        put(PIXELMON, "pixelmon:shiny_stone_shard", 512);
        put(PIXELMON, "pixelmon:dusk_stone_shard", 512);
        put(PIXELMON, "pixelmon:dawn_stone_shard", 512);
    }

    private static void put(Map<String, Long> map, String key, long value) {
        map.put(key, value);
    }

    private DefaultPkmValues() {}
}
