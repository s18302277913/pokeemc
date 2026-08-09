package com.pokeemc.trade.network;

import com.pokeemc.PokeEMC;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S：把队伍/PC 宝可梦托管进报价（计划 5.1）。
 * storageKind ∈ {"party","pc"}；party 时 box=-1、slot∈[0,5]；pc 时 box/slot 有防御上限。
 */
public record OfferPokemonPacket(
        UUID requestId,
        UUID tradeId,
        long expectedRevision,
        String storageKind,
        int box,
        int slot
) implements CustomPacketPayload {

    public static final Type<OfferPokemonPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_offer_pokemon"));

    public static final StreamCodec<ByteBuf, OfferPokemonPacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, OfferPokemonPacket::requestId,
            TradePayloadCodecs.UUID_CODEC, OfferPokemonPacket::tradeId,
            ByteBufCodecs.VAR_LONG, OfferPokemonPacket::expectedRevision,
            TradePayloadCodecs.SHORT_STRING, OfferPokemonPacket::storageKind,
            ByteBufCodecs.VAR_INT, OfferPokemonPacket::box,
            ByteBufCodecs.VAR_INT, OfferPokemonPacket::slot,
            OfferPokemonPacket::new
    );

    @Override
    public Type<OfferPokemonPacket> type() {
        return TYPE;
    }

    public static void handle(OfferPokemonPacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onOfferPokemon(packet, context);
    }
}
