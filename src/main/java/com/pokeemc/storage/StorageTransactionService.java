package com.pokeemc.storage;

import com.mojang.logging.LogUtils;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageAdapterRegistry;
import com.poketrade.api.storage.StorageEndpoint;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageSnapshot;
import com.poketrade.api.storage.StorageTransaction;
import com.poketrade.api.storage.StorageTransactionResult;
import com.poketrade.api.permission.ProtectionAction;
import com.pokeemc.thirdparty.ThirdPartyServices;
import com.pokeemc.storage.adapter.StorageHandleExt;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 真实槽位事务引擎：把 {@link StorageTransaction} 校验并原子地落到槽位上。
 *
 * <p>这是 Task 7 的核心。从 {@code PokeTradeStorageApiImpl} 提取后独立装配，
 * 所有外部依赖均通过函数式接口注入（savedData / 玩家背包 / 背包刷新 / 时钟），
 * 因此可以在纯 JVM 测试中以 Fake 桩覆盖权限矩阵、revision/指纹并发控制、
 * 两阶段 simulate-commit 原子性、幂等去重与 TTL 过期等场景。</p>
 *
 * <p>语义约定：</p>
 * <ul>
 *   <li>幂等键为 {@code actorId|sessionId|operationId}，缓存有容量上限与 TTL，
 *       重复包（同键）直接返回首次结果，不二次执行。</li>
 *   <li>所有请求先模拟后提交；任一 simulate 失败则整体失败，任何槽位不变。</li>
 *   <li>权限每次执行时以最新 {@link StorageRecord} 重新判定——菜单打开后被撤销，
 *       下一次操作立即失败。</li>
 *   <li>只有实现 {@link StorageHandleExt}（暴露槽位指纹/数量）的适配器可参与事务；
 *       第三方非原子适配器降级为不可用并返回 {@code ADAPTER_UNAVAILABLE}。</li>
 * </ul>
 */
public final class StorageTransactionService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 幂等缓存容量上限（LRU 淘汰）。 */
    public static final int IDEMPOTENT_CACHE_CAPACITY = 1024;

    /** 幂等结果默认短期有效时长。 */
    public static final long DEFAULT_IDEMPOTENT_TTL_MILLIS = 5 * 60_000L;

    /** savedData 提供者：事务执行期间必须可返回当前存档数据。 */
    @FunctionalInterface
    public interface SavedDataProvider {
        StorageSavedData get();
    }

    /** 玩家背包句柄提供者：玩家离线时返回 {@code null}。 */
    @FunctionalInterface
    public interface PlayerInventoryProvider {
        StorageHandleExt inventoryOf(UUID actorId);
    }

    /** 事务涉及玩家背包后需要同步客户端菜单显示。 */
    @FunctionalInterface
    public interface InventoryRefresher {
        void refresh(UUID actorId);
    }

    private final StorageAdapterRegistry registry;
    private final StorageAccessService access;
    private final SavedDataProvider savedDataProvider;
    private final PlayerInventoryProvider inventoryProvider;
    private final InventoryRefresher inventoryRefresher;
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

    /** 已解析的 STORAGE 端点（句柄保持打开，事务结束前不关闭）。 */
    private record StorageEndpointData(
            StorageEndpoint endpoint,
            StorageKey key,
            StorageRecord record,
            StorageHandleExt handle) {
    }

    public StorageTransactionService(
            StorageAdapterRegistry registry,
            StorageAccessService access,
            SavedDataProvider savedDataProvider,
            PlayerInventoryProvider inventoryProvider,
            InventoryRefresher inventoryRefresher) {
        this(registry, access, savedDataProvider, inventoryProvider, inventoryRefresher,
                System::currentTimeMillis, DEFAULT_IDEMPOTENT_TTL_MILLIS);
    }

    /** 测试用构造：注入时钟与 TTL，便于验证幂等过期行为。 */
    StorageTransactionService(
            StorageAdapterRegistry registry,
            StorageAccessService access,
            SavedDataProvider savedDataProvider,
            PlayerInventoryProvider inventoryProvider,
            InventoryRefresher inventoryRefresher,
            LongSupplier clock,
            long idempotentTtlMillis) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.access = Objects.requireNonNull(access, "access");
        this.savedDataProvider = Objects.requireNonNull(savedDataProvider, "savedDataProvider");
        this.inventoryProvider = Objects.requireNonNull(inventoryProvider, "inventoryProvider");
        this.inventoryRefresher = Objects.requireNonNull(inventoryRefresher, "inventoryRefresher");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (idempotentTtlMillis <= 0) {
            throw new IllegalArgumentException("idempotentTtlMillis must be positive");
        }
        this.idempotentTtlMillis = idempotentTtlMillis;
    }

    /**
     * 执行事务。同 {@code actorId+sessionId+operationId} 的重复调用（含失败）
     * 直接返回首次结果；成功后槽位、revision、审计均已落盘。
     */
    public StorageTransactionResult execute(StorageTransaction t) {
        Objects.requireNonNull(t, "transaction");
        String opKey = opKey(t);
        long now = clock.getAsLong();
        Entry cached = idempotent.get(opKey);
        if (cached != null) {
            if (now - cached.timestampMillis() <= idempotentTtlMillis) {
                return cached.result();
            }
            idempotent.remove(opKey); // 已过期，允许重放
        }

        StorageTransactionResult result = doExecute(t, now);
        idempotent.put(opKey, new Entry(result, now));
        return result;
    }

    private static String opKey(StorageTransaction t) {
        return t.actorId() + "|" + t.sessionId() + "|" + t.operationId();
    }

    private StorageTransactionResult doExecute(StorageTransaction t, long now) {
        StorageSavedData data = savedDataProvider.get();
        StorageEndpoint source = t.source();
        StorageEndpoint target = t.target();
        if (source.kind() == StorageEndpoint.Kind.INVENTORY
                && target.kind() == StorageEndpoint.Kind.INVENTORY) {
            return StorageTransactionResult.failure(
                    "invalid_endpoints", "inventory-to-inventory is not a storage transaction");
        }

        StorageHandleExt invSource = source.kind() == StorageEndpoint.Kind.INVENTORY
                ? inventoryProvider.inventoryOf(t.actorId())
                : null;
        if (source.kind() == StorageEndpoint.Kind.INVENTORY && invSource == null) {
            return StorageTransactionResult.failure(
                    "player_offline", "source player not online");
        }
        StorageHandleExt invTarget = target.kind() == StorageEndpoint.Kind.INVENTORY
                ? inventoryProvider.inventoryOf(t.actorId())
                : null;
        if (target.kind() == StorageEndpoint.Kind.INVENTORY && invTarget == null) {
            return StorageTransactionResult.failure(
                    "player_offline", "target player not online");
        }

        StorageEndpointData srcStorage = source.kind() == StorageEndpoint.Kind.STORAGE
                ? resolveStorage(source, data).orElse(null)
                : null;
        if (source.kind() == StorageEndpoint.Kind.STORAGE && srcStorage == null) {
            return lastFailure(source);
        }
        StorageEndpointData tgtStorage = target.kind() == StorageEndpoint.Kind.STORAGE
                ? resolveStorage(target, data).orElse(null)
                : null;
        if (target.kind() == StorageEndpoint.Kind.STORAGE && tgtStorage == null) {
            return lastFailure(target);
        }

        // 统一鉴权：按方向检查（每次执行都读取最新 record，权限被撤销立即失败）
        if (source.kind() == StorageEndpoint.Kind.INVENTORY) {
            if (!access.canDeposit(t.actorId(), accessSnapshot(tgtStorage.record()))) {
                return failWithSnapshots(
                        StorageTransactionResult.PERMISSION_DENIED, "deposit not allowed",
                        srcStorage, tgtStorage);
            }
        } else if (target.kind() == StorageEndpoint.Kind.INVENTORY) {
            if (!access.canWithdraw(t.actorId(), accessSnapshot(srcStorage.record()))) {
                return failWithSnapshots(
                        StorageTransactionResult.PERMISSION_DENIED, "withdraw not allowed",
                        srcStorage, tgtStorage);
            }
        } else {
            if (!access.canWithdraw(t.actorId(), accessSnapshot(srcStorage.record()))
                    || !access.canDeposit(t.actorId(), accessSnapshot(tgtStorage.record()))) {
                return failWithSnapshots(
                        StorageTransactionResult.PERMISSION_DENIED,
                        "transfer permission not allowed",
                        srcStorage, tgtStorage);
            }
        }

        // 阶段 6：第三方保护链（AND 于自有 ACL）；STORAGE 端点按方向检查
        if (source.kind() == StorageEndpoint.Kind.STORAGE
                && !ThirdPartyServices.protectionHook()
                        .allows(t.actorId(), srcStorage.endpoint().storageId(),
                                ProtectionAction.WITHDRAW)) {
            return failWithSnapshots(
                    StorageTransactionResult.PERMISSION_DENIED,
                    "third-party protection denied withdraw",
                    srcStorage, tgtStorage);
        }
        if (target.kind() == StorageEndpoint.Kind.STORAGE
                && !ThirdPartyServices.protectionHook()
                        .allows(t.actorId(), tgtStorage.endpoint().storageId(),
                                ProtectionAction.DEPOSIT)) {
            return failWithSnapshots(
                    StorageTransactionResult.PERMISSION_DENIED,
                    "third-party protection denied deposit",
                    srcStorage, tgtStorage);
        }

        // revision 并发控制
        for (StorageEndpointData d : storageEndpoints(srcStorage, tgtStorage)) {
            Long expected = t.expectedRevisions().get(d.endpoint().storageId());
            if (expected != null && expected.longValue() != d.record().revision()) {
                return failWithSnapshots(
                        StorageTransactionResult.REVISION_CONFLICT,
                        "revision mismatch for " + d.endpoint().storageId().asString(),
                        srcStorage, tgtStorage);
            }
        }

        // 内容指纹校验（仅 STORAGE 端点，非 0 时）
        if (srcStorage != null && t.sourceFingerprint() != 0) {
            long actual = srcStorage.handle().fingerprint(source.slotIndex());
            if (actual != t.sourceFingerprint()) {
                // [CHANGED] 会话 #21-F Bug 1 诊断：区分「缓存过期」（刷新可解）与
                // 「指纹不稳定」（二次读取不同，即使快照新鲜也会被拒）。
                long again = srcStorage.handle().fingerprint(source.slotIndex());
                LOGGER.warn("[storage-diag] move content_changed src={} slot={} clientFp={} "
                                + "serverFpNow={} serverFpAgain={} (unstable={}) item={}x{}",
                        t.source().storageId().asString(), source.slotIndex(),
                        t.sourceFingerprint(), actual, again, actual != again,
                        srcStorage.handle().itemId(source.slotIndex()),
                        srcStorage.handle().count(source.slotIndex()));
                return failWithSnapshots(
                        "content_changed", "source content changed",
                        srcStorage, tgtStorage);
            }
        }
        if (tgtStorage != null && t.targetFingerprint() != 0) {
            long actual = tgtStorage.handle().fingerprint(target.slotIndex());
            if (actual != t.targetFingerprint()) {
                long again = tgtStorage.handle().fingerprint(target.slotIndex());
                LOGGER.warn("[storage-diag] move content_changed tgt={} slot={} clientFp={} "
                                + "serverFpNow={} serverFpAgain={} (unstable={}) item={}x{}",
                        t.target().storageId().asString(), target.slotIndex(),
                        t.targetFingerprint(), actual, again, actual != again,
                        tgtStorage.handle().itemId(target.slotIndex()),
                        tgtStorage.handle().count(target.slotIndex()));
                return failWithSnapshots(
                        "content_changed", "target content changed",
                        srcStorage, tgtStorage);
            }
        }

        // 提取物品信息
        String itemId;
        int available;
        if (invSource != null) {
            itemId = invSource.itemId(source.slotIndex());
            available = invSource.count(source.slotIndex());
        } else {
            itemId = srcStorage.handle().itemId(source.slotIndex());
            available = srcStorage.handle().count(source.slotIndex());
        }
        if (itemId == null || available <= 0) {
            return failWithSnapshots(
                    "source_empty", "source slot is empty",
                    srcStorage, tgtStorage);
        }
        int count = Math.min(t.requestedCount(), available);

        // 两阶段模拟后提交：任一 simulate 失败则整体失败，槽位不变
        StorageHandleExt srcHandle = invSource != null ? invSource : srcStorage.handle();
        StorageHandleExt tgtHandle = invTarget != null ? invTarget : tgtStorage.handle();
        if (!srcHandle.simulateExtract(source.slotIndex(), itemId, count)) {
            return failWithSnapshots(
                    "source_changed", "cannot extract " + count + "x" + itemId,
                    srcStorage, tgtStorage);
        }
        if (!tgtHandle.simulateInsert(target.slotIndex(), itemId, count)) {
            return failWithSnapshots(
                    "target_blocked", "cannot insert " + count + "x" + itemId,
                    srcStorage, tgtStorage);
        }
        // [CHANGED] 缺陷 #6 事务原子性重构：
        // 原实现先 commitExtract/commitInsert（槽位已移动）再 bumpRevision（复查），
        // bump 失败时返回 REVISION_CONFLICT 但物品已移动——部分状态。现改为：
        //   1) 修订号在校验通过后以纯算术预计算（服务端单线程内无并发改写，见 ADR-12）；
        //   2) 槽位写 + 修订应用 + 审计放入同一临界区；
        //   3) 临界区内任一失败 → 尽力补偿回滚已提交槽位写，保证 all-or-nothing；
        //   4) 客户端同步快照单独防御性计算，失败不影响已提交事务。
        List<StorageItemSlot> changedSlots = new ArrayList<>();
        Map<StorageId, Long> newRevisions = new LinkedHashMap<>();
        Map<StorageId, StorageSnapshot> updatedSnapshots = new LinkedHashMap<>();
        String action = source.kind() == StorageEndpoint.Kind.INVENTORY
                ? "deposit"
                : (target.kind() == StorageEndpoint.Kind.INVENTORY ? "withdraw" : "transfer");
        long lastAuditId = -1;
        boolean sameStorage = srcStorage != null && tgtStorage != null
                && srcStorage.key().equals(tgtStorage.key());

        long srcNewRev = srcStorage != null ? srcStorage.record().revision() + 1 : -1;
        long tgtNewRev = -1;
        if (tgtStorage != null) {
            // 同仓储双槽位：源先 +1，目标在源之后 +1
            tgtNewRev = (sameStorage ? srcNewRev : tgtStorage.record().revision()) + 1;
        }

        boolean srcCommitted = false;
        boolean tgtCommitted = false;
        try {
            srcHandle.commitExtract(source.slotIndex(), itemId, count);
            srcCommitted = true;
            tgtHandle.commitInsert(target.slotIndex(), itemId, count);
            tgtCommitted = true;

            if (srcStorage != null) {
                data.applyRevision(srcStorage.key()); // 前置已校验，强制递增（无复查）
                newRevisions.put(srcStorage.endpoint().storageId(), srcNewRev);
                addChangedSlot(changedSlots, srcStorage, source.slotIndex());
                lastAuditId = data.appendAudit(
                        now, srcStorage.key().asString(), t.actorId(), action,
                        srcStorage.record().displayName() + " slot " + source.slotIndex()
                                + " " + count + "x" + itemId).id();
            }
            if (tgtStorage != null) {
                data.applyRevision(tgtStorage.key());
                newRevisions.put(tgtStorage.endpoint().storageId(), tgtNewRev);
                addChangedSlot(changedSlots, tgtStorage, target.slotIndex());
                lastAuditId = data.appendAudit(
                        now, tgtStorage.key().asString(), t.actorId(), action,
                        tgtStorage.record().displayName() + " slot " + target.slotIndex()
                                + " " + count + "x" + itemId).id();
            }
            if (invSource != null || invTarget != null) {
                inventoryRefresher.refresh(t.actorId());
            }
        } catch (RuntimeException e) {
            // 补偿：尽力回滚已提交的槽位写，避免"物品已移动但返回失败"
            compensateSlots(
                    srcCommitted, tgtCommitted,
                    srcHandle, tgtHandle,
                    source, target, itemId, count);
            return failWithSnapshots(
                    "commit_failed", "slot commit failed: " + e.getMessage(),
                    srcStorage, tgtStorage);
        }

        // 快照为客户端同步信息：失败仅跳过该快照，不撤销已提交事务
        if (srcStorage != null) {
            try {
                updatedSnapshots.put(
                        srcStorage.endpoint().storageId(),
                        new StorageSnapshot(
                                srcStorage.endpoint().storageId(), srcNewRev,
                                srcStorage.handle().snapshot().slots()));
            } catch (RuntimeException ignored) {
                // 快照失败不影响事务结果
            }
        }
        if (tgtStorage != null) {
            try {
                updatedSnapshots.put(
                        tgtStorage.endpoint().storageId(),
                        new StorageSnapshot(
                                tgtStorage.endpoint().storageId(), tgtNewRev,
                                tgtStorage.handle().snapshot().slots()));
            } catch (RuntimeException ignored) {
                // 快照失败不影响事务结果
            }
        }

        return StorageTransactionResult.success(
                count + "x" + itemId + " " + action,
                changedSlots,
                newRevisions,
                updatedSnapshots,
                auditUuid(lastAuditId));
    }

    private static List<StorageEndpointData> storageEndpoints(
            StorageEndpointData src, StorageEndpointData tgt) {
        List<StorageEndpointData> out = new ArrayList<>(2);
        if (src != null) {
            out.add(src);
        }
        if (tgt != null) {
            out.add(tgt);
        }
        return out;
    }

    private static StorageAccessService.AccessSnapshot accessSnapshot(StorageRecord record) {
        return new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
    }

    /** 解析 STORAGE 端点；失败原因记录在 lastFailure 供调用方读取。 */
    private Optional<StorageEndpointData> resolveStorage(
            StorageEndpoint endpoint, StorageSavedData data) {
        StorageId sid = endpoint.storageId();
        StorageAdapter adapter = registry.byTypeId(sid.adapterType()).orElse(null);
        if (adapter == null) {
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.ADAPTER_UNAVAILABLE,
                    "unknown adapter type: " + sid.adapterType());
            return Optional.empty();
        }
        StorageKey key = StorageKey.of(
                sid.dimension(), sid.adapterType(), sid.location());
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.NOT_FOUND,
                    "storage not claimed: " + sid.asString());
            return Optional.empty();
        }
        StorageAdapterContext context = new StorageAdapterContext(sid);
        if (!adapter.supports(context)) {
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.ADAPTER_UNAVAILABLE,
                    "container not supported: " + sid.asString());
            return Optional.empty();
        }
        StorageHandle handle = adapter.open(context).orElse(null);
        if (handle == null) {
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.CHUNK_UNLOADED,
                    "container chunk not loaded: " + sid.asString());
            return Optional.empty();
        }
        if (!(handle instanceof StorageHandleExt ext)) {
            handle.close();
            // 非原子降级：第三方适配器无法提供槽位级两阶段事务，拒绝参与
            lastFailure = StorageTransactionResult.failure(
                    StorageTransactionResult.ADAPTER_UNAVAILABLE,
                    "adapter does not support transactional slot access (non-atomic): "
                            + sid.adapterType());
            return Optional.empty();
        }
        return Optional.of(new StorageEndpointData(endpoint, key, record, ext));
    }

    private StorageTransactionResult lastFailure;

    private StorageTransactionResult lastFailure(StorageEndpoint endpoint) {
        StorageTransactionResult failure = lastFailure;
        lastFailure = null;
        return failure != null
                ? failure
                : StorageTransactionResult.failure(
                        StorageTransactionResult.NOT_FOUND,
                        "storage unavailable: " + endpoint.storageId().asString());
    }

    // [REMOVED] bumpRevision(data, key, expected)：旧实现依赖 updateRecord 复查，
    // 在槽位已提交后调用可能失败（缺陷 #6）。已由提交临界区内的 applyRevision 取代。

    /**
     * 补偿：尽力回滚已提交的槽位写（逆序——先撤目标 insert，再补回源 extract）。
     *
     * <p>仅作为提交阶段防御性回退：正常路径下临界区内槽位写是唯一可失败步骤，
     * 校验已全前置。补偿本身尽力而为，失败仅记录日志（此时容器处于不一致状态，
     * 由后续 audit/revision 缺失暴露，交由管理员修复）。</p>
     */
    private static void compensateSlots(
            boolean srcCommitted, boolean tgtCommitted,
            StorageHandleExt srcHandle, StorageHandleExt tgtHandle,
            StorageEndpoint source, StorageEndpoint target,
            String itemId, int count) {
        if (tgtCommitted) {
            try {
                tgtHandle.commitExtract(target.slotIndex(), itemId, count);
            } catch (RuntimeException e) {
                LOGGER.error("PokeEMC: compensation failed to roll back target slot {}: {}",
                        target.slotIndex(), e.getMessage());
            }
        }
        if (srcCommitted) {
            try {
                srcHandle.commitInsert(source.slotIndex(), itemId, count);
            } catch (RuntimeException e) {
                LOGGER.error("PokeEMC: compensation failed to roll back source slot {}: {}",
                        source.slotIndex(), e.getMessage());
            }
        }
    }

    private void addChangedSlot(
            List<StorageItemSlot> out, StorageEndpointData d, int slotIndex) {
        String id = d.handle().itemId(slotIndex);
        int count = d.handle().count(slotIndex);
        if (id != null && count > 0) {
            out.add(new StorageItemSlot(
                    slotIndex, id, count, d.handle().fingerprint(slotIndex)));
        }
    }

    private static UUID auditUuid(long auditId) {
        return auditId < 0
                ? null
                : UUID.nameUUIDFromBytes(
                        ("poketrade-audit-" + auditId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 失败结果附带已解析仓储的最新快照，便于客户端同步状态。 */
    private StorageTransactionResult failWithSnapshots(
            String code, String message,
            StorageEndpointData src, StorageEndpointData tgt) {
        Map<StorageId, StorageSnapshot> snapshots = new LinkedHashMap<>();
        for (StorageEndpointData d : storageEndpoints(src, tgt)) {
            try {
                snapshots.put(
                        d.endpoint().storageId(),
                        new StorageSnapshot(
                                d.endpoint().storageId(), d.record().revision(),
                                d.handle().snapshot().slots()));
            } catch (RuntimeException ignored) {
                // 快照失败不影响失败结果本身
            }
        }
        return new StorageTransactionResult(
                false, code, message, List.of(), Map.of(), snapshots, null);
    }
}
