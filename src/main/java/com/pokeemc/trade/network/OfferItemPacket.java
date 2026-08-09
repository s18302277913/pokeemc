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
 * C2S：把背包某槽位物品托管进报价（计划 5.1）。
 * slot/count 硬上限由边界检查在进入服务层前拦截。
 */
public record OfferItemPacket(
        UUID requestId,
        UUID tradeId,
        long expectedRevision,
        int inventorySlot,
        int count
) implements CustomPacketPayload {

    public static final Type<OfferItemPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_offer_item"));

    public static final StreamCodec<ByteBuf, OfferItemPacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, OfferItemPacket::requestId,
            TradePayloadCodecs.UUID_CODEC, OfferItemPacket::tradeId,
            ByteBufCodecs.VAR_LONG, OfferItemPacket::expectedRevision,
            ByteBufCodecs.VAR_INT, OfferItemPacket::inventorySlot,
            ByteBufCodecs.VAR_INT, OfferItemPacket::count,
            OfferItemPacket::new
    );

    @Override
    public Type<OfferItemPacket> type() {
        return TYPE;
    }

    public static void handle(OfferItemPacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onOfferItem(packet, context);
    }
}
