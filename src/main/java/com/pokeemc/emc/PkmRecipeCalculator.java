package com.pokeemc.emc;

import com.pokeemc.PokeEMC;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 配方自动计算引擎（简化版 ProjectE GraphMapper 思路）：
 * <ol>
 *   <li>遍历所有合成/熔炼/切石配方</li>
 *   <li>若配方所有输入均有 PKM（含 tag 展开取最小值），则输出 PKM = sum(输入PKM) / 输出数量</li>
 *   <li>熔炼类配方（1 输入 → 1 输出）支持双向传播：输入有价则输出同价；输出有价则输入同价（矿石等）</li>
 *   <li>多轮迭代直到不再产生新值（配方产物可能作为其他配方的输入）</li>
 * </ol>
 */
public final class PkmRecipeCalculator {

    private static final int MAX_ROUNDS = 200;

    private PkmRecipeCalculator() {}

    /**
     * 在服务端世界数据齐全后调用一次，补充未定价物品。
     *
     * @param level 服务端 Level（提供 RegistryAccess 与 RecipeManager）
     */
    public static void computeAll(Level level) {
        if (level.isClientSide()) {
            return;
        }
        RecipeManager recipeManager = level.getRecipeManager();
        RegistryAccess access = level.registryAccess();
        List<RecipeHolder<?>> recipes = new ArrayList<>();
        // [CHANGED] Bug C：加入 SMITHING（锻造台），使 1.21+ 锻造产出自动推导——
        // mace（breeze_rod + heavy_core）等 base/addition 均有价时可按输入求和；
        // 镶饰/下界合金升级因 template 物品无价自然跳过，不影响既有值。
        for (RecipeType<?> type : List.of(RecipeType.CRAFTING, RecipeType.SMELTING, RecipeType.BLASTING,
                RecipeType.SMOKING, RecipeType.CAMPFIRE_COOKING, RecipeType.STONECUTTING,
                RecipeType.SMITHING)) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Collection<RecipeHolder<?>> holders = (Collection) recipeManager.getAllRecipesFor((RecipeType) type);
            recipes.addAll(holders);
        }
        PokeEMC.LOGGER.info("PokeEMC: recipe compute start, {} recipes, {} known values", recipes.size(), PKMManager.size());
        // [CHANGED] 会话 #16（bug 6）：酿造台不是 RecipeManager 配方，是内置 PotionBrewing
        // 混合表（挂在 MinecraftServer.potionBrewing()）；客户端/无服务端时跳过。
        PotionBrewing brewing = level instanceof ServerLevel serverLevel
                ? serverLevel.getServer().potionBrewing() : null;

        long before = PKMManager.size();
        int round = 0;
        boolean changed = true;
        while (changed && round < MAX_ROUNDS) {
            changed = false;
            round++;
            for (RecipeHolder<?> holder : recipes) {
                if (tryCompute(holder.value(), access)) {
                    changed = true;
                }
            }
            if (computeBrewing(brewing)) {
                changed = true;
            }
        }
        long after = PKMManager.size();
        PokeEMC.LOGGER.info("PokeEMC: recipe compute done after {} rounds, {} -> {} values", round, before, after);
    }

    private static boolean tryCompute(Recipe<?> recipe, RegistryAccess access) {
        ItemStack output = recipe.getResultItem(access);
        if (output.isEmpty()) {
            return false;
        }
        ResourceLocation outKey = BuiltInRegistries.ITEM.getKey(output.getItem()); // [CHANGED] 官方 API：builtInRegistryHolder() 弃用
        long outVal = PKMManager.getPkm(outKey);

        // 熔炼/烧烤类：1 输入 → 1 输出，双向传播
        RecipeType<?> type = recipe.getType();
        if (type == RecipeType.SMELTING || type == RecipeType.BLASTING
                || type == RecipeType.SMOKING || type == RecipeType.CAMPFIRE_COOKING) {
            return tryComputeCooking(recipe, outKey, outVal);
        }

        // 合成/切石：输出 = sum(输入) / 输出数量
        if (outVal >= 0) {
            return false;
        }
        long totalInput = 0;
        boolean allKnown = true;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            long minPkm = Long.MAX_VALUE;
            boolean anyKnown = false;
            for (ItemStack stack : ingredient.getItems()) {
                long v = PKMManager.getPkm(stack);
                if (v >= 0) {
                    anyKnown = true;
                    if (v < minPkm) {
                        minPkm = v;
                    }
                }
            }
            if (!anyKnown) {
                allKnown = false;
                break;
            }
            totalInput += minPkm;
        }
        if (!allKnown) {
            return false;
        }
        long outValue = Math.max(1, totalInput / Math.max(1, output.getCount()));
        PKMManager.setComputed(outKey, outValue);
        return true;
    }

    /**
     * 酿造台推导（会话 #16，bug 6）：1.21.1 酿造不是 RecipeManager 配方，而是内置
     * {@link PotionBrewing} 混合表（{@code MinecraftServer#potionBrewing()}，NeoForge
     * 实例：药水效果混合 + 容器混合 + 模组配方）。仅做<b>正向</b>传播，避免反向
     * 制造「药水 → 材料」的假依赖（买=卖，无套利）：
     * <ul>
     *   <li>药水物品 {@code minecraft:potion} 未定价时 = 全部「可酿成分」中的<b>最小价</b>
     *       （EMC 简化：所有药水共享一个物品键，按最便宜材料口径定价）；</li>
     *   <li>容器混合正向：药水 + 火药 → 喷溅，喷溅 + 龙息 → 滞留（前提：火药/龙息有价）。</li>
     * </ul>
     */
    private static boolean computeBrewing(PotionBrewing brewing) {
        if (brewing == null) {
            return false;
        }
        boolean changed = false;
        ResourceLocation potionKey = BuiltInRegistries.ITEM.getKey(Items.POTION);
        if (PKMManager.getPkm(potionKey) < 0) {
            long minIng = minBrewingIngredient(brewing);
            if (minIng != Long.MAX_VALUE) {
                PKMManager.setComputed(potionKey, Math.max(1, minIng));
                changed = true;
            }
        }
        // 容器混合正向：药水 → 喷溅 → 滞留（成分为火药 / 龙息，两者本身已有价）
        long potionV = PKMManager.getPkm(potionKey);
        if (potionV >= 0) {
            ResourceLocation splashKey = BuiltInRegistries.ITEM.getKey(Items.SPLASH_POTION);
            if (PKMManager.getPkm(splashKey) < 0 && PKMManager.getPkm(Items.GUNPOWDER) >= 0) {
                PKMManager.setComputed(splashKey, Math.max(1, potionV));
                changed = true;
            }
            long splashV = PKMManager.getPkm(splashKey);
            ResourceLocation lingeringKey = BuiltInRegistries.ITEM.getKey(Items.LINGERING_POTION);
            if (PKMManager.getPkm(lingeringKey) < 0 && splashV >= 0
                    && PKMManager.getPkm(Items.DRAGON_BREATH) >= 0) {
                PKMManager.setComputed(lingeringKey, Math.max(1, splashV));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 遍历 PKM 快照全部已知物品，返回是「可酿成分」（药水效果成分或容器成分）的最低价值。
     * 未找到返回 {@link Long#MAX_VALUE}。仅遍历快照（有限集合），不做全注册表扫描。
     */
    private static long minBrewingIngredient(PotionBrewing brewing) {
        long min = Long.MAX_VALUE;
        for (Object2LongMap.Entry<ResourceLocation> e : PKMManager.snapshot().object2LongEntrySet()) {
            long v = e.getLongValue();
            if (v <= 0 || v >= min) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(e.getKey());
            if (item == null || item == Items.AIR) {
                continue;
            }
            ItemStack s = new ItemStack(item);
            if (s.isEmpty()) {
                continue;
            }
            try {
                if (brewing.isPotionIngredient(s) || brewing.isContainerIngredient(s)) {
                    min = v;
                }
            } catch (RuntimeException ignored) {
                // 异常物品跳过，不阻断整体推导
            }
        }
        return min;
    }

    private static boolean tryComputeCooking(Recipe<?> recipe, ResourceLocation outKey, long outVal) {
        boolean changed = false;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            long inVal = Long.MAX_VALUE;
            boolean anyKnown = false;
            for (ItemStack stack : ingredient.getItems()) {
                long v = PKMManager.getPkm(stack);
                if (v >= 0) {
                    anyKnown = true;
                    if (v < inVal) {
                        inVal = v;
                    }
                }
            }
            if (anyKnown) {
                // 输入有价 → 输出同价（1:1 烧炼）
                if (outVal < 0) {
                    PKMManager.setComputed(outKey, Math.max(1, inVal));
                    changed = true;
                    outVal = Math.max(1, inVal);
                }
            } else if (outVal >= 0) {
                // 输出有价 → 输入同价（如矿石：铁锭 256 → 铁矿石 256）
                for (ItemStack stack : ingredient.getItems()) {
                    ResourceLocation inKey = BuiltInRegistries.ITEM.getKey(stack.getItem()); // [CHANGED] 官方 API
                    if (PKMManager.getPkm(inKey) < 0) {
                        PKMManager.setComputed(inKey, outVal);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }
}
