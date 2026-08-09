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
 * C2S：把钱包 PKM 托管进报价（计划 5.1）。
 * 金额上限由边界检查拦截（防负数/超大 VarLong），服务层做精确余额校验。
 */
public record OfferPkmPacket(
        UUID requestId,
        UUID tradeId,
        long expectedRevision,
        long amount
) implements CustomPacketPayload {

    public static final Type<OfferPkmPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_offer_pkm"));

    public static final StreamCodec<ByteBuf, OfferPkmPacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, OfferPkmPacket::requestId,
            TradePayloadCodecs.UUID_CODEC, OfferPkmPacket::tradeId,
            ByteBufCodecs.VAR_LONG, OfferPkmPacket::expectedRevision,
            ByteBufCodecs.VAR_LONG, OfferPkmPacket::amount,
            OfferPkmPacket::new
    );

    @Override
    public Type<OfferPkmPacket> type() {
        return TYPE;
    }

    public static void handle(OfferPkmPacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onOfferPkm(packet, context);
    }
}
