package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.menu.StorageBrowserMenu;
import com.pokeemc.storage.StorageAccessService;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
import com.pokeemc.storage.adapter.StorageHandleExt;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 客户端 -> 服务器：把仓储槽位物品“拿起”到鼠标（容器 carried 栈），
 * 与原版箱子左键拾取手感一致。服务端校验距离/权限/修订/槽位内容后
 * 扣减仓储并放入 carried；回执复用 {@link StorageMovePacket.Response}。
 */
public record StorageWithdrawCarriedPacket(
        String sessionId,
        String operationId,
        StorageId storageId,
        int slotIndex,
        long fingerprint,
        long expectedRevision) implements CustomPacketPayload {

    public static final int MAX_SESSION_FIELD_LENGTH = 64;

    public static final double MAX_OPERATION_DISTANCE_BLOCKS = 8.0;

    public static final Type<StorageWithdrawCarriedPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_withdraw_carried"));

    private static final StreamCodec<ByteBuf, StorageId> STORAGE_ID_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StorageId::dimension,
            ByteBufCodecs.STRING_UTF8, StorageId::adapterType,
            ByteBufCodecs.STRING_UTF8, StorageId::location,
            StorageId::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageWithdrawCarriedPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageWithdrawCarriedPacket decode(RegistryFriendlyByteBuf buf) {
                    return new StorageWithdrawCarriedPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            STORAGE_ID_CODEC.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.VAR_LONG.decode(buf),
                            buf.readLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageWithdrawCarriedPacket packet) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.operationId());
                    STORAGE_ID_CODEC.encode(buf, packet.storageId());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.slotIndex());
                    ByteBufCodecs.VAR_LONG.encode(buf, packet.fingerprint());
                    buf.writeLong(packet.expectedRevision());
                }
            };

    @Override
    public Type<StorageWithdrawCarriedPacket> type() {
        return TYPE;
    }

    public static void handle(StorageWithdrawCarriedPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StorageMovePacket.Response response = executeWithdrawCarried(player, packet);
                if (response != null) {
                    PacketDistributor.sendToPlayer(player, response);
                }
            }
        });
    }

    /** 服务端执行入口（独立静态方法便于测试）。 */
    public static StorageMovePacket.Response executeWithdrawCarried(
            ServerPlayer player, StorageWithdrawCarriedPacket packet) {
        if (isBlankOrTooLong(packet.sessionId()) || isBlankOrTooLong(packet.operationId())) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_session",
                    "invalid or missing session/operation id");
        }
        if (packet.slotIndex() < 0) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_request",
                    "invalid slot index");
        }
        if (!(player.containerMenu instanceof StorageBrowserMenu)) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_menu",
                    "withdraw requires a storage browser or exchange menu");
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_request",
                    "cursor already holds an item");
        }
        boolean virtual = VanillaEnderChestAdapter.isEnderChest(packet.storageId());
        BlockPos pos = virtual ? null : AbstractContainerAdapter.parsePos(packet.storageId().location());
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
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "server_unavailable",
                    "server not available");
        }
        StorageSavedData data = server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
        StorageKey key = StorageKey.of(packet.storageId().dimension(),
                packet.storageId().adapterType(), packet.storageId().location());
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "not_found",
                    "storage not claimed");
        }
        StorageAccessService.AccessSnapshot snapshot =
                new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
        if (!StorageServices.access().canWithdraw(player.getUUID(), snapshot)) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "permission_denied",
                    "withdraw not allowed");
        }
        if (packet.expectedRevision() >= 0 && packet.expectedRevision() != record.revision()) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "revision_conflict",
                    "storage changed; refresh and retry");
        }
        StorageAdapter adapter = StorageServices.registry().byTypeId(packet.storageId().adapterType()).orElse(null);
        if (adapter == null) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "adapter_unavailable",
                    "unknown adapter type");
        }
        StorageAdapterContext context = new StorageAdapterContext(packet.storageId());
        if (!adapter.supports(context)) {
            return new StorageMovePacket.Response(packet.sessionId(), false, "adapter_unavailable",
                    "container not supported");
        }
        try (StorageHandle handle = adapter.open(context).orElse(null)) {
            if (!(handle instanceof StorageHandleExt ext)) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "adapter_unavailable",
                        "adapter does not support transactional slot access");
            }
            if (packet.slotIndex() >= ext.slotCount()) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "invalid_request",
                        "slot out of bounds");
            }
            String itemId = ext.itemId(packet.slotIndex());
            int count = ext.count(packet.slotIndex());
            if (itemId == null || count <= 0) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "source_empty",
                        "source slot is empty");
            }
            if (packet.fingerprint() != 0
                    && ext.fingerprint(packet.slotIndex()) != packet.fingerprint()) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "content_changed",
                        "slot content changed");
            }
            ext.commitExtract(packet.slotIndex(), itemId, count);
            ResourceLocation rl = ResourceLocation.parse(itemId);
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(rl), count);
            player.containerMenu.setCarried(stack);
            player.containerMenu.broadcastChanges();
            long newRev = data.updateRecord(key, record.revision(), r -> r)
                    ? record.revision() + 1 : -1;
            if (newRev < 0) {
                return new StorageMovePacket.Response(packet.sessionId(), false, "revision_conflict",
                        "revision conflict on storage");
            }
            data.appendAudit(System.currentTimeMillis(), key.asString(), player.getUUID(),
                    "withdraw", record.displayName() + " 拿起 " + count + "x" + itemId);
            return new StorageMovePacket.Response(packet.sessionId(), true, "success",
                    "picked up " + count + "x" + itemId);
        } catch (RuntimeException e) {
            PokeEMC.LOGGER.warn("PokeEMC: carried withdraw failed: {}", e.toString());
            return new StorageMovePacket.Response(packet.sessionId(), false, "internal_error",
                    "withdraw failed");
        }
    }

    private static boolean isBlankOrTooLong(String value) {
        return value == null || value.isBlank() || value.length() > MAX_SESSION_FIELD_LENGTH;
    }
}
