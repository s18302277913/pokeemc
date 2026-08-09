package com.pokeemc.storage;

import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import com.pokeemc.storage.adapter.StorageHandleImpl;
import com.pokeemc.storage.adapter.SlotStore;
import com.pokeemc.thirdparty.EconomyRegistryImpl;
import com.pokeemc.thirdparty.ProtectionRegistryImpl;
import com.pokeemc.thirdparty.ThirdPartyServices;
import com.poketrade.api.permission.ProtectionCapability;
import com.poketrade.api.permission.ProtectionContext;
import com.poketrade.api.permission.ProtectionProvider;
import com.poketrade.api.permission.ProtectionResult;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageEndpoint;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageSnapshot;
import com.poketrade.api.storage.StorageTransaction;
import com.poketrade.api.storage.StorageTransactionResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务引擎纯 JVM 测试：权限矩阵、revision/指纹并发控制、两阶段原子性、
 * 幂等（同键去重 / 按 actor+session 隔离 / TTL 过期）、非原子适配器降级。
 */
class StorageTransactionServiceTest {

    private static final long TTL_MILLIS = 10_000L;
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final String DIM = "minecraft:overworld";
    private static final String ADAPTER_TYPE = "test_chest";

    private static final StorageId SID_A =
            new StorageId(DIM, ADAPTER_TYPE, "0;64;0");
    private static final StorageId SID_B =
            new StorageId(DIM, ADAPTER_TYPE, "10;64;0");

    /** 与 StorageHandleImplTest 一致的槽位桩，支持按槽位覆盖堆叠上限。 */
    static final class FakeSlots implements SlotStore {
        final String[] ids;
        final int[] counts;
        final int defaultMax;
        final Map<Integer, Integer> maxOverrides = new HashMap<>();

        FakeSlots(int size, int defaultMax) {
            this.ids = new String[size];
            this.counts = new int[size];
            this.defaultMax = defaultMax;
        }

        @Override
        public int size() {
            return ids.length;
        }

        @Override
        public String itemId(int slot) {
            return ids[slot];
        }

        @Override
        public int count(int slot) {
            return counts[slot];
        }

        @Override
        public int maxStack(int slot, String itemId) {
            return maxOverrides.getOrDefault(slot, defaultMax);
        }

        @Override
        public long fingerprint(int slot) {
            return ids[slot] == null ? 0 : 31L * ids[slot].hashCode() + counts[slot];
        }

        @Override
        public void set(int slot, String itemId, int count) {
            ids[slot] = itemId;
            counts[slot] = (itemId == null || count <= 0) ? 0 : count;
        }

        @Override
        public void setChanged() {
        }
    }

    private static final class FakeAdapter implements StorageAdapter {
        private final String typeId;
        private final Function<StorageAdapterContext, Optional<StorageHandle>> opener;

        FakeAdapter(String typeId, Function<StorageAdapterContext, Optional<StorageHandle>> opener) {
            this.typeId = typeId;
            this.opener = opener;
        }

        @Override
        public String typeId() {
            return typeId;
        }

        @Override
        public Set<StorageCapability> capabilities() {
            return Set.of();
        }

        @Override
        public boolean supports(StorageAdapterContext context) {
            return context.storageId().adapterType().equals(typeId);
        }

        @Override
        public Optional<StorageHandle> open(StorageAdapterContext context) {
            return opener.apply(context);
        }
    }

    /** 非原子句柄：仅实现公共 StorageHandle，不提供槽位级指纹/数量。 */
    private static final class NonAtomicHandle implements StorageHandle {
        final StorageId sid;
        boolean closed;

        NonAtomicHandle(StorageId sid) {
            this.sid = sid;
        }

        @Override
        public StorageSnapshot snapshot() {
            return new StorageSnapshot(sid, 1, Map.of());
        }

        @Override
        public boolean simulateInsert(int slotIndex, String itemId, int count) {
            return true;
        }

        @Override
        public boolean simulateExtract(int slotIndex, String itemId, int count) {
            return true;
        }

        @Override
        public void commitInsert(int slotIndex, String itemId, int count) {
        }

        @Override
        public void commitExtract(int slotIndex, String itemId, int count) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private StorageAdapterRegistryImpl registry;
    private StorageSavedData data;
    private final Map<StorageId, FakeSlots> storageSlots = new HashMap<>();
    private final FakeSlots inventorySlots = new FakeSlots(36, 64);
    private final long[] now = {1_000_000L};
    private boolean inventoryOnline = true;
    private StorageTransactionService service;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        ThirdPartyServices.reset(); // 隔离用例间第三方装配
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        registry = new StorageAdapterRegistryImpl();
        registry.register(new FakeAdapter(
                ADAPTER_TYPE,
                ctx -> Optional.of(StorageHandleImpl.of(ctx.storageId(), slotsOf(ctx.storageId())))));
        storageSlots.put(SID_A, new FakeSlots(27, 64));
        storageSlots.put(SID_B, new FakeSlots(27, 64));
        data = new StorageSavedData();
        service = new StorageTransactionService(
                registry,
                new StorageAccessService(
                        id -> Optional.empty(),
                        id -> false,
                        (actor, owner, perm) -> {
                        }),
                () -> data,
                id -> inventoryOnline ? StorageHandleImpl.of(null, inventorySlots) : null,
                id -> {
                },
                () -> now[0],
                TTL_MILLIS);
    }

    private FakeSlots slotsOf(StorageId sid) {
        return storageSlots.computeIfAbsent(sid, ignored -> new FakeSlots(27, 64));
    }

    private StorageKey keyOf(StorageId sid) {
        return StorageKey.of(sid.dimension(), sid.adapterType(), sid.location());
    }

    private StorageTransaction tx(
            UUID actor, String session, String op,
            StorageEndpoint src, StorageEndpoint tgt, int count,
            long srcFp, long tgtFp, Map<StorageId, Long> revisions) {
        return new StorageTransaction(
                actor, session, op, src, tgt, count, srcFp, tgtFp, revisions);
    }

    private StorageTransactionResult assertRejected(StorageTransaction t, String code) {
        StorageTransactionResult result = service.execute(t);
        assertFalse(result.success(), "expected failure, got: " + result);
        assertEquals(code, result.code());
        return result;
    }

    // ---------------------------------------------------------------- 权限矩阵

    @Test
    void ownerCanDepositWithoutGrants() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        inventorySlots.set(0, "minecraft:stone", 5);
        long revisionBefore = record(SID_A).revision();
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.storage(SID_A, 0), 5,
                0, slotsOf(SID_A).fingerprint(0),
                Map.of(SID_A, revisionBefore));
        StorageTransactionResult r = service.execute(t);
        assertTrue(r.success(), "owner deposit should succeed: " + r);
        assertEquals(0, inventorySlots.count(0));
        assertEquals("minecraft:stone", slotsOf(SID_A).itemId(0));
        assertEquals(5, slotsOf(SID_A).count(0));
        assertEquals(revisionBefore + 1, data.getRecord(keyOf(SID_A)).orElseThrow().revision());
        assertEquals(1, data.auditSize(), "deposit must write an audit entry");
    }

    @Test
    void depositWithoutDepositPermissionRejected() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0])
                .withGrant(new StoragePrincipal.Player(ACTOR),
                        StorageGrant.allow(StoragePermission.WITHDRAW)));
        inventorySlots.set(0, "minecraft:stone", 5);
        StorageTransaction t = tx(ACTOR, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.storage(SID_A, 0), 5,
                0, slotsOf(SID_A).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision()));
        StorageTransactionResult r = assertRejected(t, StorageTransactionResult.PERMISSION_DENIED);
        assertEquals(5, inventorySlots.count(0), "source must stay untouched");
        assertNull(slotsOf(SID_A).itemId(0), "target must stay untouched");
        assertTrue(r.updatedSnapshots().containsKey(SID_A),
                "failure should carry the latest storage snapshot for client sync");
    }

    @Test
    void withdrawRequiresWithdrawPermission() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0])
                .withGrant(new StoragePrincipal.Player(ACTOR),
                        StorageGrant.allow(StoragePermission.DEPOSIT)));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        StorageTransaction t = tx(ACTOR, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.inventory(0), 5,
                slotsOf(SID_A).fingerprint(0), 0,
                Map.of(SID_A, record(SID_A).revision()));
        assertRejected(t, StorageTransactionResult.PERMISSION_DENIED);
        assertEquals(5, slotsOf(SID_A).count(0));
        assertEquals(0, inventorySlots.count(0));
    }

    @Test
    void transferRequiresWithdrawOnSourceAndDepositOnTarget() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0])
                .withGrant(new StoragePrincipal.Player(ACTOR),
                        StorageGrant.allow(StoragePermission.WITHDRAW)));
        claim(SID_B, StorageRecord.create(OWNER, "Owner", now[0])
                .withGrant(new StoragePrincipal.Player(ACTOR),
                        StorageGrant.allow(StoragePermission.DEPOSIT)));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        Map<StorageId, Long> revs = Map.of(
                SID_A, record(SID_A).revision(), SID_B, record(SID_B).revision());
        StorageTransaction ok = tx(ACTOR, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_B, 0), 5,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_B).fingerprint(0), revs);
        assertTrue(service.execute(ok).success(), "both permissions present -> allowed");

        // 反例：源缺少 WITHDRAW，只有 DEPOSIT
        data.updateRecord(keyOf(SID_A), record(SID_A).revision(), r -> r.withGrant(
                new StoragePrincipal.Player(ACTOR), StorageGrant.allow(StoragePermission.DEPOSIT)));
        StorageTransaction denied = tx(ACTOR, "s1", "o2",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_B, 0), 5,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_B).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision(), SID_B, record(SID_B).revision()));
        assertRejected(denied, StorageTransactionResult.PERMISSION_DENIED);
    }

    @Test
    void sameStorageMoveRequiresWithdrawAndDeposit() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0])
                .withGrant(new StoragePrincipal.Player(ACTOR),
                        StorageGrant.allow(StoragePermission.DEPOSIT, StoragePermission.WITHDRAW)));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        StorageTransaction ok = tx(ACTOR, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_A, 1), 5,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_A).fingerprint(1),
                Map.of(SID_A, record(SID_A).revision()));
        assertTrue(service.execute(ok).success());
        assertNull(slotsOf(SID_A).itemId(0));
        assertEquals(5, slotsOf(SID_A).count(1));

        // 反例：仅 DEPOSIT 不允许在同仓储内移动（需要 WITHDRAW）
        data.updateRecord(keyOf(SID_A), record(SID_A).revision(),
                r -> r.withGrant(new StoragePrincipal.Player(ACTOR),
                        StorageGrant.allow(StoragePermission.DEPOSIT)));
        slotsOf(SID_A).set(0, "minecraft:dirt", 3);
        StorageTransaction denied = tx(ACTOR, "s1", "o2",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_A, 1), 3,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_A).fingerprint(1),
                Map.of(SID_A, record(SID_A).revision()));
        assertRejected(denied, StorageTransactionResult.PERMISSION_DENIED);
    }

    // ---------------------------------------------------------------- 并发控制

    @Test
    void staleRevisionRejected() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_A, 1), 5,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_A).fingerprint(1),
                Map.of(SID_A, record(SID_A).revision() + 99));
        assertRejected(t, StorageTransactionResult.REVISION_CONFLICT);
        assertEquals(5, slotsOf(SID_A).count(0), "nothing may change on conflict");
        assertNull(slotsOf(SID_A).itemId(1));
    }

    @Test
    void fingerprintMismatchRejected() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        claim(SID_B, StorageRecord.create(OWNER, "Owner", now[0]));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_B, 0), 5,
                slotsOf(SID_A).fingerprint(0) + 1, slotsOf(SID_B).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision(), SID_B, record(SID_B).revision()));
        assertRejected(t, "content_changed");
        assertEquals(5, slotsOf(SID_A).count(0));
        assertNull(slotsOf(SID_B).itemId(0));
    }

    // ---------------------------------------------------------------- 数量/容量/不可堆叠

    @Test
    void requestedCountClampedToAvailable() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        inventorySlots.set(0, "minecraft:stone", 3);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.storage(SID_A, 0), 10,
                0, slotsOf(SID_A).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision()));
        StorageTransactionResult r = service.execute(t);
        assertTrue(r.success(), "clamping to available must succeed: " + r);
        assertEquals(3, slotsOf(SID_A).count(0), "moved exactly what was available");
    }

    @Test
    void targetCapacityExceededRejectsWholeTransaction() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        claim(SID_B, StorageRecord.create(OWNER, "Owner", now[0]));
        slotsOf(SID_A).set(0, "minecraft:stone", 10);
        slotsOf(SID_B).set(0, "minecraft:stone", 62);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_B, 0), 10,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_B).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision(), SID_B, record(SID_B).revision()));
        assertRejected(t, "target_blocked");
        assertEquals(10, slotsOf(SID_A).count(0), "source must stay untouched (all-or-nothing)");
        assertEquals(62, slotsOf(SID_B).count(0));
    }

    @Test
    void nonStackableItemCannotMerge() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        claim(SID_B, StorageRecord.create(OWNER, "Owner", now[0]));
        FakeSlots a = slotsOf(SID_A);
        FakeSlots b = slotsOf(SID_B);
        a.maxOverrides.put(0, 1);
        b.maxOverrides.put(0, 1);
        a.set(0, "minecraft:diamond_sword", 1);
        b.set(0, "minecraft:diamond_sword", 1);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_B, 0), 1,
                a.fingerprint(0), b.fingerprint(0),
                Map.of(SID_A, record(SID_A).revision(), SID_B, record(SID_B).revision()));
        assertRejected(t, "target_blocked");
        assertEquals(1, a.count(0));
        assertEquals(1, b.count(0));
    }

    // ---------------------------------------------------------------- 幂等

    @Test
    void duplicateOperationReturnsSameResultWithoutReExecution() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        inventorySlots.set(0, "minecraft:stone", 5);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.storage(SID_A, 0), 5,
                0, slotsOf(SID_A).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision()));
        StorageTransactionResult first = service.execute(t);
        assertTrue(first.success());
        long revisionAfterFirst = record(SID_A).revision();
        assertEquals(1, data.auditSize());

        StorageTransactionResult second = service.execute(t);
        assertEquals(first.code(), second.code());
        assertEquals(first.message(), second.message());
        assertEquals(5, slotsOf(SID_A).count(0), "item must not be moved a second time");
        assertEquals(revisionAfterFirst, record(SID_A).revision(), "revision must not bump twice");
        assertEquals(1, data.auditSize(), "no second audit entry for a duplicate packet");
    }

    @Test
    void duplicateFailureAlsoReturnsCachedResult() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_A, 1), 5,
                slotsOf(SID_A).fingerprint(0) + 1, slotsOf(SID_A).fingerprint(1),
                Map.of(SID_A, record(SID_A).revision()));
        StorageTransactionResult first = assertRejected(t, "content_changed");
        StorageTransactionResult second = service.execute(t);
        assertEquals(first.code(), second.code());
        assertEquals(first.message(), second.message());
    }

    @Test
    void idempotencyIsIsolatedByActor() {
        StorageId sidC = new StorageId(DIM, ADAPTER_TYPE, "20;64;0");
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        claim(sidC, StorageRecord.create(OTHER, "Other", now[0]));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        slotsOf(sidC).set(0, "minecraft:dirt", 3);
        Map<StorageId, Long> revsA = Map.of(SID_A, record(SID_A).revision());
        Map<StorageId, Long> revsC = Map.of(sidC, record(sidC).revision());

        StorageTransactionResult first = service.execute(tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_A, 1), 5,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_A).fingerprint(1), revsA));
        assertTrue(first.success());
        // 不同 actor、相同 session/op：不是重复包，必须独立执行
        StorageTransactionResult second = service.execute(tx(OTHER, "s1", "o1",
                StorageEndpoint.storage(sidC, 0), StorageEndpoint.storage(sidC, 1), 3,
                slotsOf(sidC).fingerprint(0), slotsOf(sidC).fingerprint(1), revsC));
        assertTrue(second.success(), "different actor must not reuse the cached result");
        assertEquals(3, slotsOf(sidC).count(1), "second actor's move must actually run");
    }

    @Test
    void idempotencyIsIsolatedBySession() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        StorageTransaction first = tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_A, 1), 5,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_A).fingerprint(1),
                Map.of(SID_A, record(SID_A).revision()));
        assertTrue(service.execute(first).success());

        // 同 actor/op、不同 session：视为新操作；源已空 -> 独立失败而非返回旧成功
        StorageTransaction second = tx(OWNER, "s2", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_A, 1), 5,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_A).fingerprint(1),
                Map.of(SID_A, record(SID_A).revision()));
        assertRejected(second, "source_empty");
    }

    @Test
    void idempotentCacheExpiresAfterTtl() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        inventorySlots.set(0, "minecraft:stone", 10);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.storage(SID_A, 0), 5,
                0, slotsOf(SID_A).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision()));
        assertTrue(service.execute(t).success());
        assertEquals(5, slotsOf(SID_A).count(0));

        now[0] += TTL_MILLIS + 1; // 缓存过期后同一字节重放：必须重新执行而不是返回缓存成功
        // 重放必须携带当前 revision/指纹重新走完整事务（若返回缓存则会命中旧的成功结果）
        StorageTransaction fresh = tx(OWNER, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.storage(SID_A, 0), 5,
                0, slotsOf(SID_A).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision()));
        StorageTransactionResult replayed = service.execute(fresh);
        assertTrue(replayed.success(), "expired cache must re-execute: " + replayed);
        assertEquals(10, slotsOf(SID_A).count(0),
                "fresh execution moves the remaining 5 from inventory again (10 total)");
    }

    // ---------------------------------------------------------------- 降级与离线

    @Test
    void nonAtomicAdapterIsDegraded() {
        StorageId sidNa = new StorageId(DIM, "test_non_atomic", "5;64;5");
        NonAtomicHandle naHandle = new NonAtomicHandle(sidNa);
        registry.register(new FakeAdapter(
                "test_non_atomic",
                ctx -> Optional.of(naHandle)));
        claim(sidNa, StorageRecord.create(OWNER, "Owner", now[0]));
        claim(SID_B, StorageRecord.create(OWNER, "Owner", now[0]));
        slotsOf(SID_B).set(0, "minecraft:stone", 5);

        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(sidNa, 0), StorageEndpoint.storage(SID_B, 0), 5,
                0, slotsOf(SID_B).fingerprint(0),
                Map.of(sidNa, record(sidNa).revision(), SID_B, record(SID_B).revision()));
        StorageTransactionResult r = assertRejected(t, StorageTransactionResult.ADAPTER_UNAVAILABLE);
        assertTrue(r.message().contains("non-atomic"), "message should explain degradation: " + r.message());
        assertTrue(naHandle.closed, "degraded handle must be closed");
        assertEquals(5, slotsOf(SID_B).count(0), "target must stay untouched");
    }

    @Test
    void inventoryEndpointRequiresOnlinePlayer() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        inventoryOnline = false;
        inventorySlots.set(0, "minecraft:stone", 5);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.storage(SID_A, 0), 5,
                0, slotsOf(SID_A).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision()));
        assertRejected(t, "player_offline");
        assertEquals(5, inventorySlots.count(0));
        assertNull(slotsOf(SID_A).itemId(0));
    }

    @Test
    void inventoryToInventoryIsRejected() {
        assertRejected(tx(OWNER, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.inventory(1), 1,
                0, 0, Map.of()), "invalid_endpoints");
    }

    // ---------------------------------------------------------------- 阶段 6：第三方保护链 AND 集成

    private static ProtectionProvider provider(String modId, ProtectionResult result) {
        return new ProtectionProvider() {
            @Override
            public String modId() {
                return modId;
            }

            @Override
            public Set<ProtectionCapability> capabilities() {
                return Set.of(ProtectionCapability.LOCK_PROTECTION);
            }

            @Override
            public ProtectionResult check(ProtectionContext context) {
                return result;
            }
        };
    }

    private static ProtectionProvider throwingProvider(String modId) {
        return new ProtectionProvider() {
            @Override
            public String modId() {
                return modId;
            }

            @Override
            public Set<ProtectionCapability> capabilities() {
                return Set.of();
            }

            @Override
            public ProtectionResult check(ProtectionContext context) {
                throw new IllegalStateException("boom");
            }
        };
    }

    /** 经 ThirdPartyServices 注入变体装配保护链（不直接触碰保护链内部实现）。 */
    private void installProtection(ProtectionProvider... providers) {
        ProtectionRegistryImpl protection = new ProtectionRegistryImpl();
        for (ProtectionProvider p : providers) {
            protection.register(p);
        }
        ThirdPartyServices.init(protection, new EconomyRegistryImpl(),
                new StorageAdapterRegistryImpl());
    }

    @Test
    void thirdPartyDenyRejectsWithdraw() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0])
                .withGrant(new StoragePrincipal.Player(ACTOR),
                        StorageGrant.allow(StoragePermission.WITHDRAW)));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        installProtection(provider("griefdefense", ProtectionResult.DENY));

        StorageTransaction t = tx(ACTOR, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.inventory(0), 5,
                slotsOf(SID_A).fingerprint(0), 0,
                Map.of(SID_A, record(SID_A).revision()));
        StorageTransactionResult r = assertRejected(t, StorageTransactionResult.PERMISSION_DENIED);
        assertEquals("third-party protection denied withdraw", r.message());
        assertEquals(5, slotsOf(SID_A).count(0), "source must stay untouched");
        assertEquals(0, inventorySlots.count(0));
        assertTrue(r.updatedSnapshots().containsKey(SID_A),
                "failure should carry the latest storage snapshot for client sync");
    }

    @Test
    void thirdPartyDenyRejectsDeposit() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        inventorySlots.set(0, "minecraft:stone", 5);
        installProtection(provider("griefdefense", ProtectionResult.DENY));

        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.storage(SID_A, 0), 5,
                0, slotsOf(SID_A).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision()));
        StorageTransactionResult r = assertRejected(t, StorageTransactionResult.PERMISSION_DENIED);
        assertEquals("third-party protection denied deposit", r.message());
        assertEquals(5, inventorySlots.count(0), "source must stay untouched");
        assertNull(slotsOf(SID_A).itemId(0), "target must stay untouched");
    }

    @Test
    void thirdPartyAllowShortCircuitsLaterDeny() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        claim(SID_B, StorageRecord.create(OWNER, "Owner", now[0]));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        installProtection(provider("a", ProtectionResult.ALLOW),
                provider("b", ProtectionResult.DENY));

        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_B, 0), 5,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_B).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision(), SID_B, record(SID_B).revision()));
        assertTrue(service.execute(t).success(), "ALLOW must short-circuit later DENY");
        assertEquals(5, slotsOf(SID_B).count(0));
    }

    @Test
    void providerExceptionDegradesToAllow() {
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        claim(SID_B, StorageRecord.create(OWNER, "Owner", now[0]));
        slotsOf(SID_A).set(0, "minecraft:stone", 5);
        installProtection(throwingProvider("griefdefense"));

        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.storage(SID_A, 0), StorageEndpoint.storage(SID_B, 0), 5,
                slotsOf(SID_A).fingerprint(0), slotsOf(SID_B).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision(), SID_B, record(SID_B).revision()));
        assertTrue(service.execute(t).success(), "provider error must not block trading");
        assertEquals(5, slotsOf(SID_B).count(0));
    }

    @Test
    void unassembledHookAllows() {
        ThirdPartyServices.reset(); // 未装配：protectionHook() 返回 unloaded()，恒放行
        claim(SID_A, StorageRecord.create(OWNER, "Owner", now[0]));
        inventorySlots.set(0, "minecraft:stone", 5);
        StorageTransaction t = tx(OWNER, "s1", "o1",
                StorageEndpoint.inventory(0), StorageEndpoint.storage(SID_A, 0), 5,
                0, slotsOf(SID_A).fingerprint(0),
                Map.of(SID_A, record(SID_A).revision()));
        assertTrue(service.execute(t).success(), "unloaded hook must allow");
        assertEquals(5, slotsOf(SID_A).count(0));
    }

    // ---------------------------------------------------------------- 辅助

    private StorageRecord record(StorageId sid) {
        return data.getRecord(keyOf(sid)).orElseThrow();
    }

    private void claim(StorageId sid, StorageRecord record) {
        StorageKey key = keyOf(sid);
        assertTrue(data.claim(key, record, 0, 0), "claim must succeed: " + key);
    }
}
