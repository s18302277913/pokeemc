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
import com.pokeemc.storage.adapter.StorageHandleExt;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 客户端 -> 服务器：把鼠标“拿起”的背包物品（容器 carried 栈）拖入仓储指定槽位。
 *
 * <p>玩家从自己背包拿起物品后拖到左侧快照的某个格子上松开：客户端发送本包
 * （目标槽位 + 数量），服务端校验菜单会话、距离、DEPOSIT 权限、修订与槽位可插入性，
 * 成功后把 carried 栈扣减并写入仓储槽位。回执复用 {@link StorageDepositPacket.Response}。</p>
 */
public record StorageDepositCarriedPacket(
        String sessionId,
        String operationId,
        StorageId storageId,
        int targetSlot,
        long expectedRevision,
        int count) implements CustomPacketPayload {

    public static final int MAX_SESSION_FIELD_LENGTH = 64;

    public static final double MAX_OPERATION_DISTANCE_BLOCKS = 8.0;

    public static final Type<StorageDepositCarriedPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_deposit_carried"));

    private static final StreamCodec<ByteBuf, StorageId> STORAGE_ID_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StorageId::dimension,
            ByteBufCodecs.STRING_UTF8, StorageId::adapterType,
            ByteBufCodecs.STRING_UTF8, StorageId::location,
            StorageId::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageDepositCarriedPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageDepositCarriedPacket decode(RegistryFriendlyByteBuf buf) {
                    return new StorageDepositCarriedPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            STORAGE_ID_CODEC.decode(buf),
                            ByteBufCodecs.VAR_INT.decode(buf),
                            buf.readLong(),
                            ByteBufCodecs.VAR_INT.decode(buf));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageDepositCarriedPacket packet) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.operationId());
                    STORAGE_ID_CODEC.encode(buf, packet.storageId());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.targetSlot());
                    buf.writeLong(packet.expectedRevision());
                    ByteBufCodecs.VAR_INT.encode(buf, packet.count());
                }
            };

    @Override
    public Type<StorageDepositCarriedPacket> type() {
        return TYPE;
    }

    public static void handle(StorageDepositCarriedPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StorageDepositPacket.Response response = executeDepositCarried(player, packet);
                if (response != null) {
                    PacketDistributor.sendToPlayer(player, response);
                }
            }
        });
    }

    /** 服务端执行入口（独立静态方法便于测试）。 */
    public static StorageDepositPacket.Response executeDepositCarried(
            ServerPlayer player, StorageDepositCarriedPacket packet) {
        if (isBlankOrTooLong(packet.sessionId()) || isBlankOrTooLong(packet.operationId())) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "invalid_session",
                    "invalid or missing session/operation id", 0, 1);
        }
        if (packet.count() <= 0) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "invalid_request",
                    "invalid target slot or count", 0, 1);
        }
        if (!(player.containerMenu instanceof StorageBrowserMenu)) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "invalid_menu",
                    "deposit requires a storage browser or exchange menu", 0, 1);
        }
        boolean virtual = VanillaEnderChestAdapter.isEnderChest(packet.storageId());
        BlockPos pos = virtual ? null : AbstractContainerAdapter.parsePos(packet.storageId().location());
        if (!virtual && pos == null) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "invalid_location",
                    "storage location malformed", 0, 1);
        }
        if (!virtual) {
            double distanceSq = player.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            double maxSq = MAX_OPERATION_DISTANCE_BLOCKS * MAX_OPERATION_DISTANCE_BLOCKS;
            if (distanceSq > maxSq) {
                return new StorageDepositPacket.Response(packet.sessionId(), false, "distance_exceeded",
                        "too far from storage", 0, 1);
            }
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "server_unavailable",
                    "server not available", 0, 1);
        }
        StorageSavedData data = server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
        StorageKey key = StorageKey.of(packet.storageId().dimension(),
                packet.storageId().adapterType(), packet.storageId().location());
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "not_found",
                    "storage not claimed", 0, 1);
        }
        StorageAccessService.AccessSnapshot snapshot =
                new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
        if (!StorageServices.access().canDeposit(player.getUUID(), snapshot)) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "permission_denied",
                    "deposit not allowed", 0, 1);
        }
        if (packet.expectedRevision() >= 0 && packet.expectedRevision() != record.revision()) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "revision_conflict",
                    "storage changed; refresh and retry", 0, 1);
        }
        StorageAdapter adapter = StorageServices.registry().byTypeId(packet.storageId().adapterType()).orElse(null);
        if (adapter == null) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "adapter_unavailable",
                    "unknown adapter type", 0, 1);
        }
        StorageAdapterContext context = new StorageAdapterContext(packet.storageId());
        if (!adapter.supports(context)) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "adapter_unavailable",
                    "container not supported", 0, 1);
        }
        try (StorageHandle handle = adapter.open(context).orElse(null)) {
            if (!(handle instanceof StorageHandleExt ext)) {
                return new StorageDepositPacket.Response(packet.sessionId(), false, "adapter_unavailable",
                        "adapter does not support transactional slot access", 0, 1);
            }
            AbstractContainerMenu menu = player.containerMenu;
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty() || packet.count() > carried.getCount()) {
                return new StorageDepositPacket.Response(packet.sessionId(), false, "invalid_request",
                        "carried item missing or count too high", 0, 1);
            }
            if (packet.targetSlot() < 0) {
                // 自动找槽位（拖到仓储列表项/空白处转移）
                int auto = StorageDepositPacket.findDepositSlot(
                        ext, BuiltInRegistries.ITEM.getKey(carried.getItem()).toString(), packet.count());
                if (auto < 0) {
                    return new StorageDepositPacket.Response(packet.sessionId(), false, "target_blocked",
                            "target storage has no room", 0, 1);
                }
                return executeAtSlot(player, data, record, menu, carried, packet, ext, auto);
            }
            if (packet.targetSlot() >= ext.slotCount()) {
                return new StorageDepositPacket.Response(packet.sessionId(), false, "invalid_request",
                        "target slot out of bounds", 0, 1);
            }
            return executeAtSlot(player, data, record, menu, carried, packet, ext, packet.targetSlot());
        } catch (RuntimeException e) {
            PokeEMC.LOGGER.warn("PokeEMC: carried deposit failed: {}", e.toString());
            return new StorageDepositPacket.Response(packet.sessionId(), false, "internal_error",
                    "deposit failed", 0, 1);
        }
    }

    private static StorageDepositPacket.Response executeAtSlot(
            ServerPlayer player, StorageSavedData data, StorageRecord record,
            AbstractContainerMenu menu, ItemStack carried, StorageDepositCarriedPacket packet,
            StorageHandleExt ext, int targetSlot) {
        if (carried.isEmpty() || packet.count() > carried.getCount()) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "invalid_request",
                    "carried item missing or count too high", 0, 1);
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(carried.getItem());
        String itemId = id == null ? null : id.toString();
        if (itemId == null) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "invalid_request",
                    "carried item has no registry id", 0, 1);
        }
        if (!ext.simulateInsert(targetSlot, itemId, packet.count())) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "target_blocked",
                    "cannot insert into that slot", 0, 1);
        }
        ext.commitInsert(targetSlot, itemId, packet.count());
        carried.shrink(packet.count());
        menu.broadcastChanges();
        long newRev = data.updateRecord(StorageKey.of(
                        packet.storageId().dimension(), packet.storageId().adapterType(),
                        packet.storageId().location()),
                record.revision(), r -> r) ? record.revision() + 1 : -1;
        if (newRev < 0) {
            return new StorageDepositPacket.Response(packet.sessionId(), false, "revision_conflict",
                    "revision conflict on storage", 0, 1);
        }
        data.appendAudit(System.currentTimeMillis(), StorageKey.of(
                        packet.storageId().dimension(), packet.storageId().adapterType(),
                        packet.storageId().location()).asString(),
                player.getUUID(), "deposit", record.displayName() + " 拖入 " + packet.count() + "x" + itemId);
        return new StorageDepositPacket.Response(packet.sessionId(), true, "success",
                "deposited carried item", 1, 1);
    }

    private static boolean isBlankOrTooLong(String value) {
        return value == null || value.isBlank() || value.length() > MAX_SESSION_FIELD_LENGTH;
    }
}
