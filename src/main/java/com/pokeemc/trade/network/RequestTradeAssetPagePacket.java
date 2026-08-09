package com.pokeemc.trade.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.trade.model.AssetPageKind;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S：请求本人资产页分页（计划 5.2）。
 * 请求者必须是该交易参与者；只能请求自己的库存/钱包/队伍/PC；
 * PC 页以页码为箱号（单箱一页）。页大小 ≤54 由边界检查拦截。
 */
public record RequestTradeAssetPagePacket(
        UUID requestId,
        UUID tradeId,
        long expectedRevision,
        AssetPageKind kind,
        int page,
        int pageSize
) implements CustomPacketPayload {

    public static final Type<RequestTradeAssetPagePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_asset_page"));

    public static final StreamCodec<ByteBuf, RequestTradeAssetPagePacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, RequestTradeAssetPagePacket::requestId,
            TradePayloadCodecs.UUID_CODEC, RequestTradeAssetPagePacket::tradeId,
            ByteBufCodecs.VAR_LONG, RequestTradeAssetPagePacket::expectedRevision,
            TradePayloadCodecs.ASSET_PAGE_KIND, RequestTradeAssetPagePacket::kind,
            ByteBufCodecs.VAR_INT, RequestTradeAssetPagePacket::page,
            ByteBufCodecs.VAR_INT, RequestTradeAssetPagePacket::pageSize,
            RequestTradeAssetPagePacket::new
    );

    @Override
    public Type<RequestTradeAssetPagePacket> type() {
        return TYPE;
    }

    public static void handle(RequestTradeAssetPagePacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onAssetPage(packet, context);
    }
}
