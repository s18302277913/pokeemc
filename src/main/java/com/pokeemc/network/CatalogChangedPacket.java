package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 目录变更通知（S2C 轻量单字段）：服务端 {@code ExchangePriceService} 重建目录后广播，
 * 客户端仅对开着的 {@link com.pokeemc.client.ExchangeCatalogHost} 屏幕触发重新拉取目录
 * （会话 #16：修复「有价但交易列表没有该物品 + 客户端/服务端列表同步不及时」）。
 */
public record CatalogChangedPacket(long catalogVersion) implements CustomPacketPayload {

    public static final Type<CatalogChangedPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "catalog_changed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CatalogChangedPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, CatalogChangedPacket::catalogVersion,
                    CatalogChangedPacket::new);

    @Override
    public Type<CatalogChangedPacket> type() {
        return TYPE;
    }

    public static void handle(CatalogChangedPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen
                    instanceof com.pokeemc.client.ExchangeCatalogHost host) {
                host.onCatalogChanged(packet.catalogVersion());
            }
        });
    }
}
