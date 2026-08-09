package com.pokeemc.emc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug B/C 回归：内置默认定价必须覆盖无法经合成树推导的物品
 * （掉落物/锻造台产出/试炼密室钥匙/各系木材等），否则它们永远无价、
 * 不出现在交易所目录，也无法被 PkmRecipeCalculator 用作输入。
 */
class DefaultPkmValuesTest {

    @Test
    void new121ItemsHaveVanillaBasePrices() {
        // 1.21 / 1.21.1 新增原版道具：均为掉落/锻造来源，必须手动定价
        for (String id : List.of(
                "minecraft:breeze_rod",
                "minecraft:wind_charge",
                "minecraft:heavy_core",
                "minecraft:mace",
                "minecraft:wolf_armor",
                "minecraft:armadillo_scute",
                "minecraft:trial_key",
                "minecraft:ominous_trial_key",
                "minecraft:ominous_bottle")) {
            // wind_charge/mace 可由配方推导，此处仅校验 wind_charge 之外的掉落类有手动值
            assertNotNull(DefaultPkmValues.VANILLA_BASE.get(id),
                    id + " 缺少内置基础价（掉落/锻造来源无合成配方可推导）");
        }
    }

    @Test
    void allVanillaWoodsHaveLogAndPlanksBase() {
        // 每种木材都是独立注册名，无法互相推导，必须逐一手动定价
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia",
                "dark_oak", "mangrove", "cherry")) {
            assertPriced("minecraft:" + wood + "_log");
            assertPriced("minecraft:" + wood + "_planks");
        }
        assertPriced("minecraft:crimson_stem");
        assertPriced("minecraft:warped_stem");
        assertPriced("minecraft:crimson_planks");
        assertPriced("minecraft:warped_planks");
    }

    @Test
    void copperChainHasBase() {
        // 铜锭有价后：铜矿/原铜经熔炼反向传播、铜块/铜灯等经合成树自动推导
        assertPriced("minecraft:copper_ingot");
    }

    @Test
    void allBaseValuesArePositive() {
        assertFalse(DefaultPkmValues.VANILLA_BASE.isEmpty());
        DefaultPkmValues.VANILLA_BASE.forEach((id, value) ->
                assertTrue(value > 0, id + " 基础价必须为正，否则会被价格服务跳过"));
    }

    private static void assertPriced(String id) {
        Long v = DefaultPkmValues.VANILLA_BASE.get(id);
        assertTrue(v != null && v > 0, id + " 缺少正向基础价");
    }
}
