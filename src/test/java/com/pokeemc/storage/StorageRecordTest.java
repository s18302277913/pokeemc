package com.pokeemc.storage;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageRecordTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final StoragePrincipal.Player PLAYER =
            new StoragePrincipal.Player(OWNER);
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void createAppliesDefaults() {
        StorageRecord record = StorageRecord.create(OWNER, "Alice", NOW);
        assertEquals(OWNER, record.ownerId());
        assertEquals("Alice", record.displayName());
        assertTrue(record.grants().isEmpty());
        assertNull(record.templateBinding());
        assertEquals(StorageRecord.TemplateMode.COPY, record.templateMode());
        assertFalse(record.automationInsertEnabled());
        assertFalse(record.automationExtractEnabled());
        assertTrue(record.listedInBrowser());
        assertEquals(NOW, record.createdAtEpochMillis());
        assertEquals(NOW, record.updatedAtEpochMillis());
        assertEquals(1, record.revision());
    }

    @Test
    void renamedUpdatesOnlyDisplayName() {
        StorageRecord record = StorageRecord.create(OWNER, "Alice", NOW).renamed("Treasure Vault");
        assertEquals("Treasure Vault", record.displayName());
        assertEquals("Alice", record.ownerName());
        assertEquals(1, record.revision());
    }

    @Test
    void withOwnerNameUpdatesOnlyOwnerName() {
        StorageRecord record = StorageRecord.create(OWNER, "末影箱", NOW)
                .withOwnerName("Alice");
        assertEquals("Alice", record.ownerName());
        assertEquals("末影箱", record.displayName());
        assertEquals(OWNER, record.ownerId());
        assertEquals(1, record.revision());
    }

    @Test
    void withGrantAddsAndPreservesOthers() {
        StorageRecord record = StorageRecord.create(OWNER, "Alice", NOW)
                .withGrant(PLAYER,
                        StorageGrant.allow(StoragePermission.VIEW, StoragePermission.DEPOSIT))
                .withGrant(new StoragePrincipal.Public(),
                        StorageGrant.allow(StoragePermission.VIEW));
        assertEquals(2, record.grants().size());
        assertTrue(record.grants().get(PLAYER).allows(StoragePermission.DEPOSIT));
        assertTrue(record.grants().get(new StoragePrincipal.Public()).allows(StoragePermission.VIEW));
        assertEquals(1, record.revision());
    }

    @Test
    void withoutGrantRemovesOnlyThatPrincipal() {
        StorageRecord record = StorageRecord.create(OWNER, "Alice", NOW)
                .withGrant(PLAYER, StorageGrant.allow(StoragePermission.VIEW))
                .withGrant(new StoragePrincipal.Public(), StorageGrant.allow(StoragePermission.VIEW))
                .withoutGrant(PLAYER);
        assertEquals(1, record.grants().size());
        assertFalse(record.grants().containsKey(PLAYER));
    }

    @Test
    void dataChangesDoNotBumpRevisionButTouchDoes() {
        StorageRecord record = StorageRecord.create(OWNER, "Alice", NOW)
                .withGrant(PLAYER, StorageGrant.allow(StoragePermission.VIEW))
                .withAutomationInsert(true)
                .withBrowserListed(false);
        assertEquals(1, record.revision());
        StorageRecord touched = record.touch(NOW + 1000);
        assertEquals(2, touched.revision());
        assertEquals(NOW + 1000, touched.updatedAtEpochMillis());
        assertEquals(NOW, touched.createdAtEpochMillis());
    }

    @Test
    void templateBindingsAreSupported() {
        StorageRecord record = StorageRecord.create(OWNER, "Alice", NOW)
                .withTemplate("server:shared", StorageRecord.TemplateMode.FOLLOW);
        assertEquals("server:shared", record.templateBinding());
        assertEquals(StorageRecord.TemplateMode.FOLLOW, record.templateMode());
        StorageRecord unbound = record.withoutTemplate();
        assertNull(unbound.templateBinding());
        assertEquals(StorageRecord.TemplateMode.COPY, unbound.templateMode());
    }

    @Test
    void frozenCopyClearsBindingAndKeepsGrants() {
        Map<StoragePrincipal, StorageGrant> frozen = Map.of(
                PLAYER, StorageGrant.allow(StoragePermission.VIEW));
        StorageRecord record = StorageRecord.create(OWNER, "Alice", NOW)
                .withTemplate("server:shared", StorageRecord.TemplateMode.FOLLOW)
                .withFrozenCopy(frozen);
        assertNull(record.templateBinding());
        assertEquals(StorageRecord.TemplateMode.COPY, record.templateMode());
        assertEquals(frozen, record.grants());
    }

    @Test
    void displayNameValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                StorageRecord.create(OWNER, "Alice", NOW)
                        .renamed("x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () ->
                StorageRecord.create(OWNER, "Alice", NOW)
                        .renamed("bad\u0007name"));
    }

    @Test
    void followWithoutBindingRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StorageRecord(
                OWNER, "Alice", "Alice", Map.of(),
                null, StorageRecord.TemplateMode.FOLLOW,
                false, false, true, NOW, NOW, 1));
    }

    @Test
    void emptyTemplateBindingRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StorageRecord(
                OWNER, "Alice", "Alice", Map.of(),
                "", StorageRecord.TemplateMode.COPY,
                false, false, true, NOW, NOW, 1));
    }

    @Test
    void grantsAreImmutablyCopied() {
        Map<StoragePrincipal, StorageGrant> mutable = new java.util.LinkedHashMap<>();
        mutable.put(PLAYER, StorageGrant.allow(StoragePermission.VIEW));
        StorageRecord record = new StorageRecord(
                OWNER, "Alice", "Alice", mutable,
                null, StorageRecord.TemplateMode.COPY,
                false, false, true, NOW, NOW, 1);
        mutable.put(new StoragePrincipal.Public(), StorageGrant.allow(StoragePermission.DEPOSIT));
        assertEquals(1, record.grants().size());
        assertThrows(UnsupportedOperationException.class,
                () -> record.grants().put(PLAYER, StorageGrant.NONE));
    }

    @Test
    void revisionMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new StorageRecord(
                OWNER, "Alice", "Alice", Map.of(),
                null, StorageRecord.TemplateMode.COPY,
                false, false, true, NOW, NOW, 0));
    }
}
