package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.client.BrowserHost;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 仓储列表失效通知（S2C 空载荷）：服务端在容器放置/破坏后广播，
 * 客户端仅对开着的 {@link BrowserHost} 屏幕触发重新发起列表查询
 * （会话 #29：修复「放置/破坏箱子后仓储列表不即时刷新」）。
 *
 * <p>与 {@link CatalogChangedPacket} 同款轻量失效模式：服务端重置查询限频后
 * 客户端以当前条件重发 {@code QueryStoragesPacket}，天然走全量扫描返回最新列表。</p>
 */
public record StorageChangedPacket() implements CustomPacketPayload {

    public static final Type<StorageChangedPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_changed"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageChangedPacket> STREAM_CODEC =
            StreamCodec.unit(new StorageChangedPacket());

    @Override
    public Type<StorageChangedPacket> type() {
        return TYPE;
    }

    public static void handle(StorageChangedPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof BrowserHost host) {
                host.onStorageListChanged();
            }
        });
    }
}
