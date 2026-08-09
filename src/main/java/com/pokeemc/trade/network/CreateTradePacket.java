package com.pokeemc.trade.network;

import com.pokeemc.PokeEMC;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S：创建交易邀请（计划 5.1）。
 * 目标玩家在线且非自己由服务层校验；创建/确认共用 2 次/秒限流。
 */
public record CreateTradePacket(UUID requestId, UUID targetPlayerId) implements CustomPacketPayload {

    public static final Type<CreateTradePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_create"));

    public static final StreamCodec<ByteBuf, CreateTradePacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, CreateTradePacket::requestId,
            TradePayloadCodecs.UUID_CODEC, CreateTradePacket::targetPlayerId,
            CreateTradePacket::new
    );

    @Override
    public Type<CreateTradePacket> type() {
        return TYPE;
    }

    public static void handle(CreateTradePacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onCreate(packet, context);
    }
}
