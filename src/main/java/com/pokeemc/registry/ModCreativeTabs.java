package com.pokeemc.registry;

import com.pokeemc.PokeEMC;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造物品栏：专属 PokeTrade 标签页，收纳转化桌、凝聚器与三种炼金燃料。
 */
public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PokeEMC.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> POKEEMC_TAB =
            CREATIVE_TABS.register("poketrade", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.poketrade"))
                    .icon(() -> new ItemStack(ModBlocks.TRANSMUTATION_TABLE_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.TRANSMUTATION_TABLE_ITEM.get());
                        // [CHANGED] 会话 #28：便携式转化桌紧随转化桌方块之后
                        output.accept(ModItems.PORTABLE_TRANSMUTATION_TABLE.get());
                        output.accept(ModBlocks.CONDENSER_ITEM.get());
                        output.accept(ModItems.ALCHEMICAL_COAL.get());
                        output.accept(ModItems.MOBIUS_FUEL.get());
                        output.accept(ModItems.AETERNALIS_FUEL.get());
                    })
                    .build());

    public static void register(IEventBus bus) {
        CREATIVE_TABS.register(bus);
    }

    private ModCreativeTabs() {}
}
