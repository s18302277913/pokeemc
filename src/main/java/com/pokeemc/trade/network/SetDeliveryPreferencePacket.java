package com.pokeemc.trade.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.trade.model.DeliveryPreference;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S：设置本人收货偏好（计划 5.1/5.2）。
 * 偏好只对本人生效，绝不投影给对手。
 */
public record SetDeliveryPreferencePacket(
        UUID requestId,
        UUID tradeId,
        long expectedRevision,
        DeliveryPreference preference
) implements CustomPacketPayload {

    public static final Type<SetDeliveryPreferencePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_set_preference"));

    public static final StreamCodec<ByteBuf, SetDeliveryPreferencePacket> STREAM_CODEC = StreamCodec.composite(
            TradePayloadCodecs.UUID_CODEC, SetDeliveryPreferencePacket::requestId,
            TradePayloadCodecs.UUID_CODEC, SetDeliveryPreferencePacket::tradeId,
            ByteBufCodecs.VAR_LONG, SetDeliveryPreferencePacket::expectedRevision,
            TradePayloadCodecs.DELIVERY_PREFERENCE, SetDeliveryPreferencePacket::preference,
            SetDeliveryPreferencePacket::new
    );

    @Override
    public Type<SetDeliveryPreferencePacket> type() {
        return TYPE;
    }

    public static void handle(SetDeliveryPreferencePacket packet, IPayloadContext context) {
        TradeNetworkHandlers.onSetPreference(packet, context);
    }
}
