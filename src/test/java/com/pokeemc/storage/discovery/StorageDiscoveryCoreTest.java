package com.pokeemc.storage.discovery;

import com.pokeemc.storage.StorageGrant;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.StoragePrincipal;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageQuery;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发现服务核心纯 JVM 逻辑（Task 6）：半径判定、限频判定、稳定排序、
 * 区块范围索引与双箱记录迁移（ACL 不变）。
 */
class StorageDiscoveryCoreTest {

    private static final String DIM = "minecraft:overworld";

    private static final StorageAdapter STUB = new StorageAdapter() {
        @Override
        public String typeId() {
            return "stub";
        }

        @Override
        public Set<StorageCapability> capabilities() {
            return Set.of();
        }

        @Override
        public boolean supports(StorageAdapterContext context) {
            return false;
        }

        @Override
        public Optional<StorageHandle> open(StorageAdapterContext context) {
            return Optional.empty();
        }
    };

    // ------------------------------------------------------------- 半径判定

    @Test
    void withinRadiusIsEuclideanInclusive() {
        assertTrue(StorageDiscoveryService.withinRadius(0, 0, 32));
        assertTrue(StorageDiscoveryService.withinRadius(32, 0, 32));
        assertTrue(StorageDiscoveryService.withinRadius(22, 22, 32)); // 968 <= 1024
        assertFalse(StorageDiscoveryService.withinRadius(33, 0, 32));
        assertFalse(StorageDiscoveryService.withinRadius(24, 24, 32)); // 1152 > 1024
        assertFalse(StorageDiscoveryService.withinRadius(2, 0, 1));
    }

    // ------------------------------------------------------------- 限频判定

    @Test
    void onlyVanillaContainersAreListable() {
        assertTrue(StorageDiscoveryService.LISTABLE_STORAGE_TYPES.contains("vanilla_chest"));
        assertTrue(StorageDiscoveryService.LISTABLE_STORAGE_TYPES.contains("vanilla_double_chest"));
        assertTrue(StorageDiscoveryService.LISTABLE_STORAGE_TYPES.contains("vanilla_trapped_chest"));
        assertTrue(StorageDiscoveryService.LISTABLE_STORAGE_TYPES.contains("vanilla_barrel"));
        // 模组容器（能量凝聚器等）不再出现在仓储列表
        assertFalse(StorageDiscoveryService.LISTABLE_STORAGE_TYPES.contains("poketrade_condenser"));
    }

    @Test
    void rateLimitedRequiresElapsedCooldown() {
        // lastTick == 0 视为从未查询，不限频
        assertFalse(StorageDiscoveryService.rateLimited(0, 100, 10));
        // 同 tick 重复查询 → 限频
        assertTrue(StorageDiscoveryService.rateLimited(100, 100, 10));
        // 未满冷却 → 限频
        assertTrue(StorageDiscoveryService.rateLimited(100, 109, 10));
        // 恰好满冷却 → 放行
        assertFalse(StorageDiscoveryService.rateLimited(100, 110, 10));
        assertFalse(StorageDiscoveryService.rateLimited(100, 200, 10));
    }

    // ------------------------------------------------------------- 稳定排序

    private static StorageKey key(int x, int z) {
        return StorageKey.of(DIM, "vanilla_chest", x + ",64," + z);
    }

    @Test
    void distanceSortAscendingWithStableKeyTieBreak() {
        StorageRecord near = StorageRecord.create(UUID.randomUUID(), "A", 100);
        StorageRecord far = StorageRecord.create(UUID.randomUUID(), "B", 200);
        StorageDiscoveryService.Candidate c1 = new StorageDiscoveryService.Candidate(
                key(5, 0), far, STUB, 5, 0, 0, false);
        StorageDiscoveryService.Candidate c2 = new StorageDiscoveryService.Candidate(
                key(3, 0), near, STUB, 3, 0, 0, true);
        StorageDiscoveryService.Candidate c3 = new StorageDiscoveryService.Candidate(
                key(2, 0), near, STUB, 5, 0, 0, true); // 与 c1 同距离

        List<StorageDiscoveryService.Candidate> list = new java.util.ArrayList<>(
                List.of(c1, c3, c2));
        list.sort(StorageDiscoveryService.stableComparator(StorageQuery.Sort.DISTANCE));

        assertEquals(List.of(c2, c3, c1), list); // 距离升序，tie-break 按键字符串
        // c1(5,0) 与 c3(2,0)：同距离时键 "…5,64,0" > "…2,64,0"，故 c3 在前
    }

    @Test
    void nameSortIsStableByDisplayName() {
        StorageRecord alpha = StorageRecord.create(UUID.randomUUID(), "Alice", 100)
                .renamed("Alpha Chest");
        StorageRecord beta = StorageRecord.create(UUID.randomUUID(), "Bob", 200)
                .renamed("Beta Vault");
        StorageDiscoveryService.Candidate cA = new StorageDiscoveryService.Candidate(
                key(1, 0), alpha, STUB, 4, 0, 0, false);
        StorageDiscoveryService.Candidate cB = new StorageDiscoveryService.Candidate(
                key(2, 0), beta, STUB, 1, 0, 0, false);

        List<StorageDiscoveryService.Candidate> list = new java.util.ArrayList<>(
                List.of(cB, cA));
        list.sort(StorageDiscoveryService.stableComparator(StorageQuery.Sort.NAME));

        assertEquals(List.of(cA, cB), list);
    }

    @Test
    void recentlyUpdatedSortDescending() {
        StorageRecord old = StorageRecord.create(UUID.randomUUID(), "A", 100)
                .renamed("Old").touch(300);
        StorageRecord fresh = StorageRecord.create(UUID.randomUUID(), "B", 200)
                .renamed("Fresh").touch(900);
        StorageDiscoveryService.Candidate cOld = new StorageDiscoveryService.Candidate(
                key(1, 0), old, STUB, 4, 0, 0, false);
        StorageDiscoveryService.Candidate cFresh = new StorageDiscoveryService.Candidate(
                key(2, 0), fresh, STUB, 1, 0, 0, false);

        List<StorageDiscoveryService.Candidate> list = new java.util.ArrayList<>(
                List.of(cOld, cFresh));
        list.sort(StorageDiscoveryService.stableComparator(StorageQuery.Sort.RECENTLY_UPDATED));

        assertEquals(List.of(cFresh, cOld), list);
    }

    @Test
    void freeSlotsSortDescending() {
        StorageRecord a = StorageRecord.create(UUID.randomUUID(), "A", 100);
        StorageRecord b = StorageRecord.create(UUID.randomUUID(), "B", 100);
        StorageDiscoveryService.Candidate cFull = new StorageDiscoveryService.Candidate(
                key(1, 0), a, STUB, 4, 27, 20, true); // 7 空闲
        StorageDiscoveryService.Candidate cEmpty = new StorageDiscoveryService.Candidate(
                key(2, 0), b, STUB, 4, 27, 5, true); // 22 空闲

        List<StorageDiscoveryService.Candidate> list = new java.util.ArrayList<>(
                List.of(cFull, cEmpty));
        list.sort(StorageDiscoveryService.stableComparator(StorageQuery.Sort.FREE_SLOTS));

        assertEquals(List.of(cEmpty, cFull), list);
    }

    // ----------------------------------------------------- 区块索引与迁移

    @Test
    void keysInChunksCoversClosedRange() {
        StorageSavedData data = StorageSavedData.create();
        StorageKey k1 = key(3, 4);
        StorageKey k2 = key(4, 4);
        StorageKey k3 = key(40, 4);
        UUID owner = UUID.randomUUID();
        data.claim(k1, StorageRecord.create(owner, "A", 100), 0, 0);
        data.claim(k2, StorageRecord.create(owner, "B", 100), 0, 0);
        data.claim(k3, StorageRecord.create(owner, "C", 100), 2, 0);

        Set<StorageKey> inRange = data.keysInChunks(DIM, 0, 1, 0, 1);

        assertTrue(inRange.contains(k1));
        assertTrue(inRange.contains(k2));
        assertFalse(inRange.contains(k3));

        // 单区块查询
        assertTrue(data.keysInChunk(DIM, 0, 0).contains(k1));
        assertFalse(data.keysInChunk(DIM, 2, 0).contains(k1));
    }

    @Test
    void migrateRecordPreservesAclAndMovesIndex() {
        StorageSavedData data = StorageSavedData.create();
        StorageKey from = StorageKey.of(DIM, "vanilla_chest", "0,64,0");
        StorageKey to = StorageKey.of(DIM, "vanilla_double_chest", "0,64,0");
        UUID owner = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        StorageRecord record = StorageRecord.create(owner, "Alice", 1_000L)
                .withGrant(new StoragePrincipal.Player(friend),
                        StorageGrant.allow(StoragePermission.VIEW, StoragePermission.DEPOSIT));
        data.claim(from, record, 0, 4);

        assertTrue(data.migrateRecord(from, to, 0, 4));

        assertTrue(data.getRecord(from).isEmpty());
        StorageRecord moved = data.getRecord(to).orElseThrow();
        assertEquals(owner, moved.ownerId());
        assertEquals(record.grants(), moved.grants()); // ACL 原样保留
        assertEquals("Alice", moved.ownerName());
        assertTrue(moved.updatedAtEpochMillis() >= 1_000L);

        // 区块索引跟随迁移
        Set<StorageKey> inChunk = data.keysInChunks(DIM, 0, 0, 4, 4);
        assertFalse(inChunk.contains(from));
        assertTrue(inChunk.contains(to));
    }

    @Test
    void migrateRecordRejectsOccupiedTargetAndMissingSource() {
        StorageSavedData data = StorageSavedData.create();
        StorageKey from = StorageKey.of(DIM, "vanilla_chest", "0,64,0");
        StorageKey to = StorageKey.of(DIM, "vanilla_double_chest", "0,64,0");
        StorageKey ghost = StorageKey.of(DIM, "vanilla_barrel", "1,64,0");
        UUID owner = UUID.randomUUID();
        data.claim(from, StorageRecord.create(owner, "A", 100), 0, 4);
        data.claim(to, StorageRecord.create(owner, "B", 100), 0, 4);

        assertFalse(data.migrateRecord(from, to, 0, 4)); // to 已存在
        assertFalse(data.migrateRecord(ghost, from, 0, 4)); // 源缺失
        assertFalse(data.migrateRecord(from, from, 0, 4)); // 自迁移
        assertTrue(data.getRecord(from).isPresent());
        assertTrue(data.getRecord(to).isPresent());
    }

    @Test
    void claimRejectsDuplicateKey() {
        StorageSavedData data = StorageSavedData.create();
        StorageKey k = key(5, 5);
        UUID owner = UUID.randomUUID();
        assertTrue(data.claim(k, StorageRecord.create(owner, "A", 100), 0, 0));
        assertFalse(data.claim(k, StorageRecord.create(owner, "B", 200), 0, 0));
        assertEquals("A", data.getRecord(k).orElseThrow().ownerName());
    }

    @Test
    void comparatorNeverComparesInconsistentTieKeys() {
        // 同距离不同键：tie-break 使用 key.asString()，稳定且无 null
        StorageDiscoveryService.Candidate a = new StorageDiscoveryService.Candidate(
                key(1, 0), StorageRecord.create(UUID.randomUUID(), "A", 100), STUB,
                7, 0, 0, false);
        StorageDiscoveryService.Candidate b = new StorageDiscoveryService.Candidate(
                key(2, 0), StorageRecord.create(UUID.randomUUID(), "B", 100), STUB,
                7, 0, 0, false);

        Comparator<StorageDiscoveryService.Candidate> cmp =
                StorageDiscoveryService.stableComparator(StorageQuery.Sort.DISTANCE);
        int ab = cmp.compare(a, b);
        int ba = cmp.compare(b, a);

        assertEquals(0, Integer.signum(ab) + Integer.signum(ba));
    }
}
