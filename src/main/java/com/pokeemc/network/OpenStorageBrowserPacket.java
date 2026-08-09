package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.menu.StorageBrowserMenu;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 -> 服务器：请求打开独立的仓储浏览器菜单（Task 9）。
 *
 * <p>浏览器为纯远程视图，无绑定方块，{@code ServerContext} 恒有效；
 * 打开后由客户端屏幕发起 {@link QueryStoragesPacket} 等数据请求。</p>
 */
public record OpenStorageBrowserPacket() implements CustomPacketPayload {

    public static final Type<OpenStorageBrowserPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "open_storage_browser"));

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, OpenStorageBrowserPacket> STREAM_CODEC =
            StreamCodec.unit(new OpenStorageBrowserPacket());

    @Override
    public Type<OpenStorageBrowserPacket> type() {
        return TYPE;
    }

    public static void handle(OpenStorageBrowserPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                player.openMenu(new SimpleMenuProvider(
                        (containerId, inventory, p) -> new StorageBrowserMenu.Standalone(
                                containerId, inventory, p2 -> true),
                        Component.literal("仓储浏览器")));
            }
        });
    }
}
