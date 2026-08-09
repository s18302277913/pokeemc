package com.pokeemc.trade.network;

import com.pokeemc.trade.asset.PokemonSummaryReader;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeCapability;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.service.TradeSnapshot;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;

/**
 * 快照投影器（计划 5.2）：把服务层 {@link TradeSnapshot} 投影为 {@link TradeSnapshotPacket}。
 * <p>
 * 职责分离：服务层快照携带完整资产（含 NBT，供命令/调试），网络层在此处做隐私裁剪——
 * 对手报价只含展示摘要（物品 itemId+count、PKM 总额、宝可梦展示字段），绝不含 NBT、
 * 招式、个体值、努力值、原训练家。self/other 名称由调用方经 {@code TradeRuntime.displayName} 提供。
 * <p>
 * 纯 JVM 可测：只依赖 {@link CompoundTag} 与模型类，可在单测中构造 NBT 驱动。
 */
public final class TradeSnapshotProjection {

    private TradeSnapshotProjection() {
    }

    /** 投影；调用方保证 {@code selfName}/{@code otherName} 非 null（离线占位由调用方提供） */
    public static TradeSnapshotPacket project(TradeSnapshot s, String selfName, String otherName) {
        return new TradeSnapshotPacket(
                s.tradeId().value(),
                s.revision(),
                s.status(),
                new TradeSnapshotPacket.PlayerSummary(s.selfPlayerId(), selfName),
                new TradeSnapshotPacket.PlayerSummary(s.otherPlayerId(), otherName),
                offerSummary(s.selfOffer()),
                offerSummary(s.otherOffer()),
                s.selfConfirmed(),
                s.otherConfirmed(),
                s.expiresAtEpochMillis(),
                s.lockDeadlineEpochMillis(),
                TradeCapability.BUSY,
                TradeCapability.BUSY,
                s.selfPreference(),
                s.feeQuote());
    }

    /** 报价 → 展示摘要：物品（itemId+count）、PKM 总额、宝可梦展示字段；不携带任何 NBT */
    private static TradeSnapshotPacket.OfferSummary offerSummary(TradeOffer offer) {
        List<TradeSnapshotPacket.ItemWire> items = new ArrayList<>(offer.items().size());
        for (ItemAsset ia : offer.items()) {
            String itemId = itemIdOf(ia.stackNbt());
            if (itemId == null || itemId.isBlank()) {
                continue; // 防御：无效序列化 NBT 不进入线格式
            }
            items.add(new TradeSnapshotPacket.ItemWire(itemId, countOf(ia.stackNbt())));
        }
        List<TradeSnapshotPacket.PokemonWire> mons = new ArrayList<>(offer.pokemon().size());
        for (PokemonAsset ka : offer.pokemon()) {
            CompoundTag nbt = ka.pokemonNbt();
            mons.add(new TradeSnapshotPacket.PokemonWire(
                    ka.pokemonId(),
                    PokemonSummaryReader.species(nbt),
                    PokemonSummaryReader.form(nbt),
                    PokemonSummaryReader.level(nbt),
                    PokemonSummaryReader.shiny(nbt),
                    PokemonSummaryReader.nickname(nbt)));
        }
        return new TradeSnapshotPacket.OfferSummary(items, offer.totalPkm(), mons);
    }

    /** ItemStack NBT 中的 registry id（1.21 格式 {@code {id, Count, tag}}）；缺失返回空串 */
    private static String itemIdOf(CompoundTag nbt) {
        return nbt == null ? "" : nbt.getString("id");
    }

    /** ItemStack NBT 中的堆叠数量（Count 为 byte）；缺失/非法按 1 处理（防御） */
    private static int countOf(CompoundTag nbt) {
        if (nbt == null || !nbt.contains("Count", net.minecraft.nbt.Tag.TAG_BYTE)) {
            return 1;
        }
        int count = nbt.getByte("Count");
        return count > 0 ? count : 1;
    }
}
