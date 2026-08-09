package com.pokeemc.storage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACL 保守合并（双箱迁移/合并语义，Task 6）：交集移除任何一方未授予的权限，
 * 并集保留任何一方授予的权限。
 */
class StoragePermissionMergeTest {

    @Test
    void intersectKeepsOnlySharedPermissions() {
        StoragePermissionSet a = StoragePermissionSet.of(
                StoragePermission.VIEW, StoragePermission.DEPOSIT, StoragePermission.WITHDRAW);
        StoragePermissionSet b = StoragePermissionSet.of(
                StoragePermission.DEPOSIT, StoragePermission.WITHDRAW, StoragePermission.MANAGE);

        StoragePermissionSet merged = a.intersect(b);

        assertTrue(merged.allows(StoragePermission.DEPOSIT));
        assertTrue(merged.allows(StoragePermission.WITHDRAW));
        assertFalse(merged.allows(StoragePermission.VIEW));
        assertFalse(merged.allows(StoragePermission.MANAGE));
    }

    @Test
    void intersectNeverAddsPermissions() {
        StoragePermissionSet a = StoragePermissionSet.of(StoragePermission.VIEW);
        StoragePermissionSet b = StoragePermissionSet.of(StoragePermission.MANAGE);

        assertTrue(a.intersect(b).isEmpty());
    }

    @Test
    void unionKeepsAnyGrantedPermission() {
        StoragePermissionSet a = StoragePermissionSet.of(
                StoragePermission.VIEW, StoragePermission.DEPOSIT);
        StoragePermissionSet b = StoragePermissionSet.of(
                StoragePermission.DEPOSIT, StoragePermission.SELL);

        StoragePermissionSet merged = a.union(b);

        assertTrue(merged.allows(StoragePermission.VIEW));
        assertTrue(merged.allows(StoragePermission.DEPOSIT));
        assertTrue(merged.allows(StoragePermission.SELL));
        assertFalse(merged.allows(StoragePermission.MANAGE));
    }

    @Test
    void mergeDoesNotMutateOriginals() {
        StoragePermissionSet a = StoragePermissionSet.of(
                StoragePermission.VIEW, StoragePermission.DEPOSIT);
        StoragePermissionSet b = StoragePermissionSet.of(StoragePermission.MANAGE);

        a.intersect(b);
        a.union(b);

        assertEquals(2, a.values().size());
        assertEquals(1, b.values().size());
    }

    @Test
    void intersectWithEmptyIsEmpty() {
        StoragePermissionSet a = StoragePermissionSet.of(
                StoragePermission.VIEW, StoragePermission.DEPOSIT);

        assertTrue(a.intersect(StoragePermissionSet.EMPTY).isEmpty());
    }

    @Test
    void unionWithEmptyKeepsEverything() {
        StoragePermissionSet a = StoragePermissionSet.of(
                StoragePermission.VIEW, StoragePermission.DEPOSIT);

        StoragePermissionSet merged = a.union(StoragePermissionSet.EMPTY);

        assertTrue(merged.allows(StoragePermission.VIEW));
        assertTrue(merged.allows(StoragePermission.DEPOSIT));
    }

    @Test
    void migratedRecordPreservesOwnerNameAndGrants() {
        UUID owner = UUID.randomUUID();
        StorageRecord record = StorageRecord.create(owner, "Alice", 1_000L)
                .withGrant(new StoragePrincipal.Player(UUID.randomUUID()),
                        StorageGrant.allow(StoragePermission.VIEW));
        // 迁移不改变 ACL；owner 名与授权主体保持一致
        assertEquals("Alice", record.ownerName());
        assertEquals(1, record.grants().size());
        assertEquals(owner, record.ownerId());
    }
}
