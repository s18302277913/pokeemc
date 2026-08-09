package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.menu.CondenserMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 -> 服务器：设置能量凝聚器的目标物品。
 * 目标物品可为空（null 编码为空串），表示清除目标。
 */
public record SetCondenserTargetPacket(ItemStack target) implements CustomPacketPayload {

    public static final Type<SetCondenserTargetPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "set_condenser_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCondenserTargetPacket> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC,
            SetCondenserTargetPacket::target,
            SetCondenserTargetPacket::new
    );

    @Override
    public Type<SetCondenserTargetPacket> type() {
        return TYPE;
    }

    public static void handle(SetCondenserTargetPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                if (player.containerMenu instanceof CondenserMenu menu) {
                    menu.setTarget(packet.target());
                }
            }
        });
    }
}
