package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.menu.StorageBrowserMenu;
import com.pokeemc.storage.StorageAccessService;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.StorageHandleExt;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Map;

/**
 * 客户端 -> 服务器：请求选中仓储的完整槽位快照（Task 9）。
 *
 * <p>服务端重新解析仓储、打开句柄并读取最新快照；无 {@code VIEW} 权限时
 * 槽位内容不下发（返回空槽位表，revision 仍下发供客户端判断过期）。
 * 客户端收到后由 {@code StorageViewModel.applySnapshot} 做增量比对（高亮变化槽位）。</p>
 */
public record StorageSnapshotPacket(
        String sessionId,
        StorageId storageId) implements CustomPacketPayload {

    public static final int MAX_SESSION_FIELD_LENGTH = 64;

    public static final Type<StorageSnapshotPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageSnapshotPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageSnapshotPacket decode(RegistryFriendlyByteBuf buf) {
                    return new StorageSnapshotPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            StoragePayloadCodecs.STORAGE_ID.decode(buf));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageSnapshotPacket packet) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                    StoragePayloadCodecs.STORAGE_ID.encode(buf, packet.storageId());
                }
            };

    @Override
    public Type<StorageSnapshotPacket> type() {
        return TYPE;
    }

    // ---------------------------------------------------------------- 响应

    /**
     * 服务端 -> 客户端：快照数据（revision + 槽位表 + 是否有 VIEW）。
     */
    public record Response(
            String sessionId,
            StorageId storageId,
            long revision,
            Map<Integer, StorageItemSlot> slots,
            boolean viewAllowed) implements CustomPacketPayload {

        public static final Type<Response> RESPONSE_TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_snapshot_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Response> RESPONSE_CODEC =
                new StreamCodec<>() {
                    @Override
                    public Response decode(RegistryFriendlyByteBuf buf) {
                        return new Response(
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                StoragePayloadCodecs.STORAGE_ID.decode(buf),
                                buf.readLong(),
                                StoragePayloadCodecs.SLOT_MAP.decode(buf),
                                buf.readBoolean());
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, Response packet) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                        StoragePayloadCodecs.STORAGE_ID.encode(buf, packet.storageId());
                        buf.writeLong(packet.revision());
                        StoragePayloadCodecs.SLOT_MAP.encode(buf, packet.slots());
                        buf.writeBoolean(packet.viewAllowed());
                    }
                };

        @Override
        public Type<Response> type() {
            return RESPONSE_TYPE;
        }

        public static void handleResponse(Response packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (net.minecraft.client.Minecraft.getInstance().screen
                        instanceof com.pokeemc.client.BrowserHost host) {
                    host.onSnapshotResponse(packet);
                }
            });
        }
    }

    // ---------------------------------------------------------------- 服务端执行

    public static void handle(StorageSnapshotPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                Response response = executeSnapshot(player, packet);
                if (response != null) {
                    PacketDistributor.sendToPlayer(player, response);
                }
            }
        });
    }

    /**
     * 服务端执行入口（独立静态方法便于测试）。
     */
    public static Response executeSnapshot(ServerPlayer player, StorageSnapshotPacket packet) {
        if (packet.sessionId() == null || packet.sessionId().isBlank()
                || packet.sessionId().length() > MAX_SESSION_FIELD_LENGTH) {
            return null;
        }
        if (!(player.containerMenu instanceof StorageBrowserMenu)) {
            return null; // 必须处于仓储浏览器/转化桌菜单会话
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        StorageSavedData data = server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
        StorageId sid = packet.storageId();
        StorageKey key = StorageKey.of(sid.dimension(), sid.adapterType(), sid.location());
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            return new Response(packet.sessionId(), sid, -1, Map.of(), false);
        }

        StorageAccessService.AccessSnapshot snapshot =
                new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
        boolean view = StorageServices.access().canView(player.getUUID(), snapshot);

        StorageAdapter adapter = StorageServices.registry().byTypeId(sid.adapterType()).orElse(null);
        if (adapter == null) {
            return new Response(packet.sessionId(), sid, record.revision(), Map.of(), view);
        }
        if (!adapter.capabilities().contains(StorageCapability.SNAPSHOT)) {
            return new Response(packet.sessionId(), sid, record.revision(), Map.of(), view);
        }
        StorageAdapterContext context = new StorageAdapterContext(sid);
        if (!adapter.supports(context)) {
            return new Response(packet.sessionId(), sid, record.revision(), Map.of(), view);
        }
        try (StorageHandle handle = adapter.open(context).orElse(null)) {
            if (handle == null) {
                return new Response(packet.sessionId(), sid, record.revision(), Map.of(), view);
            }
            StorageSnapshot snapshotData = handle.snapshot();
            Map<Integer, StorageItemSlot> slots = view ? snapshotData.slots() : Map.of();
            // 权威 revision 是持久化记录 revision，而不是容器句柄的内容戳
            // （普通箱子等适配器句柄 revision 恒为 0）：否则事务一旦递增过记录
            // revision，客户端快照仍报 0，出售/移动会永远触发 revision 冲突。
            return new Response(packet.sessionId(), sid, record.revision(), slots, view);
        } catch (RuntimeException e) {
            return new Response(packet.sessionId(), sid, record.revision(), Map.of(), view);
        }
    }
}
