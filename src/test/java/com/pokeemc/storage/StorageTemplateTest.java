package com.pokeemc.storage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTemplateTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final StoragePrincipal.Player PLAYER =
            new StoragePrincipal.Player(OWNER);
    private static final StoragePrincipal.Group BUILDERS =
            new StoragePrincipal.Group("factions", "builders");
    private static final long NOW = 1_700_000_000_000L;

    private static Map<StoragePrincipal, StorageGrant> grants(
            StoragePrincipal principal, StorageGrant grant) {
        Map<StoragePrincipal, StorageGrant> map = new LinkedHashMap<>();
        map.put(principal, grant);
        return map;
    }

    @Test
    void createAppliesDefaults() {
        StorageTemplate template = StorageTemplate.create(
                "server:shared", StorageTemplate.Scope.SERVER, null, "shared",
                grants(PLAYER, StorageGrant.allow(StoragePermission.VIEW)), NOW);
        assertEquals("server:shared", template.id());
        assertEquals(StorageTemplate.Scope.SERVER, template.scope());
        assertNull(template.ownerId());
        assertEquals(1, template.revision());
        assertEquals(NOW, template.createdAtEpochMillis());
    }

    @Test
    void playerTemplateRequiresOwner() {
        assertThrows(NullPointerException.class, () -> StorageTemplate.create(
                "me:home", StorageTemplate.Scope.PLAYER, null, "home",
                Map.of(), NOW));
        StorageTemplate template = StorageTemplate.create(
                "me:home", StorageTemplate.Scope.PLAYER, OWNER, "home",
                Map.of(), NOW);
        assertEquals(OWNER, template.ownerId());
    }

    @Test
    void invalidIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> StorageTemplate.create(
                "server:shared with space", StorageTemplate.Scope.SERVER, null, "shared",
                Map.of(), NOW));
        assertThrows(IllegalArgumentException.class, () -> StorageTemplate.create(
                "", StorageTemplate.Scope.SERVER, null, "shared",
                Map.of(), NOW));
    }

    @Test
    void nameValidation() {
        assertThrows(IllegalArgumentException.class, () -> StorageTemplate.create(
                "server:shared", StorageTemplate.Scope.SERVER, null, "",
                Map.of(), NOW));
        assertThrows(IllegalArgumentException.class, () -> StorageTemplate.create(
                "server:shared", StorageTemplate.Scope.SERVER, null, "x".repeat(33),
                Map.of(), NOW));
    }

    @Test
    void withGrantsKeepsRevisionAndTouchBumps() {
        StorageTemplate template = StorageTemplate.create(
                "server:shared", StorageTemplate.Scope.SERVER, null, "shared",
                grants(PLAYER, StorageGrant.allow(StoragePermission.VIEW)), NOW);
        StorageTemplate updated = template.withGrants(Map.of());
        assertEquals(1, updated.revision());
        StorageTemplate touched = updated.touch(NOW + 500);
        assertEquals(2, touched.revision());
        assertEquals(NOW + 500, touched.updatedAtEpochMillis());
    }

    @Test
    void mergeGrantsUnionsAllowsAndDenies() {
        Map<StoragePrincipal, StorageGrant> templateGrants = grants(PLAYER,
                StorageGrant.allow(StoragePermission.VIEW, StoragePermission.DEPOSIT));
        Map<StoragePrincipal, StorageGrant> localGrants = grants(PLAYER,
                StorageGrant.allow(StoragePermission.VIEW, StoragePermission.SELL));
        Map<StoragePrincipal, StorageGrant> merged =
                StorageTemplate.mergeGrants(templateGrants, localGrants);
        StorageGrant result = merged.get(PLAYER);
        assertTrue(result.allows(StoragePermission.VIEW));
        assertTrue(result.allows(StoragePermission.DEPOSIT));
        assertTrue(result.allows(StoragePermission.SELL));
    }

    @Test
    void localDenyOverridesTemplateAllow() {
        Map<StoragePrincipal, StorageGrant> templateGrants = grants(PLAYER,
                StorageGrant.allow(StoragePermission.WITHDRAW, StoragePermission.SELL));
        Map<StoragePrincipal, StorageGrant> localGrants = grants(PLAYER,
                StorageGrant.deny(StoragePermission.SELL));
        StorageGrant result = StorageTemplate.mergeGrants(templateGrants, localGrants)
                .get(PLAYER);
        assertTrue(result.allows(StoragePermission.WITHDRAW));
        assertFalse(result.allows(StoragePermission.SELL));
    }

    @Test
    void templateDenyIsPreserved() {
        Map<StoragePrincipal, StorageGrant> templateGrants = grants(PLAYER,
                StorageGrant.deny(StoragePermission.BREAK));
        Map<StoragePrincipal, StorageGrant> localGrants = grants(PLAYER,
                StorageGrant.allow(StoragePermission.VIEW));
        StorageGrant result = StorageTemplate.mergeGrants(templateGrants, localGrants)
                .get(PLAYER);
        assertFalse(result.allows(StoragePermission.BREAK));
        assertTrue(result.allows(StoragePermission.VIEW));
    }

    @Test
    void principalsFromEitherSideAreKept() {
        Map<StoragePrincipal, StorageGrant> templateGrants = grants(PLAYER,
                StorageGrant.allow(StoragePermission.VIEW));
        Map<StoragePrincipal, StorageGrant> localGrants = grants(BUILDERS,
                StorageGrant.allow(StoragePermission.DEPOSIT));
        Map<StoragePrincipal, StorageGrant> merged =
                StorageTemplate.mergeGrants(templateGrants, localGrants);
        assertEquals(2, merged.size());
        assertTrue(merged.containsKey(PLAYER));
        assertTrue(merged.containsKey(BUILDERS));
    }
}
