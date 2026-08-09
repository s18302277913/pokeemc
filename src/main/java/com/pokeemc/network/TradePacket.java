package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.menu.TransmutationTableMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 -> 服务器：请求在转化桌兑换指定物品。
 */
public record TradePacket(ItemStack stack, int count) implements CustomPacketPayload {

    public static final Type<TradePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TradePacket> STREAM_CODEC = StreamCodec.composite(
            ItemStack.STREAM_CODEC, TradePacket::stack,
            ByteBufCodecs.VAR_INT, TradePacket::count,
            TradePacket::new
    );

    @Override
    public Type<TradePacket> type() {
        return TYPE;
    }

    public static void handle(TradePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                if (player.containerMenu instanceof TransmutationTableMenu menu) {
                    menu.purchase(packet.stack(), packet.count());
                }
            }
        });
    }
}
