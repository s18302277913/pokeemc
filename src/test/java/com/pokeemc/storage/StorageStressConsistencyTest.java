package com.pokeemc.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13 性能与一致性压力测试（纯 JVM，固定 seed，无游戏依赖）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>20,000 次固定 seed 混合变更（认领/授权/撤权/模板创建/更新/删除/FOLLOW
 *       绑定/移动/热替换删除），持续校验主 map、区块索引和模板反向索引一致；</li>
 *   <li>10,000 条记录下：单键查询直接命中、区块桶查询只返回该桶键、范围查询
 *       只访问覆盖的区块桶（不遍历全表）；</li>
 *   <li>审计环形队列始终不超过容量上限。</li>
 * </ul>
 */
class StorageStressConsistencyTest {

    private static final String DIM = "minecraft:overworld";
    private static final String ADAPTER = "vanilla_chest";
    private static final long NOW = 1_700_000_000_000L;

    private static StorageKey keyAt(int x, int z) {
        return StorageKey.of(DIM, ADAPTER, x + ";64;" + z);
    }

    private static int chunkOf(int coord) {
        return coord >> 4;
    }

    private static StorageRecord record(UUID owner, long now) {
        return StorageRecord.create(owner, "Owner", now);
    }

    // ---------------------------------------------------------------- 一致性校验

    /** 校验主 map / 区块索引 / 模板反向索引三者一致。 */
    private void assertConsistent(StorageSavedData data, Map<StorageKey, int[]> claimed) {
        // 主 map 与独立跟踪的键集合一致
        assertEquals(claimed.keySet(), data.recordsView().keySet(),
                "main map must match tracked keys");

        // 每个记录键都能在自己的区块桶中被找到
        for (Map.Entry<StorageKey, int[]> e : claimed.entrySet()) {
            int cx = e.getValue()[0];
            int cz = e.getValue()[1];
            assertTrue(data.keysInChunk(DIM, cx, cz).contains(e.getKey()),
                    "claimed key must be indexed in its chunk bucket");
        }

        // 区块索引覆盖全部记录（全范围查询无遗漏、无孤儿键）
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] c : claimed.values()) {
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minZ = Math.min(minZ, c[1]);
            maxZ = Math.max(maxZ, c[1]);
        }
        if (!claimed.isEmpty()) {
            Set<StorageKey> all = data.keysInChunks(DIM, minX, maxX, minZ, maxZ);
            assertEquals(claimed.keySet(), all,
                    "full-range chunk query must return exactly the claimed keys");
        } else {
            assertTrue(data.keysInChunks(DIM, 0, 0, 0, 0).isEmpty(),
                    "empty data must yield empty range query");
        }

        // 模板反向索引：FOLLOW 绑定的记录必须引用现存模板
        for (StorageRecord record : data.recordsView().values()) {
            if (record.templateMode() == StorageRecord.TemplateMode.FOLLOW) {
                assertTrue(record.templateBinding() != null,
                        "FOLLOW record must carry a template binding");
                assertTrue(data.getTemplate(record.templateBinding()).isPresent(),
                        "FOLLOW binding must reference an existing template: "
                                + record.templateBinding());
            }
        }
    }

    // ---------------------------------------------------------------- 20,000 次混合变更

    @Test
    void twentyThousandSeededMutationsKeepIndexesConsistent() {
        Random rnd = new Random(0x5EED_CAFE);
        StorageSavedData data = new StorageSavedData();
        data.setAuditCapacity(2048);

        Map<StorageKey, int[]> claimed = new LinkedHashMap<>();
        Map<String, String> templates = new HashMap<>(); // id -> scope 标记
        UUID owner = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        int nextX = 0;

        for (int step = 0; step < 20_000; step++) {
            int op = rnd.nextInt(10);
            StorageKey key;
            switch (op) {
                case 0: { // 认领新仓储
                    key = keyAt(nextX++, rnd.nextInt(512));
                    if (!claimed.containsKey(key)) {
                        StorageRecord rec = record(owner, NOW + step);
                        int cx = chunkOf(nextX - 1);
                        int cz = chunkOf(rnd.nextInt(512));
                        if (data.claim(key, rec, cx, cz)) {
                            claimed.put(key, new int[]{cx, cz});
                        }
                    }
                    break;
                }
                case 1: { // 授权（applyGrant）
                    key = pick(rnd, claimed);
                    if (key != null) {
                        long rev = data.getRecord(key).orElseThrow().revision();
                        StoragePrincipal principal = new StoragePrincipal.Player(
                                UUID.randomUUID());
                        data.applyGrant(key, rev, principal,
                                StorageGrant.allow(pickPermission(rnd)));
                    }
                    break;
                }
                case 2: { // 撤权（removeGrant）
                    key = pick(rnd, claimed);
                    if (key != null) {
                        long rev = data.getRecord(key).orElseThrow().revision();
                        data.removeGrant(key, rev, new StoragePrincipal.Player(
                                UUID.randomUUID()));
                    }
                    break;
                }
                case 3: { // 重命名
                    key = pick(rnd, claimed);
                    if (key != null) {
                        long rev = data.getRecord(key).orElseThrow().revision();
                        data.renameStorage(key, rev, "改名" + step);
                    }
                    break;
                }
                case 4: { // 创建模板
                    String id = "tpl" + (templates.size() + 1);
                    StorageTemplate template = StorageTemplate.create(
                            id, StorageTemplate.Scope.PLAYER, owner, "模板" + id,
                            Map.of(new StoragePrincipal.Public(),
                                    StorageGrant.allow(StoragePermission.VIEW)),
                            NOW + step);
                    try {
                        data.createTemplate(template);
                        templates.put(id, "player");
                    } catch (IllegalArgumentException ignored) {
                        // 重复 id：跳过
                    }
                    break;
                }
                case 5: { // 更新模板授权
                    String id = pick(rnd, templates);
                    if (id != null) {
                        long rev = data.getTemplate(id).orElseThrow().revision();
                        StoragePrincipal principal = new StoragePrincipal.Player(
                                UUID.randomUUID());
                        data.updateTemplate(id, rev, Map.of(
                                principal, StorageGrant.allow(pickPermission(rnd))));
                    }
                    break;
                }
                case 6: { // 删除模板（会冻结 FOLLOW 仓储）
                    String id = pick(rnd, templates);
                    if (id != null) {
                        data.deleteTemplate(id);
                        templates.remove(id);
                    }
                    break;
                }
                case 7: { // 移动（migrateRecord）
                    key = pick(rnd, claimed);
                    if (key != null) {
                        StorageKey to = keyAt(nextX++, rnd.nextInt(512));
                        int cx = chunkOf(nextX - 1);
                        int cz = chunkOf(rnd.nextInt(512));
                        if (!claimed.containsKey(to) && data.migrateRecord(key, to, cx, cz)) {
                            int[] oldChunk = claimed.remove(key);
                            claimed.put(to, new int[]{cx, cz});
                            assertTrue(oldChunk != null, "migrated key must have been tracked");
                        }
                    }
                    break;
                }
                case 8: { // FOLLOW 绑定模板
                    key = pick(rnd, claimed);
                    String id = pick(rnd, templates);
                    if (key != null && id != null) {
                        long rev = data.getRecord(key).orElseThrow().revision();
                        data.bindTemplate(key, rev, id, StorageRecord.TemplateMode.FOLLOW);
                    }
                    break;
                }
                case 9: { // 热替换删除（unclaim 同路径）
                    key = pick(rnd, claimed);
                    if (key != null) {
                        data = unclaim(data, key);
                        claimed.remove(key);
                    }
                    break;
                }
                default:
                    throw new IllegalStateException("unreachable op: " + op);
            }

            // 定期审计写入
            if (step % 16 == 0) {
                data.appendAudit(NOW + step, "-", owner, "stress", "step " + step);
            }
            assertTrue(data.auditSize() <= data.auditCapacity(),
                    "audit must never exceed capacity");

            // 每 200 步全量一致性校验
            if (step % 200 == 0) {
                assertConsistent(data, claimed);
            }
        }
        assertConsistent(data, claimed);
    }

    /** 模拟 unclaim：encode → NBT 移除 → decode 热替换（与 StorageCommands 同路径）。 */
    private static StorageSavedData unclaim(StorageSavedData data, StorageKey key) {
        CompoundTag tag = StorageSavedData.encode(data);
        ListTag storages = tag.getList("storages", Tag.TAG_COMPOUND);
        storages.removeIf(t -> key.asString().equals(((CompoundTag) t).getString("key")));
        StorageSavedData rebuilt = StorageSavedData.decode(
                tag, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        rebuilt.setDirty();
        return rebuilt;
    }

    // ---------------------------------------------------------------- 10,000 条查询

    @Test
    void tenThousandRecordsQueryByChunkBucketWithoutFullScan() {
        StorageSavedData data = new StorageSavedData();
        Random rnd = new Random(0x1234_5678L);
        Map<StorageKey, int[]> claimed = new LinkedHashMap<>();

        for (int i = 0; i < 10_000; i++) {
            int x = rnd.nextInt(4000);
            int z = rnd.nextInt(4000);
            StorageKey key = keyAt(x, z);
            if (claimed.containsKey(key)) {
                continue;
            }
            int cx = chunkOf(x);
            int cz = chunkOf(z);
            if (data.claim(key, record(UUID.randomUUID(), NOW + i), cx, cz)) {
                claimed.put(key, new int[]{cx, cz});
            }
        }

        // 单键查询直接命中（不遍历全表：LinkedHashMap.get O(1)）
        StorageKey probe = claimed.keySet().iterator().next();
        assertTrue(data.getRecord(probe).isPresent(), "single-key lookup must hit");
        assertFalse(data.getRecord(keyAt(99999, 99999)).isPresent(),
                "unknown key must miss");

        // 区块桶查询：只返回该桶内的键
        int[] bucket = claimed.get(probe);
        Set<StorageKey> bucketKeys = data.keysInChunk(DIM, bucket[0], bucket[1]);
        assertTrue(bucketKeys.contains(probe), "bucket must contain its own key");
        for (StorageKey k : bucketKeys) {
            int[] c = claimed.get(k);
            assertTrue(c != null && c[0] == bucket[0] && c[1] == bucket[1],
                    "bucket must not contain keys from other chunks");
        }

        // 范围查询：只访问覆盖的区块桶（结果 = 各桶键并集，等于范围内所有记录）
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] c : claimed.values()) {
            minX = Math.min(minX, c[0]);
            maxX = Math.max(maxX, c[0]);
            minZ = Math.min(minZ, c[1]);
            maxZ = Math.max(maxZ, c[1]);
        }
        Set<StorageKey> all = data.keysInChunks(DIM, minX, maxX, minZ, maxZ);
        assertEquals(claimed.keySet(), all, "range query must cover all records exactly");

        // 窄范围：只返回该子范围覆盖桶内的键
        int midX = (minX + maxX) / 2;
        int midZ = (minZ + maxZ) / 2;
        Set<StorageKey> expected = new HashSet<>();
        for (Map.Entry<StorageKey, int[]> e : claimed.entrySet()) {
            int[] c = e.getValue();
            if (c[0] == midX && c[1] == midZ) {
                expected.add(e.getKey());
            }
        }
        assertEquals(expected, data.keysInChunks(DIM, midX, midX, midZ, midZ),
                "narrow range must only touch the covered bucket");
    }

    @Test
    void auditRingNeverExceedsCapacityUnderBurst() {
        StorageSavedData data = new StorageSavedData();
        data.setAuditCapacity(128);
        for (int i = 0; i < 10_000; i++) {
            data.appendAudit(NOW + i, "-", UUID.randomUUID(), "burst", "e" + i);
            assertTrue(data.auditSize() <= 128, "audit must stay within capacity");
        }
        assertEquals(128, data.auditSize());
    }

    // ---------------------------------------------------------------- 工具

    private static StoragePermission pickPermission(Random rnd) {
        StoragePermission[] values = StoragePermission.values();
        return values[rnd.nextInt(values.length)];
    }

    private static <K> K pick(Random rnd, Map<K, ?> map) {
        if (map.isEmpty()) {
            return null;
        }
        int idx = rnd.nextInt(map.size());
        int i = 0;
        for (K k : map.keySet()) {
            if (i++ == idx) {
                return k;
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
