package com.pokeemc.trade.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.trade.model.AssetPageKind;
import com.pokeemc.trade.model.TradeCapability;
import com.pokeemc.trade.model.TradeStatus;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * S2C：交易状态快照（计划 5.2）。每次状态改变只向双方推送一次新快照；
 * 快照按请求者视角构造（self=请求者本人），对手报价只含已托管摘要，绝不含 NBT。
 */
public record TradeSnapshotPacket(
        UUID tradeId,
        long revision,
        TradeStatus status,
        PlayerSummary selfPlayer,
        PlayerSummary otherPlayer,
        OfferSummary selfOffer,
        OfferSummary otherOffer,
        boolean selfConfirmed,
        boolean otherConfirmed,
        long expiresAtEpochMillis,
        long lockDeadlineEpochMillis,
        TradeCapability selfCapability,
        TradeCapability otherCapability,
        com.pokeemc.trade.model.DeliveryPreference selfDeliveryPreference,
        com.pokeemc.trade.model.TradeFeeQuote feeQuote
) implements CustomPacketPayload {

    public static final Type<TradeSnapshotPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_snapshot"));

    /** 玩家摘要：UUID + 当前公开名称（名称仅服务端解析） */
    public record PlayerSummary(UUID playerId, String displayName) {

        public PlayerSummary {
            if (playerId == null || displayName == null) {
                throw new IllegalArgumentException("playerId/displayName cannot be null");
            }
        }
    }

    /** 报价摘要：物品（itemId+count）、PKM 总额、宝可梦展示字段；无 NBT */
    public record OfferSummary(
            List<ItemWire> items,
            long pkmTotal,
            List<PokemonWire> pokemon
    ) {

        public OfferSummary {
            if (items == null || pokemon == null) {
                throw new IllegalArgumentException("items/pokemon cannot be null");
            }
        }

        public static OfferSummary empty() {
            return new OfferSummary(List.of(), 0, List.of());
        }
    }

    /** 物品线格式：只含 itemId 与数量，可渲染但不可篡改为任意堆叠 */
    public record ItemWire(String itemId, int count) {

        public ItemWire {
            if (itemId == null) {
                throw new IllegalArgumentException("itemId cannot be null");
            }
        }
    }

    /** 宝可梦线格式：不含招式/个体值/努力值/原训练家等隐私字段 */
    public record PokemonWire(
            UUID pokemonId,
            String species,
            String form,
            int level,
            boolean shiny,
            String nickname
    ) {

        public PokemonWire {
            if (pokemonId == null || species == null) {
                throw new IllegalArgumentException("pokemonId/species cannot be null");
            }
        }
    }

    private static final StreamCodec<ByteBuf, PlayerSummary> PLAYER_SUMMARY = StreamCodec.of(
            (buf, p) -> {
                TradePayloadCodecs.UUID_CODEC.encode(buf, p.playerId());
                TradePayloadCodecs.STRING_UTF8.encode(buf, p.displayName());
            },
            buf -> new PlayerSummary(
                    TradePayloadCodecs.UUID_CODEC.decode(buf),
                    TradePayloadCodecs.STRING_UTF8.decode(buf)));

    private static final StreamCodec<ByteBuf, ItemWire> ITEM_WIRE = StreamCodec.of(
            (buf, w) -> {
                TradePayloadCodecs.STRING_UTF8.encode(buf, w.itemId());
                VarInt.write(buf, w.count());
            },
            buf -> new ItemWire(
                    TradePayloadCodecs.STRING_UTF8.decode(buf),
                    VarInt.read(buf)));

    private static final StreamCodec<ByteBuf, PokemonWire> POKEMON_WIRE = StreamCodec.of(
            (buf, w) -> {
                TradePayloadCodecs.UUID_CODEC.encode(buf, w.pokemonId());
                TradePayloadCodecs.STRING_UTF8.encode(buf, w.species());
                TradePayloadCodecs.STRING_UTF8.encode(buf, w.form());
                VarInt.write(buf, w.level());
                buf.writeBoolean(w.shiny());
                TradePayloadCodecs.STRING_UTF8.encode(buf, w.nickname());
            },
            buf -> new PokemonWire(
                    TradePayloadCodecs.UUID_CODEC.decode(buf),
                    TradePayloadCodecs.STRING_UTF8.decode(buf),
                    TradePayloadCodecs.STRING_UTF8.decode(buf),
                    VarInt.read(buf),
                    buf.readBoolean(),
                    TradePayloadCodecs.STRING_UTF8.decode(buf)));

    private static final StreamCodec<ByteBuf, OfferSummary> OFFER_SUMMARY = StreamCodec.of(
            (buf, s) -> {
                TradePayloadCodecs.boundedList(ITEM_WIRE, 64).encode(buf, s.items());
                VarLong.write(buf, s.pkmTotal());
                TradePayloadCodecs.boundedList(POKEMON_WIRE, 32).encode(buf, s.pokemon());
            },
            buf -> new OfferSummary(
                    TradePayloadCodecs.boundedList(ITEM_WIRE, 64).decode(buf),
                    VarLong.read(buf),
                    TradePayloadCodecs.boundedList(POKEMON_WIRE, 32).decode(buf)));

    private static final StreamCodec<ByteBuf, com.pokeemc.trade.model.DeliveryPreference> PREFERENCE =
            TradePayloadCodecs.DELIVERY_PREFERENCE;

    public static final StreamCodec<ByteBuf, TradeSnapshotPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                TradePayloadCodecs.UUID_CODEC.encode(buf, p.tradeId());
                VarLong.write(buf, p.revision());
                TradePayloadCodecs.TRADE_STATUS.encode(buf, p.status());
                PLAYER_SUMMARY.encode(buf, p.selfPlayer());
                PLAYER_SUMMARY.encode(buf, p.otherPlayer());
                OFFER_SUMMARY.encode(buf, p.selfOffer());
                OFFER_SUMMARY.encode(buf, p.otherOffer());
                buf.writeBoolean(p.selfConfirmed());
                buf.writeBoolean(p.otherConfirmed());
                VarLong.write(buf, p.expiresAtEpochMillis());
                VarLong.write(buf, p.lockDeadlineEpochMillis());
                TradePayloadCodecs.TRADE_CAPABILITY.encode(buf, p.selfCapability());
                TradePayloadCodecs.TRADE_CAPABILITY.encode(buf, p.otherCapability());
                PREFERENCE.encode(buf, p.selfDeliveryPreference());
                buf.writeBoolean(p.feeQuote() != null);
                if (p.feeQuote() != null) {
                    TradePayloadCodecs.TRADE_FEE_QUOTE.encode(buf, p.feeQuote());
                }
            },
            buf -> new TradeSnapshotPacket(
                    TradePayloadCodecs.UUID_CODEC.decode(buf),
                    VarLong.read(buf),
                    TradePayloadCodecs.TRADE_STATUS.decode(buf),
                    PLAYER_SUMMARY.decode(buf),
                    PLAYER_SUMMARY.decode(buf),
                    OFFER_SUMMARY.decode(buf),
                    OFFER_SUMMARY.decode(buf),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    VarLong.read(buf),
                    VarLong.read(buf),
                    TradePayloadCodecs.TRADE_CAPABILITY.decode(buf),
                    TradePayloadCodecs.TRADE_CAPABILITY.decode(buf),
                    PREFERENCE.decode(buf),
                    buf.readBoolean() ? TradePayloadCodecs.TRADE_FEE_QUOTE.decode(buf) : null));

    @Override
    public Type<TradeSnapshotPacket> type() {
        return TYPE;
    }
}
