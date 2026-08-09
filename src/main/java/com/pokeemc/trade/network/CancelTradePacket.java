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
 * C2S：取消交易（计划 5.1）。双方均可取消；已托管资产归还各原所有者。
 * LOCKED 期间取消归入报价变更限流类别。
 */
public record CancelTradePacket(
        UUID requestId,
        UUID tradeId,
        long expectedRevision
) implements CustomPacketPayload {

    public static final Type<CancelTradePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_cancel"));

    public static final StreamCodec<ByteBuf, CancelTradePacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, CancelTradePacket::requestId,
            TradePayloadCodecs.UUID_CODEC, CancelTradePacket::tradeId,
            ByteBufCodecs.VAR_LONG, CancelTradePacket::expectedRevision,
            CancelTradePacket::new
    );

    @Override
    public Type<CancelTradePacket> type() {
        return TYPE;
    }

    public static void handle(CancelTradePacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onCancel(packet, context);
    }
}
