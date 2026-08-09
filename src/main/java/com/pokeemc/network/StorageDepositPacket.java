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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 客户端 -> 服务器：把玩家背包物品「一键存入」选中的附近仓储。
 *
 * <p>客户端只提交背包槽位与数量；目标槽位由服务端在仓储句柄上自动选择
 * （先合并同物品、再找空槽），逐行复用 {@link StorageTransactionService} 的
 * 原子事务执行。每行使用独立 operationId，服务端幂等缓存保证重试不重复移动。
 * 执行完成后回执 {@link Response}，客户端据此刷新快照并展示结果。</p>
 */
public record StorageDepositPacket(
        String sessionId,
        String operationId,
        StorageId storageId,
        long expectedRevision,
        List<DepositLine> lines) implements CustomPacketPayload {

    /** 单次最多处理的背包槽位数（主背包 36 格）。 */
    public static final int MAX_LINES = 36;

    public static final int MAX_SESSION_FIELD_LENGTH = 64;

    public static final double MAX_OPERATION_DISTANCE_BLOCKS = 8.0;

    public static final Type<StorageDepositPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_deposit"));

    public record DepositLine(int inventorySlot, int count) {
    }

    private static final StreamCodec<ByteBuf, StorageId> STORAGE_ID_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StorageId::dimension,
            ByteBufCodecs.STRING_UTF8, StorageId::adapterType,
            ByteBufCodecs.STRING_UTF8, StorageId::location,
            StorageId::new);

    private static final StreamCodec<ByteBuf, DepositLine> LINE_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DepositLine::inventorySlot,
            ByteBufCodecs.VAR_INT, DepositLine::count,
            DepositLine::new);

    private static final StreamCodec<ByteBuf, List<DepositLine>> LINES_CODEC = new StreamCodec<>() {
        @Override
        public List<DepositLine> decode(ByteBuf buf) {
            int size = ByteBufCodecs.VAR_INT.decode(buf);
            if (size < 0 || size > MAX_LINES) {
                throw new IllegalArgumentException("bad deposit lines size: " + size);
            }
            List<DepositLine> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                out.add(LINE_CODEC.decode(buf));
            }
            return out;
        }

        @Override
        public void encode(ByteBuf buf, List<DepositLine> list) {
            ByteBufCodecs.VAR_INT.encode(buf, list.size());
            for (DepositLine line : list) {
                LINE_CODEC.encode(buf, line);
            }
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageDepositPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageDepositPacket decode(RegistryFriendlyByteBuf buf) {
                    return new StorageDepositPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            STORAGE_ID_CODEC.decode(buf),
                            buf.readLong(),
                            LINES_CODEC.decode(buf));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageDepositPacket packet) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.operationId());
                    STORAGE_ID_CODEC.encode(buf, packet.storageId());
                    buf.writeLong(packet.expectedRevision());
                    LINES_CODEC.encode(buf, packet.lines());
                }
            };

    @Override
    public Type<StorageDepositPacket> type() {
        return TYPE;
    }

    // ---------------------------------------------------------------- 响应

    public record Response(
            String sessionId,
            boolean success,
            String code,
            String message,
            int movedLines,
            int totalLines) implements CustomPacketPayload {

        public static final Type<Response> RESPONSE_TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_deposit_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Response> RESPONSE_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, Response::sessionId,
                        ByteBufCodecs.BOOL, Response::success,
                        ByteBufCodecs.STRING_UTF8, Response::code,
                        ByteBufCodecs.STRING_UTF8, Response::message,
                        ByteBufCodecs.VAR_INT, Response::movedLines,
                        ByteBufCodecs.VAR_INT, Response::totalLines,
                        Response::new);

        @Override
        public Type<Response> type() {
            return RESPONSE_TYPE;
        }

        public static void handleResponse(Response packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (net.minecraft.client.Minecraft.getInstance().screen
                        instanceof com.pokeemc.client.BrowserHost host) {
                    host.onDepositResponse(packet);
                }
            });
        }
    }

    // ---------------------------------------------------------------- 服务端执行

    public static void handle(StorageDepositPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                Response response = executeDeposit(player, packet);
                if (response != null) {
                    PacketDistributor.sendToPlayer(player, response);
                }
            }
        });
    }

    /** 服务端执行入口（独立静态方法便于测试）。 */
    public static Response executeDeposit(ServerPlayer player, StorageDepositPacket packet) {
        if (isBlankOrTooLong(packet.sessionId()) || isBlankOrTooLong(packet.operationId())) {
            return new Response(packet.sessionId(), false, "invalid_session",
                    "invalid or missing session/operation id", 0, packet.lines() == null ? 0 : packet.lines().size());
        }
        if (packet.lines() == null || packet.lines().isEmpty()) {
            return new Response(packet.sessionId(), false, "invalid_request",
                    "deposit lines are empty", 0, 0);
        }
        if (packet.lines().size() > MAX_LINES) {
            return new Response(packet.sessionId(), false, "invalid_request",
                    "too many deposit lines", 0, packet.lines().size());
        }
        if (!(player.containerMenu instanceof StorageBrowserMenu)) {
            return new Response(packet.sessionId(), false, "invalid_menu",
                    "deposit requires a storage browser or exchange menu", 0, packet.lines().size());
        }
        for (DepositLine line : packet.lines()) {
            if (line.inventorySlot() < 0 || line.inventorySlot() >= 36 || line.count() <= 0) {
                return new Response(packet.sessionId(), false, "invalid_request",
                        "invalid inventory slot or count", 0, packet.lines().size());
            }
        }
        boolean virtual = VanillaEnderChestAdapter.isEnderChest(packet.storageId());
        BlockPos pos = virtual ? null : AbstractContainerAdapter.parsePos(packet.storageId().location());
        if (!virtual && pos == null) {
            return new Response(packet.sessionId(), false, "invalid_location",
                    "storage location malformed", 0, packet.lines().size());
        }
        if (!virtual) {
            double distanceSq = player.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            double maxSq = MAX_OPERATION_DISTANCE_BLOCKS * MAX_OPERATION_DISTANCE_BLOCKS;
            if (distanceSq > maxSq) {
                return new Response(packet.sessionId(), false, "distance_exceeded",
                        "too far from storage", 0, packet.lines().size());
            }
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return new Response(packet.sessionId(), false, "server_unavailable",
                    "server not available", 0, packet.lines().size());
        }
        StorageSavedData data = server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
        StorageKey key = StorageKey.of(packet.storageId().dimension(),
                packet.storageId().adapterType(), packet.storageId().location());
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            return new Response(packet.sessionId(), false, "not_found",
                    "storage not claimed", 0, packet.lines().size());
        }
        StorageAccessService.AccessSnapshot snapshot =
                new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
        if (!StorageServices.access().canDeposit(player.getUUID(), snapshot)) {
            return new Response(packet.sessionId(), false, "permission_denied",
                    "deposit not allowed", 0, packet.lines().size());
        }
        if (packet.expectedRevision() >= 0 && packet.expectedRevision() != record.revision()) {
            return new Response(packet.sessionId(), false, "revision_conflict",
                    "storage changed; refresh and retry", 0, packet.lines().size());
        }
        StorageAdapter adapter = StorageServices.registry().byTypeId(packet.storageId().adapterType()).orElse(null);
        if (adapter == null) {
            return new Response(packet.sessionId(), false, "adapter_unavailable",
                    "unknown adapter type", 0, packet.lines().size());
        }
        StorageAdapterContext context = new StorageAdapterContext(packet.storageId());
        if (!adapter.supports(context)) {
            return new Response(packet.sessionId(), false, "adapter_unavailable",
                    "container not supported", 0, packet.lines().size());
        }
        try (StorageHandle handle = adapter.open(context).orElse(null)) {
            if (!(handle instanceof StorageHandleExt ext)) {
                return new Response(packet.sessionId(), false, "adapter_unavailable",
                        "adapter does not support transactional slot access", 0, packet.lines().size());
            }
            int moved = 0;
            for (int i = 0; i < packet.lines().size(); i++) {
                DepositLine line = packet.lines().get(i);
                StorageEndpoint source = StorageEndpoint.inventory(line.inventorySlot());
                String itemId = inventoryItemId(player, line.inventorySlot());
                if (itemId == null) {
                    continue; // 槽位已空（背包可能已变化），跳过该行
                }
                int targetSlot = findDepositSlot(ext, itemId, line.count());
                if (targetSlot < 0) {
                    continue; // 仓储已满/无合并空间
                }
                StorageTransaction transaction = new StorageTransaction(
                        player.getUUID(),
                        packet.sessionId(),
                        packet.operationId() + "-" + i,
                        source,
                        StorageEndpoint.storage(packet.storageId(), targetSlot),
                        line.count(),
                        0L,
                        0L,
                        Map.of());
                StorageTransactionResult result = StorageServices.storageService().execute(transaction);
                if (result.success()) {
                    moved++;
                }
            }
            boolean success = moved > 0;
            return new Response(packet.sessionId(), success,
                    success ? (moved == packet.lines().size() ? "success" : "partial") : "no_space",
                    success ? "deposited " + moved + "/" + packet.lines().size() + " lines"
                            : "nothing could be deposited",
                    moved, packet.lines().size());
        } catch (RuntimeException e) {
            PokeEMC.LOGGER.warn("PokeEMC: storage deposit failed: {}", e.toString());
            return new Response(packet.sessionId(), false, "internal_error",
                    "deposit failed", 0, packet.lines().size());
        }
    }

    /** 自动选择目标槽位：优先合并同物品，再找空槽（存入/转移共用）。 */
    static int findDepositSlot(StorageHandleExt handle, String itemId, int count) {
        for (int i = 0; i < handle.slotCount(); i++) {
            String existing = handle.itemId(i);
            if (existing != null && existing.equals(itemId) && handle.simulateInsert(i, itemId, count)) {
                return i;
            }
        }
        for (int i = 0; i < handle.slotCount(); i++) {
            if (handle.itemId(i) == null && handle.simulateInsert(i, itemId, count)) {
                return i;
            }
        }
        return -1;
    }

    /** 读取玩家主背包指定槽位的注册表物品 ID；空槽返回 null。 */
    private static String inventoryItemId(ServerPlayer player, int slot) {
        net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(slot);
        if (stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? null : id.toString();
    }

    private static boolean isBlankOrTooLong(String value) {
        return value == null || value.isBlank() || value.length() > MAX_SESSION_FIELD_LENGTH;
    }
}
