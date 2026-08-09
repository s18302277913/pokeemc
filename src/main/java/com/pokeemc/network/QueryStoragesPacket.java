package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.menu.StorageBrowserMenu;
import com.pokeemc.storage.StorageAccessService;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.StorageRecord;
import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageQuery;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端 -> 服务器：仓储列表查询（范围/搜索/排序/过滤）。
 *
 * <p>服务端不信任客户端坐标：查询圆心取玩家当前方块位置，维度取玩家所在维度；
 * 半径钳制到 [1,512]、结果数钳制到 [1,200]，最终交给
 * {@link com.pokeemc.storage.discovery.StorageDiscoveryService#querySync} 执行
 * （服务端已按 VIEW 或可执行动作过滤）。响应 {@link Response} 除描述列表外，
 * 还携带每个仓储对当前玩家的六项权限掩码，供客户端排序/过滤展示。</p>
 */
public record QueryStoragesPacket(
        String sessionId,
        int radius,
        String searchText,
        StorageQuery.Sort sort,
        StorageQuery.Filter filter,
        int maxResults) implements CustomPacketPayload {

    public static final int MAX_RADIUS = 512;
    public static final int MAX_SESSION_FIELD_LENGTH = 64;

    public static final Type<QueryStoragesPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "query_storages"));

    private static final StreamCodec<ByteBuf, StorageQuery.Sort> SORT_CODEC =
            ByteBufCodecs.INT.map(i -> StorageQuery.Sort.values()[i], Enum::ordinal);

    private static final StreamCodec<ByteBuf, StorageQuery.Filter> FILTER_CODEC =
            ByteBufCodecs.INT.map(i -> StorageQuery.Filter.values()[i], Enum::ordinal);

    public static final StreamCodec<RegistryFriendlyByteBuf, QueryStoragesPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public QueryStoragesPacket decode(RegistryFriendlyByteBuf buf) {
                    return new QueryStoragesPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            SORT_CODEC.decode(buf),
                            FILTER_CODEC.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, QueryStoragesPacket packet) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.radius());
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.searchText());
                    SORT_CODEC.encode(buf, packet.sort());
                    FILTER_CODEC.encode(buf, packet.filter());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.maxResults());
                }
            };

    @Override
    public Type<QueryStoragesPacket> type() {
        return TYPE;
    }

    // ---------------------------------------------------------------- 响应

    /**
     * 服务端 -> 客户端：查询结果（描述列表 + 六项权限掩码）。
     */
    public record Response(
            String sessionId,
            List<StorageDescriptor> storages,
            Map<StorageId, EnumSet<StoragePermission>> permissions) implements CustomPacketPayload {

        public static final Type<Response> RESPONSE_TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "query_storages_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Response> RESPONSE_CODEC =
                new StreamCodec<>() {
                    @Override
                    public Response decode(RegistryFriendlyByteBuf buf) {
                        return new Response(
                                ByteBufCodecs.STRING_UTF8.decode(buf),
                                StoragePayloadCodecs.DESCRIPTOR_LIST.decode(buf),
                                readPermissionMap(buf));
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, Response packet) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                        StoragePayloadCodecs.DESCRIPTOR_LIST.encode(buf, packet.storages());
                        writePermissionMap(buf, packet.permissions());
                    }
                };

        private static void writePermissionMap(
                ByteBuf buf, Map<StorageId, EnumSet<StoragePermission>> map) {
            buf.writeInt(map.size());
            for (Map.Entry<StorageId, EnumSet<StoragePermission>> e : map.entrySet()) {
                StoragePayloadCodecs.STORAGE_ID.encode(buf, e.getKey());
                StoragePayloadCodecs.PERMISSION_SET.encode(buf, e.getValue());
            }
        }

        private static Map<StorageId, EnumSet<StoragePermission>> readPermissionMap(ByteBuf buf) {
            int n = buf.readInt();
            Map<StorageId, EnumSet<StoragePermission>> out = new LinkedHashMap<>(n);
            for (int i = 0; i < n; i++) {
                out.put(StoragePayloadCodecs.STORAGE_ID.decode(buf),
                        StoragePayloadCodecs.PERMISSION_SET.decode(buf));
            }
            return out;
        }

        @Override
        public Type<Response> type() {
            return RESPONSE_TYPE;
        }

        public static void handleResponse(Response packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (net.minecraft.client.Minecraft.getInstance().screen
                        instanceof com.pokeemc.client.BrowserHost host) {
                    host.onQueryResponse(packet);
                }
            });
        }
    }

    // ---------------------------------------------------------------- 服务端执行

    public static void handle(QueryStoragesPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                Response response = executeQuery(player, packet);
                if (response != null) {
                    PacketDistributor.sendToPlayer(player, response);
                }
            }
        });
    }

    /**
     * 服务端执行入口（独立静态方法便于测试）：会话校验 + 玩家位置 + 权限掩码计算。
     */
    public static Response executeQuery(ServerPlayer player, QueryStoragesPacket packet) {
        if (isBlankOrTooLong(packet.sessionId())) {
            return null;
        }
        if (!(player.containerMenu instanceof StorageBrowserMenu)) {
            return null; // 必须处于仓储浏览器/转化桌菜单会话
        }
        int radius = Math.max(1, Math.min(packet.radius(), MAX_RADIUS));
        int maxResults = Math.max(1, Math.min(packet.maxResults(), StorageQuery.HARD_MAX_RESULTS));
        BlockPos pos = player.blockPosition();
        StorageQuery query = new StorageQuery(
                player.getUUID(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getZ(),
                radius,
                packet.searchText(),
                packet.sort() == null ? StorageQuery.Sort.DISTANCE : packet.sort(),
                packet.filter() == null ? StorageQuery.Filter.VIEWABLE : packet.filter(),
                maxResults);
        List<StorageDescriptor> descriptors = StorageServices.discovery().querySync(query);

        Map<StorageId, EnumSet<StoragePermission>> permissions = new LinkedHashMap<>();
        for (StorageDescriptor descriptor : descriptors) {
            com.pokeemc.storage.StorageKey key = com.pokeemc.storage.StorageKey.of(
                    descriptor.storageId().dimension(),
                    descriptor.storageId().adapterType(),
                    descriptor.storageId().location());
            com.pokeemc.storage.StorageSavedData data = serverSavedData();
            StorageRecord record = data.getRecord(key).orElse(null);
            if (record == null) {
                continue;
            }
            StorageAccessService.AccessSnapshot snapshot =
                    new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
            EnumSet<StoragePermission> perms = EnumSet.noneOf(StoragePermission.class);
            if (StorageServices.access().canView(player.getUUID(), snapshot)) {
                perms.add(StoragePermission.VIEW);
            }
            if (StorageServices.access().canDeposit(player.getUUID(), snapshot)) {
                perms.add(StoragePermission.DEPOSIT);
            }
            if (StorageServices.access().canWithdraw(player.getUUID(), snapshot)) {
                perms.add(StoragePermission.WITHDRAW);
            }
            if (StorageServices.access().canSell(player.getUUID(), snapshot)) {
                perms.add(StoragePermission.SELL);
            }
            if (StorageServices.access().canBreak(player.getUUID(), snapshot)) {
                perms.add(StoragePermission.BREAK);
            }
            if (StorageServices.access().canManage(player.getUUID(), snapshot)) {
                perms.add(StoragePermission.MANAGE);
            }
            permissions.put(descriptor.storageId(), perms);
        }
        return new Response(packet.sessionId(), descriptors, permissions);
    }

    private static com.pokeemc.storage.StorageSavedData serverSavedData() {
        net.minecraft.server.MinecraftServer server =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("query requires server thread");
        }
        return server.overworld().getDataStorage().computeIfAbsent(
                com.pokeemc.storage.StorageSavedData.factory(),
                com.pokeemc.storage.StorageSavedData.DATA_NAME);
    }

    private static boolean isBlankOrTooLong(String value) {
        return value == null || value.isBlank() || value.length() > MAX_SESSION_FIELD_LENGTH;
    }
}
