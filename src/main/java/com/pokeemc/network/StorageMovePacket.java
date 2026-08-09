package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
import com.poketrade.api.storage.StorageEndpoint;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageTransaction;
import com.poketrade.api.storage.StorageTransactionResult;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端 -> 服务器：把一个仓储槽位事务步骤（移动/存入/取出）提交到服务端执行。
 *
 * <p>客户端把点击、右键拆分、shift-click、数字键、双击与拖拽分配解析为
 * 槽位级移动步骤后打包发送。服务端不信任客户端：收到后重新校验会话字段、
 * 玩家与仓储距离、端点合法性，再交给 {@link StorageServices#storageService()}
 * 在服务端线程执行。重复包（相同 sessionId+operationId）由事务服务幂等返回
 * 首次结果，不会二次移动物品。</p>
 */
public record StorageMovePacket(
        String sessionId,
        String operationId,
        long expectedMenuRevision,
        StorageEndpoint source,
        StorageEndpoint target,
        int requestedCount,
        long sourceFingerprint,
        long targetFingerprint,
        Map<StorageId, Long> expectedRevisions) implements CustomPacketPayload {

    /** 玩家可操作的仓储最大距离（格）。 */
    public static final double MAX_OPERATION_DISTANCE_BLOCKS = 8.0;

    /** 会话/操作标识的最大长度，防止恶意超长字符串。 */
    public static final int MAX_SESSION_FIELD_LENGTH = 64;

    public static final Type<StorageMovePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_move"));

    private static final StreamCodec<ByteBuf, StorageId> STORAGE_ID_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StorageId::dimension,
            ByteBufCodecs.STRING_UTF8, StorageId::adapterType,
            ByteBufCodecs.STRING_UTF8, StorageId::location,
            StorageId::new);

    private static final StreamCodec<ByteBuf, StorageEndpoint> ENDPOINT_CODEC = new StreamCodec<>() {
        @Override
        public StorageEndpoint decode(ByteBuf buf) {
            if (buf.readBoolean()) {
                return StorageEndpoint.storage(
                        STORAGE_ID_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
            }
            return StorageEndpoint.inventory(ByteBufCodecs.VAR_INT.decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, StorageEndpoint endpoint) {
            buf.writeBoolean(endpoint.kind() == StorageEndpoint.Kind.STORAGE);
            if (endpoint.kind() == StorageEndpoint.Kind.STORAGE) {
                STORAGE_ID_CODEC.encode(buf, endpoint.storageId());
            }
            ByteBufCodecs.VAR_INT.encode(buf, endpoint.slotIndex());
        }
    };

    private static final StreamCodec<ByteBuf, Map<StorageId, Long>> REVISION_MAP_CODEC = new StreamCodec<>() {
        @Override
        public Map<StorageId, Long> decode(ByteBuf buf) {
            int size = ByteBufCodecs.VAR_INT.decode(buf);
            Map<StorageId, Long> map = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) {
                map.put(STORAGE_ID_CODEC.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf));
            }
            return map;
        }

        @Override
        public void encode(ByteBuf buf, Map<StorageId, Long> map) {
            ByteBufCodecs.VAR_INT.encode(buf, map.size());
            for (Map.Entry<StorageId, Long> entry : map.entrySet()) {
                STORAGE_ID_CODEC.encode(buf, entry.getKey());
                ByteBufCodecs.VAR_LONG.encode(buf, entry.getValue());
            }
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageMovePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageMovePacket decode(RegistryFriendlyByteBuf buf) {
                    return new StorageMovePacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            ENDPOINT_CODEC.decode(buf),
                            ENDPOINT_CODEC.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            REVISION_MAP_CODEC.decode(buf));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageMovePacket packet) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.operationId());
                    ByteBufCodecs.VAR_LONG.encode(buf, packet.expectedMenuRevision());
                    ENDPOINT_CODEC.encode(buf, packet.source());
                    ENDPOINT_CODEC.encode(buf, packet.target());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.requestedCount());
                    ByteBufCodecs.VAR_LONG.encode(buf, packet.sourceFingerprint());
                    ByteBufCodecs.VAR_LONG.encode(buf, packet.targetFingerprint());
                    REVISION_MAP_CODEC.encode(buf, packet.expectedRevisions());
                }
            };

    @Override
    public Type<StorageMovePacket> type() {
        return TYPE;
    }

    public static void handle(StorageMovePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StorageTransactionResult result = executeMove(player, packet);
                PokeEMC.LOGGER.debug("storage move {} -> {} = {} ({})",
                        packet.source(), packet.target(), result.code(), result.message());
                PacketDistributor.sendToPlayer(player, new Response(
                        packet.sessionId(), result.success(), result.code(), result.message()));
            }
        });
    }

    // ---------------------------------------------------------------- 响应

    /**
     * 服务端 -> 客户端：移动（取出/存入/转移）执行回执。
     */
    public record Response(
            String sessionId,
            boolean success,
            String code,
            String message) implements CustomPacketPayload {

        public static final Type<Response> RESPONSE_TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_move_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Response> RESPONSE_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Response::sessionId,
                        ByteBufCodecs.BOOL, Response::success,
                        ByteBufCodecs.STRING_UTF8, Response::code,
                        ByteBufCodecs.STRING_UTF8, Response::message,
                        Response::new);

        @Override
        public Type<Response> type() {
            return RESPONSE_TYPE;
        }

        public static void handleResponse(Response packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (net.minecraft.client.Minecraft.getInstance().screen
                        instanceof com.pokeemc.client.BrowserHost host) {
                    host.onMoveResponse(packet);
                }
            });
        }
    }

    /**
     * 服务端执行入口：会话字段校验、距离校验、端点合法性校验后构造事务并执行。
     * 独立成静态方法便于后续直接调用/测试。
     */
    public static StorageTransactionResult executeMove(ServerPlayer player, StorageMovePacket packet) {
        if (isBlankOrTooLong(packet.sessionId())) {
            return StorageTransactionResult.failure("invalid_session", "invalid or missing session id");
        }
        if (isBlankOrTooLong(packet.operationId())) {
            return StorageTransactionResult.failure("invalid_operation", "invalid or missing operation id");
        }
        if (packet.requestedCount() <= 0) {
            return StorageTransactionResult.failure("invalid_count", "requested count must be positive");
        }
        if (packet.source().equals(packet.target())) {
            return StorageTransactionResult.failure(
                    "invalid_endpoints", "source and target must differ");
        }
        for (StorageEndpoint endpoint : List.of(packet.source(), packet.target())) {
            if (endpoint.kind() == StorageEndpoint.Kind.STORAGE) {
                boolean virtual = VanillaEnderChestAdapter.isEnderChest(endpoint.storageId());
                BlockPos pos = virtual ? null
                        : AbstractContainerAdapter.parsePos(endpoint.storageId().location());
                if (!virtual && pos == null) {
                    return StorageTransactionResult.failure(
                            "invalid_location", "storage location malformed");
                }
                if (!virtual) {
                    double distanceSq = player.distanceToSqr(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    double maxSq = MAX_OPERATION_DISTANCE_BLOCKS * MAX_OPERATION_DISTANCE_BLOCKS;
                    if (distanceSq > maxSq) {
                        return StorageTransactionResult.failure(
                                "distance_exceeded", "too far from storage");
                    }
                }
            }
        }
        try {
            StorageTransaction transaction = new StorageTransaction(
                    player.getUUID(),
                    packet.sessionId(),
                    packet.operationId(),
                    packet.source(),
                    packet.target(),
                    packet.requestedCount(),
                    packet.sourceFingerprint(),
                    packet.targetFingerprint(),
                    packet.expectedRevisions());
            return StorageServices.storageService().execute(transaction);
        } catch (IllegalArgumentException e) {
            return StorageTransactionResult.failure("invalid_request", e.getMessage());
        }
    }

    private static boolean isBlankOrTooLong(String value) {
        return value == null || value.isBlank() || value.length() > MAX_SESSION_FIELD_LENGTH;
    }
}
