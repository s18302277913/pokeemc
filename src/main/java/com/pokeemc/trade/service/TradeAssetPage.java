package com.pokeemc.trade.service;

import com.pokeemc.trade.model.AssetPageKind;

import java.util.List;
import java.util.UUID;

/**
 * 本人资产页分页结果（计划 5.2）：只回复请求者本人。
 * 条目为展示摘要（物品用 itemId+count，宝可梦不含招式/个体值/努力值/原训练家等隐私字段）。
 * 条目所属类别由 {@link #kind} 决定，条目本身不携带判别标记。
 */
public record TradeAssetPage(
        AssetPageKind kind,
        long assetRevision,
        int total,
        int page,
        int pageSize,
        List<TradeAssetEntry> entries
) {

    public TradeAssetPage {
        if (kind == null || entries == null) {
            throw new IllegalArgumentException("kind/entries cannot be null");
        }
    }

    /**
     * 资产页摘要条目（sealed）：物品 / PKM / 宝可梦。
     */
    public sealed interface TradeAssetEntry
            permits ItemEntry, PkmEntry, PokemonEntry {
    }

    /** 物品条目：客户端可渲染的受限摘要，不携带 NBT；inventorySlot 供发起报价时定位 */
    public record ItemEntry(UUID assetId, String itemId, int count, int inventorySlot) implements TradeAssetEntry {

        public ItemEntry {
            if (assetId == null || itemId == null) {
                throw new IllegalArgumentException("assetId/itemId cannot be null");
            }
            if (count < 0) {
                throw new IllegalArgumentException("count cannot be negative");
            }
        }
    }

    /** PKM 条目：玩家钱包当前可报价余额（单条目） */
    public record PkmEntry(long amount) implements TradeAssetEntry {
    }

    /** 宝可梦条目：不含招式/个体值/努力值/原训练家等隐私字段；源位置供发起报价时定位 */
    public record PokemonEntry(
            UUID assetId,
            UUID pokemonId,
            String species,
            String form,
            int level,
            boolean shiny,
            String nickname,
            String sourceStorage,
            int sourceBox,
            int sourceSlot
    ) implements TradeAssetEntry {

        public PokemonEntry {
            if (pokemonId == null) {
                throw new IllegalArgumentException("pokemonId cannot be null");
            }
            if (species == null) {
                throw new IllegalArgumentException("species cannot be null");
            }
            if (sourceStorage == null) {
                throw new IllegalArgumentException("sourceStorage cannot be null");
            }
        }
    }
}
