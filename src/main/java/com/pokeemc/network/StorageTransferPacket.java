package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.menu.StorageBrowserMenu;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
import com.pokeemc.storage.adapter.StorageHandleExt;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageEndpoint;
import com.poketrade.api.storage.StorageHandle;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端 -> 服务器：把一个仓储槽位的物品转移到另一个仓储（自动选目标槽位）。
 *
 * <p>拖拽快照格到另一个仓储列表项时发送。服务端校验距离、权限、修订与槽位内容，
 * 通过 {@link StorageTransactionService} 执行源→目标仓储的原子事务；
 * 目标槽位优先合并同物品、再找空槽。回执复用 {@link StorageMovePacket.Response}。</p>
 */
public record StorageTransferPacket(
        String sessionId,
        String operationId,
        StorageId sourceStorageId,
        int sourceSlot,
        long sourceFingerprint,
        StorageId targetStorageId,
        int count,
        long sourceRevision,
        long targetRevision) implements CustomPacketPayload {

    public static final int MAX_SESSION_FIELD_LENGTH = 64;

    public static final double MAX_OPERATION_DISTANCE_BLOCKS = 8.0;

    public static final Type<StorageTransferPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_transfer"));

    private static final StreamCodec<ByteBuf, StorageId> STORAGE_ID_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StorageId::dimension,
            ByteBufCodecs.STRING_UTF8, StorageId::adapterType,
            ByteBufCodecs.STRING_UTF8, StorageId::location,
            StorageId::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageTransferPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageTransferPacket decode(RegistryFriendlyByteBuf buf) {
                    return new StorageTransferPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            STORAGE_ID_CODEC.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            STORAGE_ID_CODEC.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            buf.readLong(),
                            buf.readLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageTransferPacket packet) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.operationId());
                    STORAGE_ID_CODEC.encode(buf, packet.sourceStorageId());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.sourceSlot());
                    ByteBufCodecs.VAR_LONG.encode(buf, packet.sourceFingerprint());
                    STORAGE_ID_CODEC.encode(buf, packet.targetStorageId());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.count());
                    buf.writeLong(packet.sourceRevision());
                    buf.writeLong(packet.targetRevision());
                }
            };

    @Override
    public Type<StorageTransferPacket> type() {
        return TYPE;
    }

    public static void handle(StorageTransferPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StorageMovePacket.Response response = executeTransfer(player, packet);
                if (response != null) {
                    PacketDistributor.sendToPlayer(player, response);
                }
            }
        });
    }

    /** 服务端执行入口（独立静态方法便于测试）。 */
    public static StorageMovePacket.Response executeTransfer(
            ServerPlayer player, StorageTransferPacket packet) {
        if (isBlankOrTooLong(packet.sessionId()) || isBlankOrTooLong(packet.operationId())) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_session",
                    "invalid or missing session/operation id");
        }
        if (packet.count() <= 0 || packet.sourceSlot() < 0) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_request",
                    "invalid source slot or count");
        }
        if (packet.sourceStorageId().equals(packet.targetStorageId())) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_request",
                    "source and target storage must differ");
        }
        if (!(player.containerMenu instanceof StorageBrowserMenu)) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_menu",
                    "transfer requires a storage browser or exchange menu");
        }
        for (StorageId sid : java.util.List.of(packet.sourceStorageId(), packet.targetStorageId())) {
            boolean virtual = VanillaEnderChestAdapter.isEnderChest(sid);
            BlockPos pos = virtual ? null : AbstractContainerAdapter.parsePos(sid.location());
            if (!virtual && pos == null) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_location",
                        "storage location malformed");
            }
            if (!virtual) {
                double distanceSq = player.distanceToSqr(
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                double maxSq = MAX_OPERATION_DISTANCE_BLOCKS * MAX_OPERATION_DISTANCE_BLOCKS;
                if (distanceSq > maxSq) {
                    return new StorageMovePacket.Response(packet.sessionId(), false, "distance_exceeded",
                            "too far from storage");
                }
            }
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "server_unavailable",
                    "server not available");
        }
        StorageSavedData data = server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
        StorageRecord sourceRecord = recordOf(data, packet.sourceStorageId());
        StorageRecord targetRecord = recordOf(data, packet.targetStorageId());
        if (sourceRecord == null || targetRecord == null) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "not_found",
                    "storage not claimed");
        }
        if (packet.sourceRevision() >= 0 && packet.sourceRevision() != sourceRecord.revision()) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "revision_conflict",
                    "source storage changed; refresh and retry");
        }
        if (packet.targetRevision() >= 0 && packet.targetRevision() != targetRecord.revision()) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "revision_conflict",
                    "target storage changed; refresh and retry");
        }
        try (StorageHandle sourceHandle = openHandle(packet.sourceStorageId());
             StorageHandle targetHandle = openHandle(packet.targetStorageId())) {
            if (!(sourceHandle instanceof StorageHandleExt sourceExt)
                    || !(targetHandle instanceof StorageHandleExt targetExt)) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "adapter_unavailable",
                        "adapter does not support transactional slot access");
            }
            String itemId = sourceExt.itemId(packet.sourceSlot());
            if (itemId == null || sourceExt.count(packet.sourceSlot()) < packet.count()) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "source_empty",
                        "insufficient items at source slot");
            }
            if (packet.sourceFingerprint() != 0
                    && sourceExt.fingerprint(packet.sourceSlot()) != packet.sourceFingerprint()) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "content_changed",
                        "source slot content changed");
            }
            int targetSlot = StorageDepositPacket.findDepositSlot(targetExt, itemId, packet.count());
            if (targetSlot < 0) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "target_blocked",
                        "target storage has no room");
            }
            Map<StorageId, Long> revisions = new LinkedHashMap<>();
            revisions.put(packet.sourceStorageId(), sourceRecord.revision());
            revisions.put(packet.targetStorageId(), targetRecord.revision());
            StorageTransaction transaction = new StorageTransaction(
                    player.getUUID(),
                    packet.sessionId(),
                    packet.operationId(),
                    StorageEndpoint.storage(packet.sourceStorageId(), packet.sourceSlot()),
                    StorageEndpoint.storage(packet.targetStorageId(), targetSlot),
                    packet.count(),
                    packet.sourceFingerprint(),
                    0L,
                    revisions);
            StorageTransactionResult result = StorageServices.storageService().execute(transaction);
            return new StorageMovePacket.Response(packet.sessionId(),
                    result.success(), result.code(), result.message());
        } catch (RuntimeException e) {
            PokeEMC.LOGGER.warn("PokeEMC: storage transfer failed: {}", e.toString());
            return new StorageMovePacket.Response(packet.sessionId(), false, "internal_error",
                    "transfer failed");
        }
    }

    private static StorageRecord recordOf(StorageSavedData data, StorageId sid) {
        return data.getRecord(StorageKey.of(sid.dimension(), sid.adapterType(), sid.location()))
                .orElse(null);
    }

    private static StorageHandle openHandle(StorageId sid) {
        StorageAdapter adapter = StorageServices.registry().byTypeId(sid.adapterType()).orElse(null);
        if (adapter == null) {
            return null;
        }
        StorageAdapterContext context = new StorageAdapterContext(sid);
        if (!adapter.supports(context)) {
            return null;
        }
        return adapter.open(context).orElse(null);
    }

    private static boolean isBlankOrTooLong(String value) {
        return value == null || value.isBlank() || value.length() > MAX_SESSION_FIELD_LENGTH;
    }
}
