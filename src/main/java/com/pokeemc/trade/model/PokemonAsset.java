package com.pokeemc.trade.model;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Pixelmon 宝可梦托管资产（计划 3.4）：完整 {@code Pokemon#writeToNBT} 已从
 * Party/PC 移入交易托管，保留原位置用于取消归还。
 * {@code pokemonId} 用于全局去重（同一宝可梦不可被重复托管）。
 */
public record PokemonAsset(
        UUID assetId,
        UUID originalOwner,
        UUID pokemonId,
        CompoundTag pokemonNbt,
        String sourceStorage,
        int sourceBox,
        int sourceSlot
) implements TradeAsset {

    public PokemonAsset {
        if (assetId == null || originalOwner == null || pokemonId == null) {
            throw new IllegalArgumentException("assetId/originalOwner/pokemonId cannot be null");
        }
        if (pokemonNbt == null) {
            throw new IllegalArgumentException("pokemonNbt cannot be null");
        }
        if (sourceStorage == null || sourceStorage.isBlank()) {
            throw new IllegalArgumentException("sourceStorage cannot be blank");
        }
        if (!sourceStorage.equals("party") && !sourceStorage.equals("pc")) {
            throw new IllegalArgumentException("sourceStorage must be party or pc");
        }
        if (sourceStorage.equals("party") && sourceBox != -1) {
            throw new IllegalArgumentException("party uses box=-1");
        }
    }

    @Override
    public String kind() {
        return "POKEMON";
    }
}
