package com.pokeemc.exchange;

import com.pokeemc.economy.PixelmonWallet;
import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.exchange.price.ExchangePriceService;
import com.pokeemc.storage.StorageAccessService;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.adapter.StorageHandleExt;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageAdapterRegistry;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageSnapshot;
import com.poketrade.api.storage.StorageTransactionResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 等价出售服务（Task 8）：以「已授权仓储槽位」为出售来源，把物品折算为 PKM 入账钱包。
 *
 * <p>与 {@link StorageTransactionService} 相同的两阶段事务语义：</p>
 * <ul>
 *   <li>出售来源只要求 {@code SELL} 权限，绝不使用 {@code WITHDRAW} 代替、也不附带授予；</li>
 *   <li>同仓储同槽位需求按槽位聚合计入：重复行虚报总量在校验阶段即被拒绝（防刷钱），
 *       后续 simulate/钱包入账/commit 只处理通过聚合校验的条目；</li>
 *   <li>服务端每次执行都<b>重新查价</b>（{@link ExchangePriceService}），不信任客户端报价；</li>
 *   <li>先 simulate 全部移除、再钱包入账、最后 commit：任一环节失败则整体失败，任何物品不移除；</li>
 *   <li>金额溢出（{@link Math#multiplyExact(long, long)}）、免费物品（单价 &lt;= 0）、
 *       快照过期（revision 冲突）、钱包拒绝（add 返回 false）均不移除物品；</li>
 *   <li>幂等键 {@code actorId|sessionId|operationId}，语义与 StorageTransactionService 相同
 *       （容量上限 + TTL 短期缓存）。</li>
 * </ul>
 *
 * <p>审计按仓储记录出售数量与金额，并聚合同次批量事务（批次号=sessionId/operationId）。</p>
 */
public final class ExchangeService {

    /** 幂等缓存容量上限（LRU 淘汰）。 */
    public static final int IDEMPOTENT_CACHE_CAPACITY = 1024;

    /** 幂等结果默认短期有效时长。 */
    public static final long DEFAULT_IDEMPOTENT_TTL_MILLIS = 5 * 60_000L;

    /** 单次出售请求的条目数上限（防呆；每仓储每槽位一条）。 */
    public static final int MAX_ENTRIES = 64;

    /** 金额溢出。 */
    public static final String VALUE_OVERFLOW = "value_overflow";

    /** 免费物品（单价 &lt;= 0 或未定价），不可出售。 */
    public static final String FREE_ITEM = "free_item";

    /** 钱包拒绝入账。 */
    public static final String WALLET_REJECTED = "wallet_rejected";

    /** 槽位为空或数量不足。 */
    public static final String SOURCE_EMPTY = "source_empty";

    /** 槽位内容变化（指纹冲突 / simulate 失败）。 */
    public static final String CONTENT_CHANGED = "content_changed";

    /** 请求本身非法（空列表、越界槽位等）。 */
    public static final String INVALID_REQUEST = "invalid_request";

    /** 会话号非法。 */
    public static final String INVALID_SESSION = "invalid_session";

    /** 操作号非法。 */
    public static final String INVALID_OPERATION = "invalid_operation";

    /** 一条出售请求：仓储 + 槽位 + 数量（+ 客户端看到的槽位指纹，0 表示跳过校验）。 */
    public record SellEntry(StorageId storageId, int slotIndex, int count, long fingerprint) {
        public SellEntry {
            Objects.requireNonNull(storageId, "storageId");
            if (slotIndex < 0) {
                throw new IllegalArgumentException("slotIndex must be >= 0");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("count must be > 0");
            }
        }
    }

    /** savedData 提供者：出售执行期间必须可返回当前存档数据。 */
    @FunctionalInterface
    public interface SavedDataProvider {
        StorageSavedData get();
    }

    /** 钱包入账口：返回 false 表示拒绝（余额上限等），此时不得移除物品。 */
    @FunctionalInterface
    public interface WalletPort {
        boolean add(UUID actorId, long amount);
    }

    private final StorageAdapterRegistry registry;
    private final StorageAccessService access;
    private final SavedDataProvider savedDataProvider;
    private final WalletPort wallet;
    private final ExchangePriceService pricing;
    private final LongSupplier clock;
    private final long idempotentTtlMillis;

    private final Map<String, Entry> idempotent = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > IDEMPOTENT_CACHE_CAPACITY;
        }
    };

    /** 幂等缓存条目：结果 + 写入时钟时间。 */
    private record Entry(StorageTransactionResult result, long timestampMillis) {
    }

    /** 已解析的出售端点（句柄保持打开，出售结束前不关闭）。 */
    private record ResolvedStorage(
            StorageId storageId,
            StorageKey key,
            StorageRecord record,
            StorageHandleExt handle) {
    }

    private record ResolvedEntry(SellEntry entry, ResolvedStorage storage) {
    }

    private static volatile ExchangeService serverInstance;

    public ExchangeService(
            StorageAdapterRegistry registry,
            StorageAccessService access,
            SavedDataProvider savedDataProvider,
            WalletPort wallet,
            ExchangePriceService pricing) {
        this(registry, access, savedDataProvider, wallet, pricing,
                System::currentTimeMillis, DEFAULT_IDEMPOTENT_TTL_MILLIS);
    }

    /** 测试用构造：注入时钟与 TTL，便于验证幂等过期行为。 */
    ExchangeService(
            StorageAdapterRegistry registry,
            StorageAccessService access,
            SavedDataProvider savedDataProvider,
            WalletPort wallet,
            ExchangePriceService pricing,
            LongSupplier clock,
            long idempotentTtlMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.access = Objects.requireNonNull(access, "access");
        this.savedDataProvider = Objects.requireNonNull(savedDataProvider, "savedDataProvider");
        this.wallet = Objects.requireNonNull(wallet, "wallet");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (idempotentTtlMillis <= 0) {
            throw new IllegalArgumentException("idempotentTtlMillis must be positive");
        }
        this.idempotentTtlMillis = idempotentTtlMillis;
    }

    /**
     * 服务端生产装配（仅在服务端线程调用）：仓储注册表/鉴权取自 {@link StorageServices}，
     * savedData 取自当前存档，钱包走 {@link PixelmonWallet}，定价走 {@link ExchangePriceService}。
     */
    public static ExchangeService forServer() {
        ExchangeService current = serverInstance;
        if (current == null) {
            synchronized (ExchangeService.class) {
                current = serverInstance;
                if (current == null) {
                    current = new ExchangeService(
                            StorageServices.registry(),
                            StorageServices.access(),
                            ExchangeService::serverSavedData,
                            ExchangeService::creditWallet,
                            ExchangePriceService.forServer(),
                            System::currentTimeMillis,
                            DEFAULT_IDEMPOTENT_TTL_MILLIS);
                    serverInstance = current;
                }
            }
        }
        return current;
    }

    private static StorageSavedData serverSavedData() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("sell requires server thread");
        }
        return server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    private static boolean creditWallet(UUID actorId, long amount) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return false;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(actorId);
        return player != null && PixelmonWallet.add(player, amount);
    }

    /**
     * 执行批量出售。同 {@code actorId+sessionId+operationId} 的重复调用（含失败）
     * 直接返回首次结果；成功后槽位、revision、审计均已落盘，钱包已入账。
     */
    public StorageTransactionResult sell(
            UUID actorId, String sessionId, String operationId,
            List<SellEntry> entries, Map<StorageId, Long> expectedRevisions) {
        Objects.requireNonNull(actorId, "actorId");
        if (!PokeTradeConfig.exchangeSellEnabled()) {
            return StorageTransactionResult.failure(
                    "sell_disabled", "sell disabled by server config");
        }
        if (isBlankOrTooLong(sessionId, 64)) {
            return StorageTransactionResult.failure(INVALID_SESSION, "invalid session id");
        }
        if (isBlankOrTooLong(operationId, 64)) {
            return StorageTransactionResult.failure(INVALID_OPERATION, "invalid operation id");
        }
        if (entries == null || entries.isEmpty()) {
            return StorageTransactionResult.failure(INVALID_REQUEST, "sell entries are empty");
        }
        if (entries.size() > MAX_ENTRIES) {
            return StorageTransactionResult.failure(INVALID_REQUEST, "too many sell entries");
        }
        if (expectedRevisions == null) {
            return StorageTransactionResult.failure(INVALID_REQUEST, "expected revisions missing");
        }

        String opKey = actorId + "|" + sessionId + "|" + operationId;
        long now = clock.getAsLong();
        Entry cached = idempotent.get(opKey);
        if (cached != null) {
            if (now - cached.timestampMillis() <= idempotentTtlMillis) {
                return cached.result();
            }
            idempotent.remove(opKey); // 已过期，允许重放
        }

        StorageTransactionResult result = doSell(actorId, sessionId, operationId, entries, expectedRevisions, now);
        idempotent.put(opKey, new Entry(result, now));
        return result;
    }

    private static boolean isBlankOrTooLong(String s, int max) {
        return s == null || s.isBlank() || s.length() > max;
    }

    private StorageTransactionResult doSell(
            UUID actorId, String sessionId, String operationId,
            List<SellEntry> entries, Map<StorageId, Long> expectedRevisions, long now) {
        StorageSavedData data = savedDataProvider.get();
        if (data == null) {
            return StorageTransactionResult.failure(
                    StorageTransactionResult.ADAPTER_UNAVAILABLE, "saved data unavailable");
        }

        // 1) 解析全部端点（同一仓储只打开一次句柄）
        LinkedHashMap<StorageId, ResolvedStorage> storages = new LinkedHashMap<>();
        List<ResolvedEntry> resolved = new ArrayList<>();
        for (SellEntry e : entries) {
            ResolvedStorage rs = storages.get(e.storageId());
            if (rs == null) {
                rs = resolveStorage(e.storageId(), data);
                if (rs == null) {
                    closeAll(storages);
                    return lastFailure;
                }
                storages.put(e.storageId(), rs);
            }
            resolved.add(new ResolvedEntry(e, rs));
        }

        // 2) 逐条校验：SELL 权限 / revision / 指纹 / 槽位可用性 / 重新查价 / 金额溢出
        long total = 0L;
        LinkedHashMap<StorageId, Long> perStorageAmount = new LinkedHashMap<>();
        LinkedHashMap<StorageId, Integer> perStorageCount = new LinkedHashMap<>();
        LinkedHashMap<StorageId, StringBuilder> perStorageItems = new LinkedHashMap<>();
        // 同仓储同槽位需求聚合：同一槽位多行（重复行虚报总量）按累计需求校验，
        // 防止钱包按虚报总量入账而 commit 阶段扣减失败（刷钱漏洞，finalreview C1）。
        LinkedHashMap<String, Integer> slotNeeded = new LinkedHashMap<>();
        for (ResolvedEntry re : resolved) {
            StorageRecord record = re.storage().record();
            if (!access.canSell(actorId, new StorageAccessService.AccessSnapshot(
                    record.ownerId(), record.grants()))) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        StorageTransactionResult.PERMISSION_DENIED,
                        "sell not allowed: " + re.entry().storageId().asString());
            }
            Long expected = expectedRevisions.get(re.entry().storageId());
            if (expected == null || expected.longValue() != record.revision()) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        StorageTransactionResult.REVISION_CONFLICT,
                        "revision mismatch for " + re.entry().storageId().asString());
            }
            StorageHandleExt handle = re.storage().handle();
            if (re.entry().fingerprint() != 0
                    && handle.fingerprint(re.entry().slotIndex()) != re.entry().fingerprint()) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        CONTENT_CHANGED, "slot content changed: "
                                + re.entry().storageId().asString() + " #" + re.entry().slotIndex());
            }
            String itemId = handle.itemId(re.entry().slotIndex());
            int available = handle.count(re.entry().slotIndex());
            String slotKey = re.entry().storageId().asString() + "#" + re.entry().slotIndex();
            int needed = slotNeeded.merge(slotKey, re.entry().count(), Integer::sum);
            if (itemId == null || available < needed) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        SOURCE_EMPTY, "insufficient items at "
                                + re.entry().storageId().asString() + " #" + re.entry().slotIndex());
            }
            TradeItemId tradeItemId;
            try {
                tradeItemId = TradeItemId.parse(itemId);
            } catch (IllegalArgumentException e) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        FREE_ITEM, "item is free or unpriced, not sellable: " + itemId);
            }
            long unit = pricing.quote(tradeItemId).map(q -> q.sellPrice()).orElse(0L);
            if (unit <= 0) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        FREE_ITEM, "item is free or unpriced, not sellable: " + itemId);
            }
            long amount;
            try {
                amount = Math.multiplyExact(unit, re.entry().count());
                total = Math.addExact(total, amount);
            } catch (ArithmeticException e) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        VALUE_OVERFLOW, "sell value overflow");
            }
            perStorageAmount.merge(re.entry().storageId(), amount, Long::sum);
            perStorageCount.merge(re.entry().storageId(), re.entry().count(), Integer::sum);
            StringBuilder sb = perStorageItems.computeIfAbsent(
                    re.entry().storageId(), k -> new StringBuilder());
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(re.entry().count()).append("x").append(itemId);
        }

        // 3) 两阶段：全部 simulate 通过后才进入钱包/提交阶段
        for (ResolvedEntry re : resolved) {
            StorageHandleExt handle = re.storage().handle();
            String itemId = handle.itemId(re.entry().slotIndex());
            if (!handle.simulateExtract(re.entry().slotIndex(), itemId, re.entry().count())) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        CONTENT_CHANGED, "cannot extract "
                                + re.entry().count() + "x" + itemId
                                + " from " + re.entry().storageId().asString());
            }
        }

        // 4) 钱包入账（在 commit 之前：拒绝则整体失败，物品不移除）
        if (!wallet.add(actorId, total)) {
            closeAll(storages);
            return StorageTransactionResult.failure(
                    WALLET_REJECTED, "wallet rejected " + total + " PKM");
        }

        // 5) 全部 commit（simulate 已全部通过；服务端主线程内无并发改动）
        for (ResolvedEntry re : resolved) {
            StorageHandleExt handle = re.storage().handle();
            String itemId = handle.itemId(re.entry().slotIndex());
            try {
                handle.commitExtract(re.entry().slotIndex(), itemId, re.entry().count());
            } catch (RuntimeException e) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        StorageTransactionResult.ADAPTER_UNAVAILABLE,
                        "commit extract failed: " + e.getMessage());
            }
        }

        // 6) 修订递增（每仓储一次）+ 变更槽位 + 快照 + 按仓储聚合审计（聚合同次批量）
        List<StorageItemSlot> changedSlots = new ArrayList<>();
        Map<StorageId, Long> newRevisions = new LinkedHashMap<>();
        Map<StorageId, StorageSnapshot> updatedSnapshots = new LinkedHashMap<>();
        long lastAuditId = -1;
        for (ResolvedStorage rs : storages.values()) {
            long expected = rs.record().revision();
            long newRev = data.updateRecord(rs.key(), expected, r -> r) ? expected + 1 : -1;
            if (newRev < 0) {
                closeAll(storages);
                return StorageTransactionResult.failure(
                        StorageTransactionResult.REVISION_CONFLICT,
                        "revision conflict on storage " + rs.storageId().asString());
            }
            newRevisions.put(rs.storageId(), newRev);
            for (ResolvedEntry re : resolved) {
                if (re.storage() == rs) {
                    addChangedSlot(changedSlots, re.storage().handle(), re.entry().slotIndex());
                }
            }
            updatedSnapshots.put(
                    rs.storageId(),
                    new StorageSnapshot(rs.storageId(), newRev, rs.handle().snapshot().slots()));
            int count = perStorageCount.getOrDefault(rs.storageId(), 0);
            long amount = perStorageAmount.getOrDefault(rs.storageId(), 0L);
            lastAuditId = data.appendAudit(
                    now, rs.key().asString(), actorId, "sell",
                    rs.record().displayName() + " 出售 " + perStorageItems.get(rs.storageId())
                            + " 共 " + count + " 件 / " + amount + " PKM（批次 "
                            + sessionId + "/" + operationId + "）").id();
        }
        closeAll(storages);

        return StorageTransactionResult.success(
                "sold " + resolved.size() + " entries for " + total + " PKM",
                changedSlots,
                newRevisions,
                updatedSnapshots,
                auditUuid(lastAuditId));
    }

    private StorageTransactionResult lastFailure;

    /** 解析出售端点；失败原因记录在 lastFailure 供调用方读取。 */
    private ResolvedStorage resolveStorage(StorageId sid, StorageSavedData data) {
        StorageAdapter adapter = registry.byTypeId(sid.adapterType()).orElse(null);
        if (adapter == null) {
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.ADAPTER_UNAVAILABLE,
                    "unknown adapter type: " + sid.adapterType());
            return null;
        }
        StorageKey key = StorageKey.of(sid.dimension(), sid.adapterType(), sid.location());
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.NOT_FOUND,
                    "storage not claimed: " + sid.asString());
            return null;
        }
        StorageAdapterContext context = new StorageAdapterContext(sid);
        if (!adapter.supports(context)) {
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.ADAPTER_UNAVAILABLE,
                    "container not supported: " + sid.asString());
            return null;
        }
        StorageHandle handle = adapter.open(context).orElse(null);
        if (handle == null) {
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.CHUNK_UNLOADED,
                    "container chunk not loaded: " + sid.asString());
            return null;
        }
        if (!(handle instanceof StorageHandleExt ext)) {
            handle.close();
            // 非原子降级：第三方适配器无法提供槽位级两阶段事务，拒绝参与出售
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.ADAPTER_UNAVAILABLE,
                    "adapter does not support transactional slot access (non-atomic): "
                            + sid.adapterType());
            return null;
        }
        return new ResolvedStorage(sid, key, record, ext);
    }

    private static void closeAll(Map<StorageId, ResolvedStorage> storages) {
        for (ResolvedStorage rs : storages.values()) {
            try {
                rs.handle().close();
            } catch (RuntimeException ignored) {
                // 关闭失败不影响结果
            }
        }
    }

    private void addChangedSlot(List<StorageItemSlot> out, StorageHandleExt handle, int slotIndex) {
        String id = handle.itemId(slotIndex);
        int count = handle.count(slotIndex);
        if (id != null && count > 0) {
            out.add(new StorageItemSlot(
                    slotIndex, id, count, handle.fingerprint(slotIndex)));
        }
    }

    private static UUID auditUuid(long auditId) {
        return auditId < 0
                ? null
                : UUID.nameUUIDFromBytes(
                        ("poketrade-audit-" + auditId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
