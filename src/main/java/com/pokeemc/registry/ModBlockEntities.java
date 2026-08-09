package com.pokeemc.registry;

import com.pokeemc.PokeEMC;
import com.pokeemc.blockentity.CondenserBlockEntity;
import com.pokeemc.blockentity.TransmutationTableBlockEntity;
import com.pokeemc.id.ModIdAliases;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, PokeEMC.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TransmutationTableBlockEntity>> TRANSMUTATION_TABLE =
            BLOCK_ENTITIES.register("transmutation_table",
                    () -> BlockEntityType.Builder.of(TransmutationTableBlockEntity::new,
                            ModBlocks.TRANSMUTATION_TABLE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CondenserBlockEntity>> CONDENSER =
            BLOCK_ENTITIES.register("condenser",
                    () -> BlockEntityType.Builder.of(CondenserBlockEntity::new,
                            ModBlocks.CONDENSER.get()).build(null));

    public static void register(IEventBus bus) {
        ModIdAliases.blockEntityAliases().forEach((legacy, current) ->
                BLOCK_ENTITIES.addAlias(ResourceLocation.parse(legacy), ResourceLocation.parse(current)));
        BLOCK_ENTITIES.register(bus);
    }
}
