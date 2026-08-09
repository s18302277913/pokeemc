package com.pokeemc.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageSavedDataTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final StoragePrincipal.Player PLAYER =
            new StoragePrincipal.Player(OWNER);
    private static final long NOW = 1_700_000_000_000L;

    private static final StorageKey KEY =
            StorageKey.of("minecraft:overworld", "vanilla_chest", "12;64;-8");

    private StorageSavedData newData() {
        return new StorageSavedData();
    }

    private StorageRecord claimedRecord(StorageSavedData data) {
        StorageRecord record = StorageRecord.create(OWNER, "Alice", NOW);
        assertTrue(data.claim(KEY, record, 0, 0));
        return record;
    }

    // ---------------------------------------------------------------- 认领与查询

    @Test
    void claimAddsRecordAndIndex() {
        StorageSavedData data = newData();
        StorageRecord record = claimedRecord(data);
        assertEquals(record, data.getRecord(KEY).orElseThrow());
        assertEquals(1, data.recordsView().size());
        assertTrue(data.keysInChunk("minecraft:overworld", 0, 0).contains(KEY));
        assertTrue(data.keysInChunk("minecraft:overworld", 1, 1).isEmpty());
        assertTrue(data.isDirty());
    }

    @Test
    void duplicateClaimRejected() {
        StorageSavedData data = newData();
        claimedRecord(data);
        assertFalse(data.claim(KEY,
                StorageRecord.create(OWNER, "Alice", NOW), 0, 0));
        assertEquals(1, data.recordsView().size());
    }

    // ---------------------------------------------------------------- revision 门控

    @Test
    void updateRecordRequiresExpectedRevision() {
        StorageSavedData data = newData();
        StorageRecord original = claimedRecord(data);

        assertFalse(data.renameStorage(KEY, 99, "Stale Rename"));
        assertEquals(original, data.getRecord(KEY).orElseThrow());

        assertTrue(data.renameStorage(KEY, 1, "New Name"));
        StorageRecord renamed = data.getRecord(KEY).orElseThrow();
        assertEquals("New Name", renamed.displayName());
        assertEquals(2, renamed.revision());
    }

    @Test
    void updateRecordOnMissingStorageRejected() {
        StorageSavedData data = newData();
        assertFalse(data.renameStorage(
                StorageKey.of("minecraft:overworld", "vanilla_chest", "99;99;99"),
                1, "X"));
    }

    @Test
    void wrappersApplyGrantsAndSettings() {
        StorageSavedData data = newData();
        claimedRecord(data);

        assertTrue(data.applyGrant(KEY, 1, PLAYER,
                StorageGrant.allow(StoragePermission.VIEW, StoragePermission.DEPOSIT)));
        assertTrue(data.getRecord(KEY).orElseThrow()
                .grants().get(PLAYER).allows(StoragePermission.DEPOSIT));

        assertTrue(data.removeGrant(KEY, 2, PLAYER));
        assertTrue(data.getRecord(KEY).orElseThrow().grants().isEmpty());

        assertTrue(data.setAutomationInsert(KEY, 3, true));
        assertTrue(data.setAutomationExtract(KEY, 4, true));
        assertTrue(data.setBrowserListed(KEY, 5, false));
        StorageRecord record = data.getRecord(KEY).orElseThrow();
        assertTrue(record.automationInsertEnabled());
        assertTrue(record.automationExtractEnabled());
        assertFalse(record.listedInBrowser());
        assertEquals(6, record.revision());
    }

    // ---------------------------------------------------------------- 模板变更

    private StorageTemplate createShared(StorageSavedData data) {
        Map<StoragePrincipal, StorageGrant> grants = Map.of(
                new StoragePrincipal.Public(),
                StorageGrant.allow(StoragePermission.VIEW, StoragePermission.DEPOSIT));
        StorageTemplate template = StorageTemplate.create(
                "server:shared", StorageTemplate.Scope.SERVER, null, "shared",
                grants, NOW);
        data.createTemplate(template);
        return template;
    }

    @Test
    void createTemplateRejectsDuplicateId() {
        StorageSavedData data = newData();
        createShared(data);
        assertThrows(IllegalArgumentException.class, () -> createShared(data));
    }

    @Test
    void bindTemplateRequiresExistingTemplate() {
        StorageSavedData data = newData();
        claimedRecord(data);
        assertFalse(data.bindTemplate(KEY, 1, "server:missing",
                StorageRecord.TemplateMode.FOLLOW));
    }

    @Test
    void updateTemplateRequiresRevisionMatch() {
        StorageSavedData data = newData();
        StorageTemplate template = createShared(data);
        assertFalse(data.updateTemplate(template.id(), 99, Map.of()));
        assertTrue(data.updateTemplate(template.id(), 1,
                Map.of(PLAYER, StorageGrant.allow(StoragePermission.VIEW))));
        assertEquals(2, data.getTemplate(template.id()).orElseThrow().revision());
    }

    @Test
    void deleteTemplateFreezesFollowersToCopy() {
        StorageSavedData data = newData();
        StorageTemplate template = createShared(data);

        // 本地显式 deny SELL，模板允许 DEPOSIT + VIEW
        StorageRecord follower = StorageRecord.create(OWNER, "Alice", NOW)
                .withGrant(PLAYER, StorageGrant.deny(StoragePermission.SELL))
                .withTemplate(template.id(), StorageRecord.TemplateMode.FOLLOW);
        StorageKey followerKey =
                StorageKey.of("minecraft:overworld", "vanilla_chest", "20;64;0");
        data.claim(followerKey, follower, 1, 0);

        int frozen = data.deleteTemplate(template.id());
        assertEquals(1, frozen);
        assertTrue(data.getTemplate(template.id()).isEmpty());

        StorageRecord record = data.getRecord(followerKey).orElseThrow();
        assertNull(record.templateBinding());
        assertEquals(StorageRecord.TemplateMode.COPY, record.templateMode());
        // 模板权限已并入：VIEW/DEPOSIT 可用，本地 deny 覆盖模板 allow
        assertTrue(record.grants().get(new StoragePrincipal.Public())
                .allows(StoragePermission.VIEW));
        assertFalse(record.grants().get(PLAYER).allows(StoragePermission.SELL));
        assertEquals(2, record.revision());
    }

    @Test
    void deleteMissingTemplateReturnsZero() {
        StorageSavedData data = newData();
        assertEquals(0, data.deleteTemplate("server:missing"));
    }

    @Test
    void repairTemplateReferencesFreezesMissingFollow() {
        StorageSavedData data = newData();
        StorageRecord follower = StorageRecord.create(OWNER, "Alice", NOW)
                .withTemplate("server:gone", StorageRecord.TemplateMode.FOLLOW);
        StorageKey followerKey =
                StorageKey.of("minecraft:overworld", "vanilla_chest", "20;64;0");
        data.claim(followerKey, follower, 1, 0);

        assertEquals(1, data.repairTemplateReferences());
        StorageRecord record = data.getRecord(followerKey).orElseThrow();
        assertNull(record.templateBinding());
        assertEquals(StorageRecord.TemplateMode.COPY, record.templateMode());
        assertEquals(2, record.revision());
        // 已修复，再次调用无变化
        assertEquals(0, data.repairTemplateReferences());
    }

    // ---------------------------------------------------------------- 审计

    @Test
    void auditRingCapsAtCapacityAndAssignsIds() {
        StorageSavedData data = newData();
        data.setAuditCapacity(2);
        data.appendAudit(NOW, KEY.asString(), OWNER, "claim", "a");
        data.appendAudit(NOW, KEY.asString(), OTHER, "grant", "b");
        data.appendAudit(NOW, KEY.asString(), OWNER, "sell", "c");

        List<StorageAuditEntry> entries = data.auditView();
        assertEquals(2, entries.size());
        assertEquals(2L, entries.get(0).id());
        assertEquals("grant", entries.get(0).action());
        assertEquals(3L, entries.get(1).id());
        assertEquals("sell", entries.get(1).action());
        assertTrue(data.isDirty());
    }

    @Test
    void auditDetailTruncatedToMaxLength() {
        StorageSavedData data = newData();
        StorageAuditEntry entry = data.appendAudit(
                NOW, KEY.asString(), OWNER, "sell", "x".repeat(400));
        assertEquals(StorageAuditEntry.MAX_DETAIL_LENGTH, entry.detail().length());
        assertEquals(256, data.auditView().get(0).detail().length());
    }

    @Test
    void auditCapacityMustBePositive() {
        StorageSavedData data = newData();
        assertThrows(IllegalArgumentException.class, () -> data.setAuditCapacity(0));
    }

    @Test
    void defaultAuditCapacity() {
        StorageSavedData data = newData();
        assertEquals(StorageSavedData.DEFAULT_AUDIT_CAPACITY, data.auditCapacity());
        for (int i = 0; i < StorageSavedData.DEFAULT_AUDIT_CAPACITY + 10; i++) {
            data.appendAudit(NOW, KEY.asString(), OWNER, "event", "e" + i);
        }
        assertEquals(StorageSavedData.DEFAULT_AUDIT_CAPACITY, data.auditSize());
        assertEquals(StorageSavedData.DEFAULT_AUDIT_CAPACITY + 10L,
                data.auditView().get(data.auditSize() - 1).id());
    }
}
