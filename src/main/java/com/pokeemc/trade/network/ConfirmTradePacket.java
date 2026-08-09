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
 * C2S：确认报价（计划 5.1）。双方确认后进入 3 秒锁定；
 * 报价在确认后变更会使 revision+1 并清空确认。创建/确认共用 2 次/秒限流。
 */
public record ConfirmTradePacket(
        UUID requestId,
        UUID tradeId,
        long expectedRevision
) implements CustomPacketPayload {

    public static final Type<ConfirmTradePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_confirm"));

    public static final StreamCodec<ByteBuf, ConfirmTradePacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, ConfirmTradePacket::requestId,
            TradePayloadCodecs.UUID_CODEC, ConfirmTradePacket::tradeId,
            ByteBufCodecs.VAR_LONG, ConfirmTradePacket::expectedRevision,
            ConfirmTradePacket::new
    );

    @Override
    public Type<ConfirmTradePacket> type() {
        return TYPE;
    }

    public static void handle(ConfirmTradePacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onConfirm(packet, context);
    }
}
