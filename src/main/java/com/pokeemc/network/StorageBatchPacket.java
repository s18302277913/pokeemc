package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.exchange.ExchangeService;
import com.pokeemc.exchange.price.ExchangePriceService;
import com.pokeemc.menu.ExchangeMenu;
import com.pokeemc.storage.StorageAccessService;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.StorageTransactionService;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.PokeballIdentity;
import com.pokeemc.storage.adapter.StorageHandleExt;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
import com.pokeemc.storage.discovery.StorageDiscoveryService;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageEndpoint;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageSnapshot;
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
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端 -> 服务器：仓储批量操作（会话 #16，任务 B）。右键菜单的 7 项批量操作
 * 中经网络执行的两类：
 * <ul>
 *   <li><b>WITHDRAW_ALL（同类，整箱）</b>：把当前仓储中与右键槽位同 itemId 的槽位
 *       批量取出合并到玩家背包（放不下的留在仓储）；</li>
 *   <li><b>SELL_ITEM（同类，附近箱子）</b>：在半径范围内扫描所有可售仓储中同 itemId 的槽位
 *       批量出售（服务端扫描 + 重新查价 + 权限 + revision 并发控制）。</li>
 * </ul>
 * SELL_ITEM/SELL_ALL 的「整箱」路径由客户端本地快照构造出售预览（复用现有确认流程），
 * 不经本包；本包仅承载服务端不可预览的执行路径。服务端不信任客户端：扫描、查价、
 * 权限、revision 均以服务端读到的为准。
 */
public record StorageBatchPacket(
        String sessionId,
        String operationId,
        Action action,
        Scope scope,
        StorageId storageId,
        String itemId,
        int radius,
        Map<StorageId, Long> expectedRevisions) implements CustomPacketPayload {

    /** 批量操作类型。 */
    public enum Action {
        /** 出售与 itemId 同类的槽位（「同类」；itemId 为空等价于 SELL_ALL）。 */
        SELL_ITEM,
        /** 出售全部可售槽位（「整箱全部」）。 */
        SELL_ALL,
        /** 取出与 itemId 同类的槽位到玩家背包（仅 CURRENT）。 */
        WITHDRAW_ALL
    }

    /** 批量操作作用域。 */
    public enum Scope {
        /** 当前选中仓储（客户端传 storageId）。 */
        CURRENT,
        /** 半径范围内的所有可售仓储（服务端扫描，客户端传 radius）。 */
        NEARBY
    }

    /** 会话/操作标识的最大长度，防止恶意超长字符串。 */
    public static final int MAX_SESSION_FIELD_LENGTH = 64;

    /** NEARBY 半径上限（防呆；正常档位最大 648）。 */
    public static final int MAX_RADIUS = 1024;

    public static final Type<StorageBatchPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_batch"));

    private static final StreamCodec<ByteBuf, StorageId> STORAGE_ID_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StorageId::dimension,
            ByteBufCodecs.STRING_UTF8, StorageId::adapterType,
            ByteBufCodecs.STRING_UTF8, StorageId::location,
            StorageId::new);

    private static final StreamCodec<ByteBuf, Map<StorageId, Long>> REVISION_MAP_CODEC =
            new StreamCodec<>() {
                @Override
                public Map<StorageId, Long> decode(ByteBuf buf) {
                    int size = ByteBufCodecs.VAR_INT.decode(buf);
                    if (size < 0 || size > ExchangeService.MAX_ENTRIES) {
                        throw new IllegalArgumentException("bad revision map size: " + size);
                    }
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

    /** 字段数 > 6，改用 {@link StreamCodec#of} 手动编解码（与 ExchangeCatalogPacket.Response 同式）。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, StorageBatchPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        ByteBufCodecs.STRING_UTF8.encode(buf, p.sessionId());
                        ByteBufCodecs.STRING_UTF8.encode(buf, p.operationId());
                        ByteBufCodecs.VAR_INT.encode(buf, p.action().ordinal());
                        ByteBufCodecs.VAR_INT.encode(buf, p.scope().ordinal());
                        boolean hasStorage = p.storageId() != null;
                        ByteBufCodecs.BOOL.encode(buf, hasStorage);
                        if (hasStorage) {
                            STORAGE_ID_CODEC.encode(buf, p.storageId());
                        }
                        boolean hasItem = p.itemId() != null;
                        ByteBufCodecs.BOOL.encode(buf, hasItem);
                        if (hasItem) {
                            ByteBufCodecs.STRING_UTF8.encode(buf, p.itemId());
                        }
                        ByteBufCodecs.VAR_INT.encode(buf, p.radius());
                        boolean hasRevisions = p.expectedRevisions() != null;
                        ByteBufCodecs.BOOL.encode(buf, hasRevisions);
                        if (hasRevisions) {
                            REVISION_MAP_CODEC.encode(buf, p.expectedRevisions());
                        }
                    },
                    buf -> new StorageBatchPacket(
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            ByteBufCodecs.STRING_UTF8.decode(buf),
                            fromOrdinal(Action.values(), ByteBufCodecs.VAR_INT.decode(buf), Action.SELL_ITEM),
                            fromOrdinal(Scope.values(), ByteBufCodecs.VAR_INT.decode(buf), Scope.CURRENT),
                            ByteBufCodecs.BOOL.decode(buf) ? STORAGE_ID_CODEC.decode(buf) : null,
                            ByteBufCodecs.BOOL.decode(buf) ? ByteBufCodecs.STRING_UTF8.decode(buf) : null,
                            ByteBufCodecs.VAR_INT.decode(buf),
                            ByteBufCodecs.BOOL.decode(buf) ? REVISION_MAP_CODEC.decode(buf) : null));

    /** 解码期序号边界防护：恶意/损坏的 ordinal 回退默认值，避免抛数组越界断连。 */
    private static <E extends Enum<E>> E fromOrdinal(E[] values, int ordinal, E fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    @Override
    public Type<StorageBatchPacket> type() {
        return TYPE;
    }

    public static void handle(StorageBatchPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                int previousNonce = player.containerMenu instanceof ExchangeMenu menu
                        ? menu.getResultNonce() : -1;
                StorageTransactionResult result = executeBatch(player, packet);
                // [CHANGED] 会话 #19：WITHDRAW_ALL 成功后也要写入结果通道（nonce++），
                // 客户端 updateTradeResult 据此刷新快照——此前仅失败才 report，
                // 批量取出成功后界面不刷新、槽位仍显示已取出物品（「右键菜单取出
                // 时刷新不及时」）。SELL_* 已由 runSell 内部 reportStorageResult +
                // broadcastChanges（nonce 已变），用 nonce 未变保护避免重复上报。
                if (player.containerMenu instanceof ExchangeMenu menu
                        && menu.getResultNonce() == previousNonce) {
                    menu.reportStorageResult(result);
                }
            }
        });
    }

    /**
     * 服务端执行入口：会话字段校验、交易所菜单会话校验、作用域解析（CURRENT 扫描当前仓储 /
     * NEARBY 服务端扫描）后交给 {@link ExchangeService}（出售）或逐槽仓储事务（取出）。
     * 独立成静态方法便于直接调用/测试。结果由菜单写入 DataSlot 供客户端展示。
     */
    public static StorageTransactionResult executeBatch(ServerPlayer player, StorageBatchPacket packet) {
        if (isBlankOrTooLong(packet.sessionId())) {
            return StorageTransactionResult.failure(
                    "invalid_session", "invalid or missing session id");
        }
        if (isBlankOrTooLong(packet.operationId())) {
            return StorageTransactionResult.failure(
                    "invalid_operation", "invalid or missing operation id");
        }
        if (packet.action() == null || packet.scope() == null) {
            return StorageTransactionResult.failure(
                    "invalid_request", "missing action or scope");
        }
        if (!(player.containerMenu instanceof ExchangeMenu menu)) {
            return StorageTransactionResult.failure(
                    "invalid_menu", "batch operation requires exchange menu");
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return StorageTransactionResult.failure(
                    "server_unavailable", "server not available");
        }
        StorageSavedData data = server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);

        if (packet.scope() == Scope.CURRENT) {
            return executeCurrent(player, packet, data, menu);
        }
        return executeNearby(player, packet, data, menu);
    }

    // ==================== CURRENT：当前选中仓储 ====================

    private static StorageTransactionResult executeCurrent(
            ServerPlayer player, StorageBatchPacket packet, StorageSavedData data, ExchangeMenu menu) {
        StorageId sid = packet.storageId();
        if (sid == null) {
            return StorageTransactionResult.failure(
                    "invalid_request", "current scope requires a storage id");
        }
        boolean virtual = VanillaEnderChestAdapter.isEnderChest(sid);
        BlockPos pos = virtual ? null : AbstractContainerAdapter.parsePos(sid.location());
        if (!virtual && pos == null) {
            return StorageTransactionResult.failure(
                    "invalid_location", "storage location malformed");
        }
        if (!virtual) {
            double distanceSq = player.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            double maxSq = StorageSellPacket.MAX_OPERATION_DISTANCE_BLOCKS
                    * StorageSellPacket.MAX_OPERATION_DISTANCE_BLOCKS;
            if (distanceSq > maxSq) {
                return StorageTransactionResult.failure(
                        "distance_exceeded", "too far from storage");
            }
        }
        // 服务端重新打开仓储扫描（不信任客户端快照）
        ScanOutcome scan = scanCurrent(player, packet, data, sid);
        if (!scan.ok()) {
            return StorageTransactionResult.failure(scan.code(), scan.message());
        }
        if (packet.action() == Action.WITHDRAW_ALL) {
            return withdrawAll(player, packet, scan);
        }
        // SELL_ITEM / SELL_ALL：交给 ExchangeService（聚合出售 + 幂等 + 审计）
        return menu.runSell(player, new StorageSellPacket(
                packet.sessionId(), packet.operationId(), scan.entries(), scan.revisions()));
    }

    /** 当前仓储槽位扫描：打开句柄遍历，SELL_* 收可售槽、WITHDRAW_ALL 收全部非空槽（同类过滤）。 */
    private static ScanOutcome scanCurrent(ServerPlayer player, StorageBatchPacket packet,
                                           StorageSavedData data, StorageId sid) {
        StorageKey key = StorageKey.of(sid.dimension(), sid.adapterType(), sid.location());
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            return ScanOutcome.failure("not_found", "storage not claimed");
        }
        StorageAccessService.AccessSnapshot snapshot =
                new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
        UUID actorId = player.getUUID();
        boolean withdraw = packet.action() == Action.WITHDRAW_ALL;
        if (withdraw) {
            if (!StorageServices.access().canWithdraw(actorId, snapshot)) {
                return ScanOutcome.failure("permission_denied", "withdraw not allowed");
            }
        } else if (!StorageServices.access().canSell(actorId, snapshot)) {
            return ScanOutcome.failure("permission_denied", "sell not allowed");
        }
        StorageAdapter adapter = StorageServices.registry().byTypeId(sid.adapterType()).orElse(null);
        if (adapter == null) {
            return ScanOutcome.failure("adapter_unavailable", "unknown adapter type");
        }
        StorageAdapterContext context = new StorageAdapterContext(sid);
        if (!adapter.supports(context)) {
            return ScanOutcome.failure("adapter_unavailable", "container not supported");
        }
        String itemId = packet.itemId() == null || packet.itemId().isBlank() ? null : packet.itemId();
        try (StorageHandle handle = adapter.open(context).orElse(null)) {
            if (!(handle instanceof StorageHandleExt ext)) {
                return ScanOutcome.failure("adapter_unavailable",
                        "adapter does not support transactional slot access");
            }
            List<ExchangeService.SellEntry> entries = new ArrayList<>();
            // WITHDRAW_ALL 需要源槽 itemId 定位背包合并槽（SellEntry 不含 itemId）
            Map<Integer, String> slotItems = new LinkedHashMap<>();
            boolean truncated = false;
            for (int i = 0; i < ext.slotCount(); i++) {
                String id = ext.itemId(i);
                int count = ext.count(i);
                if (id == null || count <= 0) {
                    continue;
                }
                if (itemId != null && !itemId.equals(id)) {
                    continue;
                }
                if (!withdraw) {
                    // 出售：跳过未定价/禁止交易物品，避免整包失败（由 ExchangeService 再查价把关）
                    if (!hasSellPrice(id)) {
                        continue;
                    }
                }
                entries.add(new ExchangeService.SellEntry(sid, i, count, ext.fingerprint(i)));
                if (withdraw) {
                    slotItems.put(i, id);
                }
                if (entries.size() >= ExchangeService.MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
            }
            return ScanOutcome.success(entries, Map.of(sid, record.revision()), truncated, slotItems);
        } catch (RuntimeException e) {
            PokeEMC.LOGGER.warn("PokeEMC: batch current scan failed: {}", e.toString());
            return ScanOutcome.failure("adapter_unavailable", "container open failed");
        }
    }

    /** 服务端是否对该物品有卖价（预过滤，防止整箱无价物品拖垮批量出售）。 */
    private static boolean hasSellPrice(String itemId) {
        try {
            TradeItemId id = TradeItemId.parse(itemId);
            return ExchangePriceService.forServer().quote(id)
                    .map(q -> q.sellPrice() > 0).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ==================== NEARBY：半径内服务端扫描 ====================

    private static StorageTransactionResult executeNearby(
            ServerPlayer player, StorageBatchPacket packet, StorageSavedData data, ExchangeMenu menu) {
        if (packet.action() == Action.WITHDRAW_ALL) {
            return StorageTransactionResult.failure(
                    "invalid_request", "nearby withdraw not supported");
        }
        if (packet.radius() < 0 || packet.radius() > MAX_RADIUS) {
            return StorageTransactionResult.failure(
                    "invalid_request", "radius out of range");
        }
        StorageDiscoveryService.ScanResult scan = StorageServices.discovery().scanSellableSlots(
                player.getUUID(),
                player.level().dimension().location().toString(),
                player.getBlockX(), player.getBlockZ(),
                packet.radius(),
                packet.action() == Action.SELL_ITEM
                        ? (packet.itemId() == null || packet.itemId().isBlank() ? null : packet.itemId())
                        : null,
                ExchangeService.MAX_ENTRIES);
        List<ExchangeService.SellEntry> entries = new ArrayList<>(scan.slots().size());
        Map<StorageId, Long> revisions = new LinkedHashMap<>();
        // [CHANGED] 会话 #21-D：storageId#slotIndex 去重（本箱若在 NEARBY 半径内也会被扫到）
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (StorageDiscoveryService.SellableSlot s : scan.slots()) {
            String key = s.storageId().asString() + "#" + s.slotIndex();
            if (!seen.add(key)) {
                continue;
            }
            entries.add(new ExchangeService.SellEntry(s.storageId(), s.slotIndex(), s.count(), s.fingerprint()));
            revisions.put(s.storageId(), s.revision());
        }
        // [CHANGED] 会话 #21-D：NEARBY 显式包含「本箱」（客户端右键菜单选中的仓储）。
        // 根因：scanSellableSlots 对 parsePos==null（末影箱 virtual 无坐标）的箱子直接
        // continue，且超出玩家坐标半径的箱子也不入扫描——本箱永不进 NEARBY 结果。
        // 复用 scanCurrent（同权限/同类过滤/无价过滤），把本箱同类可售槽位去重并入。
        if (packet.storageId() != null) {
            ScanOutcome current = scanCurrent(player, packet, data, packet.storageId());
            if (current.ok()) {
                Long currentRev = current.revisions().get(packet.storageId());
                for (ExchangeService.SellEntry e : current.entries()) {
                    String key = packet.storageId().asString() + "#" + e.slotIndex();
                    if (!seen.add(key)) {
                        continue;
                    }
                    entries.add(e);
                    if (currentRev != null) {
                        revisions.put(packet.storageId(), currentRev);
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            return StorageTransactionResult.failure(
                    scan.truncated() ? "too_many" : "nothing_to_sell",
                    scan.truncated() ? "too many sellable slots; narrowed down"
                            : "no sellable items in range");
        }
        if (entries.size() > ExchangeService.MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, ExchangeService.MAX_ENTRIES));
        }
        return menu.runSell(player, new StorageSellPacket(
                packet.sessionId(), packet.operationId(), entries, revisions));
    }

    // ==================== WITHDRAW_ALL：逐槽取出到背包 ====================

    /**
     * 批量取出：逐仓储槽经 {@link StorageTransactionService} 两阶段事务移入玩家背包
     * （优先合并同物品槽，其次空槽；单槽可拆分多次填充）。背包满停止并报
     * {@code inventory_full}。每笔子事务独立 operationId（避免幂等去重吞掉后续槽）。
     */
    private static StorageTransactionResult withdrawAll(
            ServerPlayer player, StorageBatchPacket packet, ScanOutcome scan) {
        StorageTransactionService tx = StorageServices.transactionService();
        List<StorageItemSlot> changed = new ArrayList<>();
        Map<StorageId, Long> newRevisions = new LinkedHashMap<>();
        Map<StorageId, StorageSnapshot> snapshots = new LinkedHashMap<>();
        int moved = 0;
        boolean inventoryFull = false;
        for (ExchangeService.SellEntry e : scan.entries()) {
            String itemId = scan.slotItems().get(e.slotIndex());
            if (itemId == null) {
                continue;
            }
            int remaining = e.count();
            int sub = 0;
            while (remaining > 0) {
                int target = inventoryTargetSlot(player.getInventory(), itemId);
                if (target < 0) {
                    inventoryFull = true;
                    break;
                }
                int space = inventorySlotSpace(player.getInventory(), target, itemId);
                int fit = Math.min(remaining, space);
                if (fit <= 0) {
                    break;
                }
                String opId = packet.operationId() + "#" + e.slotIndex() + "-" + (sub++);
                StorageTransaction t = new StorageTransaction(
                        player.getUUID(), packet.sessionId(), opId,
                        StorageEndpoint.storage(packet.storageId(), e.slotIndex()),
                        StorageEndpoint.inventory(target),
                        fit, 0L, 0L, Map.of());
                StorageTransactionResult r = tx.execute(t);
                if (r.success()) {
                    remaining -= fit;
                    moved++;
                    changed.addAll(r.changedSlots());
                    newRevisions.putAll(r.newRevisions());
                    snapshots.putAll(r.updatedSnapshots());
                } else if ("source_empty".equals(r.code())
                        || "source_changed".equals(r.code())
                        || "content_changed".equals(r.code())) {
                    break; // 源槽已变/为空：跳到下一仓储槽
                } else if ("target_blocked".equals(r.code())) {
                    inventoryFull = true;
                    break;
                } else {
                    return r; // 权限/错误：整体失败（已移动的槽位已提交，客户端经菜单刷新）
                }
            }
            if (inventoryFull) {
                break;
            }
        }
        if (moved == 0) {
            return StorageTransactionResult.failure(
                    inventoryFull ? "inventory_full" : "source_empty",
                    inventoryFull ? "inventory full" : "no withdrawable slots");
        }
        return StorageTransactionResult.success(
                "withdrew " + moved + " slot operation(s) to inventory",
                changed, newRevisions, snapshots, null);
    }

    /** 找背包目标槽：同物品可合并（剩余空间 > 0）优先，其次空槽。 */
    private static int inventoryTargetSlot(net.minecraft.world.entity.player.Inventory inv, String itemId) {
        ItemStack sample = sample(itemId);
        if (sample == null || sample.isEmpty()) {
            return -1;
        }
        int maxStack = Math.max(1, sample.getMaxStackSize());
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, sample)
                    && s.getCount() < maxStack) {
                return i;
            }
        }
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 指定目标背包槽还能放入的数量（合并剩余空间或空槽堆叠上限）。 */
    private static int inventorySlotSpace(net.minecraft.world.entity.player.Inventory inv,
                                          int slot, String itemId) {
        ItemStack sample = sample(itemId);
        if (sample == null || sample.isEmpty()) {
            return 0;
        }
        ItemStack s = inv.getItem(slot);
        if (s.isEmpty()) {
            return Math.max(1, sample.getMaxStackSize());
        }
        if (!ItemStack.isSameItemSameComponents(s, sample)) {
            return 0;
        }
        return Math.max(0, Math.max(1, sample.getMaxStackSize()) - s.getCount());
    }

    /** 物品 id → 样本栈（球类还原球种组件；解析失败返回 null）。 */
    private static ItemStack sample(String itemId) {
        try {
            ItemStack s = PokeballIdentity.decode(itemId, 1);
            return (s == null || s.isEmpty()) ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 当前仓储扫描结果（含失败信息）；slotItems 仅 WITHDRAW_ALL 使用（槽位 → itemId）。 */
    private record ScanOutcome(boolean ok, String code, String message,
                               List<ExchangeService.SellEntry> entries,
                               Map<StorageId, Long> revisions, boolean truncated,
                               Map<Integer, String> slotItems) {
        static ScanOutcome failure(String code, String message) {
            return new ScanOutcome(false, code, message, List.of(), Map.of(), false, Map.of());
        }

        static ScanOutcome success(List<ExchangeService.SellEntry> entries,
                                   Map<StorageId, Long> revisions, boolean truncated,
                                   Map<Integer, String> slotItems) {
            return new ScanOutcome(true, "success", "ok", entries, revisions, truncated, slotItems);
        }
    }

    private static boolean isBlankOrTooLong(String value) {
        return value == null || value.isBlank() || value.length() > MAX_SESSION_FIELD_LENGTH;
    }
}
