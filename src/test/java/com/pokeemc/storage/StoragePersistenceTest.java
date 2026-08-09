package com.pokeemc.storage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoragePersistenceTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final StoragePrincipal.Player PLAYER =
            new StoragePrincipal.Player(OWNER);
    private static final StoragePrincipal.Group BUILDERS =
            new StoragePrincipal.Group("factions", "builders");
    private static final long NOW = 1_700_000_000_000L;

    private static final StorageKey KEY =
            StorageKey.of("minecraft:overworld", "vanilla_chest", "12;64;-8");
    private static final StorageKey KEY2 =
            StorageKey.of("minecraft:overworld", "vanilla_chest", "20;64;0");

    /** 构建覆盖仓储/模板/FOLLOW 绑定/审计/区块索引的完整数据。 */
    private static StorageSavedData fullData() {
        StorageSavedData data = new StorageSavedData();
        StorageRecord record = StorageRecord.create(OWNER, "Alice", NOW)
                .withGrant(PLAYER,
                        StorageGrant.allow(StoragePermission.VIEW, StoragePermission.DEPOSIT))
                .withGrant(new StoragePrincipal.Public(),
                        StorageGrant.allow(StoragePermission.VIEW))
                .withGrant(BUILDERS, StorageGrant.deny(StoragePermission.BREAK))
                .withAutomationInsert(true);
        data.claim(KEY, record, 0, 0);

        StorageTemplate template = StorageTemplate.create(
                "server:shared", StorageTemplate.Scope.SERVER, null, "shared",
                java.util.Map.of(new StoragePrincipal.Public(),
                        StorageGrant.allow(StoragePermission.VIEW)), NOW);
        data.createTemplate(template);

        StorageRecord follower = StorageRecord.create(OWNER, "Alice", NOW)
                .withTemplate(template.id(), StorageRecord.TemplateMode.FOLLOW);
        data.claim(KEY2, follower, 1, 0);

        data.appendAudit(NOW, KEY.asString(), OWNER, "claim", "Alice claimed the chest");
        data.appendAudit(NOW, KEY.asString(), OWNER, "grant", "builders denied break");
        return data;
    }

    @Test
    void encodeDecodeRoundTripPreservesEverything() {
        StorageSavedData data = fullData();
        data.renameStorage(KEY, 1, "Treasure Vault");

        StorageSavedData decoded =
                StorageSavedData.decode(StorageSavedData.encode(data),
                        StorageSavedData.StorageLoadContext.ACCEPT_ALL);

        assertEquals(data.recordsView(), decoded.recordsView());
        assertEquals(data.templatesView(), decoded.templatesView());
        assertEquals(data.auditView(), decoded.auditView());
        assertEquals(data.keysInChunk("minecraft:overworld", 0, 0),
                decoded.keysInChunk("minecraft:overworld", 0, 0));
        assertEquals(data.keysInChunk("minecraft:overworld", 1, 0),
                decoded.keysInChunk("minecraft:overworld", 1, 0));
        // FOLLOW 绑定经持久化保留
        StorageRecord follower = decoded.getRecord(KEY2).orElseThrow();
        assertEquals("server:shared", follower.templateBinding());
        assertEquals(StorageRecord.TemplateMode.FOLLOW, follower.templateMode());
        // 审计 ID 继续单调递增
        assertEquals(3L, decoded.appendAudit(NOW, KEY.asString(), OWNER, "x", "y").id());
    }

    @Test
    void corruptStorageEntryIsSkippedIndividually() {
        StorageSavedData data = new StorageSavedData();
        data.claim(KEY, StorageRecord.create(OWNER, "Alice", NOW), 0, 0);
        data.claim(KEY2, StorageRecord.create(OWNER, "Alice", NOW), 1, 0);

        CompoundTag tag = StorageSavedData.encode(data);
        ListTag storages = tag.getList("storages", Tag.TAG_COMPOUND);
        storages.getCompound(1).remove("ownerId"); // 损坏第二条

        StorageSavedData decoded =
                StorageSavedData.decode(tag, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        assertEquals(1, decoded.recordsView().size());
        assertTrue(decoded.getRecord(KEY).isPresent());
        assertTrue(decoded.getRecord(KEY2).isEmpty());
    }

    @Test
    void invalidDimensionIsSkippedWithoutOverworldFallback() {
        StorageSavedData data = new StorageSavedData();
        data.claim(KEY, StorageRecord.create(OWNER, "Alice", NOW), 0, 0);

        CompoundTag tag = StorageSavedData.encode(data);
        tag.getList("storages", Tag.TAG_COMPOUND).getCompound(0)
                .putString("key", "minecraft:not_a_real_dim|vanilla_chest|0;0;0");

        StorageSavedData decoded = StorageSavedData.decode(tag,
                new StorageSavedData.StorageLoadContext(
                        dim -> dim.equals("minecraft:overworld"), id -> true));
        assertTrue(decoded.recordsView().isEmpty());
        // 不回退主世界：不会出现以 overworld 为维度的幽灵条目
        assertTrue(decoded.getRecord(
                StorageKey.of("minecraft:overworld", "vanilla_chest", "0;0;0")).isEmpty());
        assertTrue(decoded.keysInChunk("minecraft:overworld", 0, 0).isEmpty());
    }

    @Test
    void unknownAdapterTypeIsSkipped() {
        StorageSavedData data = new StorageSavedData();
        data.claim(KEY, StorageRecord.create(OWNER, "Alice", NOW), 0, 0);

        CompoundTag tag = StorageSavedData.encode(data);
        tag.getList("storages", Tag.TAG_COMPOUND).getCompound(0)
                .putString("key", "minecraft:overworld|unknown_adapter|0;0;0");

        StorageSavedData decoded = StorageSavedData.decode(tag,
                new StorageSavedData.StorageLoadContext(
                        dim -> true, adapter -> adapter.equals("vanilla_chest")));
        assertTrue(decoded.recordsView().isEmpty());
    }

    @Test
    void versionHigherThanCurrentReturnsEmpty() {
        StorageSavedData data = fullData();
        CompoundTag tag = StorageSavedData.encode(data);
        tag.putInt("version", StorageSavedData.DATA_VERSION + 1);
        StorageSavedData decoded =
                StorageSavedData.decode(tag, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        assertTrue(decoded.recordsView().isEmpty());
        assertTrue(decoded.templatesView().isEmpty());
        assertEquals(0, decoded.auditSize());
    }

    @Test
    void versionZeroReturnsEmpty() {
        StorageSavedData data = fullData();
        CompoundTag tag = StorageSavedData.encode(data);
        tag.putInt("version", 0);
        StorageSavedData decoded =
                StorageSavedData.decode(tag, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        assertTrue(decoded.recordsView().isEmpty());
    }

    @Test
    void missingVersionIsTreatedAsCurrent() {
        StorageSavedData data = fullData();
        CompoundTag tag = StorageSavedData.encode(data);
        tag.remove("version");
        StorageSavedData decoded =
                StorageSavedData.decode(tag, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        assertEquals(2, decoded.recordsView().size());
    }

    @Test
    void chunkIndexDropsKeysWithoutMatchingStorage() {
        StorageSavedData data = new StorageSavedData();
        data.claim(KEY, StorageRecord.create(OWNER, "Alice", NOW), 0, 0);

        CompoundTag tag = StorageSavedData.encode(data);
        tag.put("storages", new ListTag()); // 清空仓储，仅保留索引

        StorageSavedData decoded =
                StorageSavedData.decode(tag, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        assertTrue(decoded.recordsView().isEmpty());
        assertTrue(decoded.keysInChunk("minecraft:overworld", 0, 0).isEmpty());
    }

    @Test
    void followBindingWithMissingTemplateIsFrozenToCopyOnLoad() {
        StorageSavedData data = new StorageSavedData();
        StorageRecord follower = StorageRecord.create(OWNER, "Alice", NOW)
                .withTemplate("server:gone", StorageRecord.TemplateMode.FOLLOW);
        data.claim(KEY, follower, 0, 0);

        CompoundTag tag = StorageSavedData.encode(data);

        StorageSavedData decoded =
                StorageSavedData.decode(tag, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        StorageRecord record = decoded.getRecord(KEY).orElseThrow();
        assertNull(record.templateBinding());
        assertEquals(StorageRecord.TemplateMode.COPY, record.templateMode());
        assertFalse(decoded.getTemplate("server:gone").isPresent());
    }

    @Test
    void unknownPermissionNameIsSkippedOnLoad() {
        StorageSavedData data = new StorageSavedData();
        data.claim(KEY, StorageRecord.create(OWNER, "Alice", NOW)
                .withGrant(PLAYER, StorageGrant.allow(StoragePermission.VIEW)), 0, 0);

        CompoundTag tag = StorageSavedData.encode(data);
        CompoundTag grant = tag.getList("storages", Tag.TAG_COMPOUND)
                .getCompound(0).getList("grants", Tag.TAG_COMPOUND).getCompound(0);
        grant.getList("allow", Tag.TAG_STRING).add(StringTag.valueOf("NOPE"));

        StorageSavedData decoded =
                StorageSavedData.decode(tag, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        StorageGrant restored = decoded.getRecord(KEY).orElseThrow()
                .grants().get(PLAYER);
        assertTrue(restored.allows(StoragePermission.VIEW));
    }

    @Test
    void auditRingIsCappedOnLoad() {
        StorageSavedData data = new StorageSavedData();
        for (int i = 0; i < StorageSavedData.DEFAULT_AUDIT_CAPACITY + 10; i++) {
            data.appendAudit(NOW, KEY.asString(), OWNER, "event", "e" + i);
        }
        StorageSavedData decoded =
                StorageSavedData.decode(StorageSavedData.encode(data),
                        StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        assertEquals(StorageSavedData.DEFAULT_AUDIT_CAPACITY, decoded.auditSize());
    }

    @Test
    void nullTagDecodesToEmpty() {
        StorageSavedData decoded =
                StorageSavedData.decode(null, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        assertTrue(decoded.recordsView().isEmpty());
        assertEquals(0, decoded.auditSize());
    }
}
