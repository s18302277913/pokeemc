package com.pokeemc.trade.asset;

import com.pokeemc.trade.model.PokemonAsset;
import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

/**
 * 宝可梦存储快照（Task 5）：从存储位置读出的宝可梦视图。
 * {@code tradeable}/{@code busy} 由 Pixelmon 适配器在读取时判定；
 * 从 {@link PokemonAsset} 重建（交付路径）时两者默认为可交易且不忙碌。
 */
public record StoredPokemon(UUID pokemonId, CompoundTag nbt, boolean tradeable, boolean busy) {

    public StoredPokemon {
        Objects.requireNonNull(pokemonId, "pokemonId");
        Objects.requireNonNull(nbt, "nbt");
    }

    /** 从资产重建（交付时用于放置目标存储） */
    public static StoredPokemon from(PokemonAsset asset) {
        return new StoredPokemon(asset.pokemonId(), asset.pokemonNbt(), true, false);
    }
}
