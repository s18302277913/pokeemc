package com.pokeemc.storage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageAccessServiceTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SHARED = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final StoragePrincipal.Group BUILDER = new StoragePrincipal.Group("perm", "builder");

    private static StorageAccessService service() {
        return new StorageAccessService(
                playerId -> SHARED.equals(playerId) ? Optional.of(BUILDER) : Optional.empty(),
                playerId -> ADMIN.equals(playerId),
                (actor, owner, perm) -> { /* audit no-op */ });
    }

    private static StorageAccessService.AccessSnapshot snapshot(
            Map<StoragePrincipal, StorageGrant> grants) {
        return new StorageAccessService.AccessSnapshot(OWNER, grants);
    }

    @Test
    void ownerGetsFullPermissionSet() {
        StorageAccessService.AccessSnapshot snap = snapshot(Map.of());
        for (StoragePermission p : StoragePermission.values()) {
            assertTrue(service().allows(OWNER, p, snap), "owner must have " + p);
        }
    }

    @Test
    void adminBypassesAndIsAudited() {
        StorageAccessService svc = service();
        StorageAccessService.AccessSnapshot snap = snapshot(Map.of());
        for (StoragePermission p : StoragePermission.values()) {
            assertTrue(svc.allows(ADMIN, p, snap), "admin must bypass " + p);
        }
    }

    @Test
    void exactPlayerGrantAppliesOnlyToListedPermissions() {
        Map<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        grants.put(new StoragePrincipal.Player(SHARED),
                StorageGrant.allow(StoragePermission.DEPOSIT, StoragePermission.VIEW));
        StorageAccessService.AccessSnapshot snap = snapshot(grants);

        assertTrue(service().canView(SHARED, snap));
        assertTrue(service().canDeposit(SHARED, snap));
        assertFalse(service().canWithdraw(SHARED, snap));
        assertFalse(service().canSell(SHARED, snap));
        assertFalse(service().canBreak(SHARED, snap));
        assertFalse(service().canManage(SHARED, snap));
    }

    @Test
    void strangerWithoutGrantGetsNothing() {
        Map<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        grants.put(new StoragePrincipal.Player(SHARED),
                StorageGrant.allow(StoragePermission.DEPOSIT));
        StorageAccessService.AccessSnapshot snap = snapshot(grants);

        for (StoragePermission p : StoragePermission.values()) {
            assertFalse(service().allows(STRANGER, p, snap), "stranger must not have " + p);
        }
    }

    @Test
    void publicGrantAppliesToEveryone() {
        Map<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        grants.put(new StoragePrincipal.Public(),
                StorageGrant.allow(StoragePermission.VIEW));
        StorageAccessService.AccessSnapshot snap = snapshot(grants);

        assertTrue(service().canView(STRANGER, snap));
        assertFalse(service().canDeposit(STRANGER, snap));
    }

    @Test
    void resolvedGroupGrantAppliesOnlyWhenGroupResolves() {
        Map<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        grants.put(BUILDER, StorageGrant.allow(StoragePermission.DEPOSIT));
        StorageAccessService.AccessSnapshot snap = snapshot(grants);

        // SHARED 属于 BUILDER 组
        assertTrue(service().canDeposit(SHARED, snap));
        // STRANGER 未解析到组，即使有同名组授权也不能获得权限
        assertFalse(service().canDeposit(STRANGER, snap));
    }

    @Test
    void unresolvedGroupIsIgnoredRatherThanMisGranted() {
        Map<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        grants.put(new StoragePrincipal.Group("missing", "ghost"),
                StorageGrant.allow(StoragePermission.WITHDRAW, StoragePermission.SELL));
        StorageAccessService.AccessSnapshot snap = snapshot(grants);

        for (StoragePermission p : StoragePermission.values()) {
            assertFalse(service().allows(SHARED, p, snap),
                    "unresolved group must not grant " + p);
        }
    }

    @Test
    void explicitPlayerDenyOverridesPublicAllow() {
        Map<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        grants.put(new StoragePrincipal.Public(),
                StorageGrant.allow(StoragePermission.VIEW));
        grants.put(new StoragePrincipal.Player(SHARED),
                StorageGrant.deny(StoragePermission.VIEW));
        StorageAccessService.AccessSnapshot snap = snapshot(grants);

        assertFalse(service().canView(SHARED, snap));
        assertTrue(service().canView(STRANGER, snap));
    }

    @Test
    void playerDenyOverridesGroupAllow() {
        Map<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        grants.put(BUILDER, StorageGrant.allow(StoragePermission.DEPOSIT, StoragePermission.VIEW));
        grants.put(new StoragePrincipal.Player(SHARED),
                StorageGrant.deny(StoragePermission.DEPOSIT));
        StorageAccessService.AccessSnapshot snap = snapshot(grants);

        assertTrue(service().canView(SHARED, snap));
        assertFalse(service().canDeposit(SHARED, snap));
    }

    @Test
    void mergedPermissionsOnlyContainsUnion() {
        Map<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        grants.put(BUILDER, StorageGrant.allow(StoragePermission.DEPOSIT));
        grants.put(new StoragePrincipal.Public(), StorageGrant.allow(StoragePermission.VIEW));
        StorageAccessService.AccessSnapshot snap = snapshot(grants);

        StoragePermissionSet merged = service().mergedPermissions(SHARED, snap);
        assertTrue(merged.allows(StoragePermission.VIEW));
        assertTrue(merged.allows(StoragePermission.DEPOSIT));
        assertEquals(2, merged.values().size());
    }
}
