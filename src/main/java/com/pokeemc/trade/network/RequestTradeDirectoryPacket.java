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
 * C2S：请求在线玩家目录分页（计划 5.1）。
 * 搜索词 ≤64 字符、页大小 ≤50 由边界检查拦截；目录 2 秒 TTL 由客户端维护。
 */
public record RequestTradeDirectoryPacket(
        UUID requestId,
        String query,
        int page,
        int pageSize
) implements CustomPacketPayload {

    public static final Type<RequestTradeDirectoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_directory"));

    public static final StreamCodec<ByteBuf, RequestTradeDirectoryPacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, RequestTradeDirectoryPacket::requestId,
            TradePayloadCodecs.STRING_UTF8, RequestTradeDirectoryPacket::query,
            ByteBufCodecs.VAR_INT, RequestTradeDirectoryPacket::page,
            ByteBufCodecs.VAR_INT, RequestTradeDirectoryPacket::pageSize,
            RequestTradeDirectoryPacket::new
    );

    @Override
    public Type<RequestTradeDirectoryPacket> type() {
        return TYPE;
    }

    public static void handle(RequestTradeDirectoryPacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onDirectory(packet, context);
    }
}
