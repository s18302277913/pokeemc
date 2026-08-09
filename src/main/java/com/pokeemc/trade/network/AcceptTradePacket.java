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
 * C2S：接受交易邀请（计划 5.1，INVITED -> OPEN）。
 * 只有被邀请方（RIGHT）能接受；发起方不能接受自己的邀请。
 * 接受与创建/确认共用 2 次/秒限流。
 */
public record AcceptTradePacket(
        UUID requestId,
        UUID tradeId,
        long expectedRevision
) implements CustomPacketPayload {

    public static final Type<AcceptTradePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_accept"));

    public static final StreamCodec<ByteBuf, AcceptTradePacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, AcceptTradePacket::requestId,
            TradePayloadCodecs.UUID_CODEC, AcceptTradePacket::tradeId,
            ByteBufCodecs.VAR_LONG, AcceptTradePacket::expectedRevision,
            AcceptTradePacket::new
    );

    @Override
    public Type<AcceptTradePacket> type() {
        return TYPE;
    }

    public static void handle(AcceptTradePacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onAccept(packet, context);
    }
}
