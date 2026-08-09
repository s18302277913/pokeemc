package com.pokeemc.registry;

import com.pokeemc.PokeEMC;
import com.pokeemc.id.ModIdAliases;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 炼金燃料系列（增值货币）：
 * <ul>
 *   <li>炼金煤炭：8 煤 + 1 红石合成，EMC 倍增（价值约 5 倍于材料）</li>
 *   <li>熔火燃料：熔炼炼金煤炭，价值再翻倍</li>
 *   <li>凡斯燃料：熔炼熔火燃料，价值再翻倍，终极燃料货币</li>
 * </ul>
 * 玩家通过合成不断增值，燃料可投入转化桌/凝聚器变现，形成"印钱"经济循环。
 */
public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PokeEMC.MODID);

    public static final DeferredItem<Item> ALCHEMICAL_COAL = ITEMS.register("alchemical_coal",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> MOBIUS_FUEL = ITEMS.register("mobius_fuel",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> AETERNALIS_FUEL = ITEMS.register("aeternalis_fuel",
            () -> new Item(new Item.Properties()));

    public static void register(IEventBus bus) {
        ModIdAliases.itemAliases().forEach((legacy, current) -> {
            if (legacy.startsWith("pokeemc:alchemical_coal")
                    || legacy.startsWith("pokeemc:mobius_fuel")
                    || legacy.startsWith("pokeemc:aeternalis_fuel")) {
                ITEMS.addAlias(ResourceLocation.parse(legacy), ResourceLocation.parse(current));
            }
        });
        ITEMS.register(bus);
    }
}
