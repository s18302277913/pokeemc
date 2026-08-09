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
 * C2S：从报价中移除一件资产（物品/PKM/宝可梦，按 assetId）（计划 5.1）。
 * 服务层按 assetId 定位资产并归还所有者；移除后 revision+1。
 */
public record RemoveOfferAssetPacket(
        UUID requestId,
        UUID tradeId,
        long expectedRevision,
        UUID assetId
) implements CustomPacketPayload {

    public static final Type<RemoveOfferAssetPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_remove_asset"));

    public static final StreamCodec<ByteBuf, RemoveOfferAssetPacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, RemoveOfferAssetPacket::requestId,
            TradePayloadCodecs.UUID_CODEC, RemoveOfferAssetPacket::tradeId,
            ByteBufCodecs.VAR_LONG, RemoveOfferAssetPacket::expectedRevision,
            TradePayloadCodecs.UUID_CODEC, RemoveOfferAssetPacket::assetId,
            RemoveOfferAssetPacket::new
    );

    @Override
    public Type<RemoveOfferAssetPacket> type() {
        return TYPE;
    }

    public static void handle(RemoveOfferAssetPacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onRemoveAsset(packet, context);
    }
}
