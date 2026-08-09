package com.pokeemc.registry;

import com.pokeemc.PokeEMC;
import com.pokeemc.block.CondenserBlock;
import com.pokeemc.block.TransmutationTableBlock;
import com.pokeemc.id.ModIdAliases;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(PokeEMC.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PokeEMC.MODID);

    public static final DeferredBlock<Block> TRANSMUTATION_TABLE = BLOCKS.register("transmutation_table",
            () -> new TransmutationTableBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.0f, 6.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<Item> TRANSMUTATION_TABLE_ITEM = ITEMS.register("transmutation_table",
            () -> new BlockItem(TRANSMUTATION_TABLE.get(), new Item.Properties()));

    public static final DeferredBlock<Block> CONDENSER = BLOCKS.register("condenser",
            () -> new CondenserBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(3.0f, 6.0f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<Item> CONDENSER_ITEM = ITEMS.register("condenser",
            () -> new BlockItem(CONDENSER.get(), new Item.Properties()));

    public static void register(IEventBus bus) {
        ModIdAliases.blockAliases().forEach((legacy, current) ->
                BLOCKS.addAlias(ResourceLocation.parse(legacy), ResourceLocation.parse(current)));
        ModIdAliases.itemAliases().forEach((legacy, current) -> {
            if (legacy.startsWith("pokeemc:transmutation_table") || legacy.startsWith("pokeemc:condenser")) {
                ITEMS.addAlias(ResourceLocation.parse(legacy), ResourceLocation.parse(current));
            }
        });
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}