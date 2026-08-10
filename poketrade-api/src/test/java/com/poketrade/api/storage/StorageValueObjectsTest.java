package com.poketrade.api.storage;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageValueObjectsTest {

    private static final StorageId CHEST = new StorageId("minecraft:overworld", "vanilla_chest", "0;64;0");

    @Test
    void storageIdRoundTripsThroughString() {
        StorageId parsed = StorageId.parse(CHEST.asString());
        assertEquals(CHEST, parsed);
    }

    @Test
    void storageIdRejectsMalformedInput() {
        assertThrows(NullPointerException.class, () -> new StorageId(null, "vanilla_chest", "0;64;0"));
        assertThrows(NullPointerException.class, () -> new StorageId("minecraft:overworld", null, "0;64;0"));
        assertThrows(NullPointerException.class, () -> new StorageId("minecraft:overworld", "vanilla_chest", null));
        assertThrows(IllegalArgumentException.class, () -> new StorageId("overworld", "vanilla_chest", "0;64;0"));
        assertThrows(IllegalArgumentException.class, () -> new StorageId("minecraft:overworld", "Vanilla Chest", "0;64;0"));
        assertThrows(IllegalArgumentException.class, () -> new StorageId("minecraft:overworld", "vanilla_chest", "0|64|0"));
        assertThrows(IllegalArgumentException.class, () -> StorageId.parse("too|short"));
        assertThrows(IllegalArgumentException.class, () -> StorageId.parse("a|b|c|d"));
    }

    @Test
    void snapshotDefensivelyCopiesSlots() {
        Map<Integer, StorageItemSlot> slots = new java.util.LinkedHashMap<>();
        slots.put(0, new StorageItemSlot(0, "minecraft:diamond", 1, 1L));
        StorageSnapshot snapshot = new StorageSnapshot(CHEST, 5, slots);

        slots.put(1, new StorageItemSlot(1, "minecraft:emerald", 2, 2L));
        assertEquals(1, snapshot.slots().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.slots().put(2, null));
    }

    @Test
    void snapshotRejectsInvalidState() {
        assertThrows(NullPointerException.class, () -> new StorageSnapshot(null, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new StorageSnapshot(CHEST, -1, Map.of()));
        assertThrows(NullPointerException.class, () -> new StorageSnapshot(CHEST, 0, null));
        Map<Integer, StorageItemSlot> bad = new java.util.LinkedHashMap<>();
        bad.put(-1, new StorageItemSlot(0, "minecraft:diamond", 1, 1L));
        assertThrows(IllegalArgumentException.class, () -> new StorageSnapshot(CHEST, 0, bad));
    }

    @Test
    void descriptorDefensivelyCopiesCapabilitiesAndValidatesCounts() {
        StorageDescriptor descriptor = new StorageDescriptor(
                CHEST, "Chest", 3, true, UUID.randomUUID(), "Alice",
                EnumSet.of(StorageCapability.SNAPSHOT, StorageCapability.INSERT),
                27, 5, 9, true);

        assertTrue(descriptor.capabilities().contains(StorageCapability.SNAPSHOT));
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.capabilities().add(StorageCapability.EXTRACT));

        assertThrows(IllegalArgumentException.class, () -> new StorageDescriptor(
                CHEST, "Chest", -1, false, null, null, Set.of(), 27, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new StorageDescriptor(
                CHEST, "Chest", 0, false, null, null, Set.of(), 27, 28, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new StorageDescriptor(
                CHEST, "Chest", 0, false, null, null, Set.of(), 27, 0, -1, true));
        assertThrows(NullPointerException.class, () -> new StorageDescriptor(
                null, "Chest", 0, false, null, null, Set.of(), 27, 0, 0, true));
    }

    @Test
    void descriptorToleratesNullAndTrimsOwnerName() {
        // ownerName 允许 null（capabilities 用非空 EnumSet，空集合会触发 EnumSet.copyOf 的 IAE）
        StorageDescriptor noOwner = new StorageDescriptor(
                CHEST, "Chest", 0, false, null, null,
                EnumSet.of(StorageCapability.SNAPSHOT), 27, 0, 0, true);
        assertNull(noOwner.ownerName());

        // 非 null 时 trim
        StorageDescriptor trimmed = new StorageDescriptor(
                CHEST, "Chest", 0, false, UUID.randomUUID(), "  Alice  ",
                EnumSet.of(StorageCapability.SNAPSHOT), 27, 0, 0, true);
        assertEquals("Alice", trimmed.ownerName());
    }

    @Test
    void queryValidatesBoundsAndActor() {
        UUID actor = UUID.randomUUID();
        StorageQuery query = StorageQuery.nearby(actor, "minecraft:overworld", 0, 0, 32);
        assertEquals(StorageQuery.DEFAULT_MAX_RESULTS, query.maxResults());

        assertThrows(NullPointerException.class, () -> new StorageQuery(
                null, "minecraft:overworld", 0, 0, 32, null, StorageQuery.Sort.DISTANCE,
                StorageQuery.Filter.VIEWABLE, 50));
        assertThrows(IllegalArgumentException.class, () -> new StorageQuery(
                actor, "minecraft:overworld", 0, 0, -1, null, StorageQuery.Sort.DISTANCE,
                StorageQuery.Filter.VIEWABLE, 50));
        assertThrows(IllegalArgumentException.class, () -> new StorageQuery(
                actor, "minecraft:overworld", 0, 0, 32, null, StorageQuery.Sort.DISTANCE,
                StorageQuery.Filter.VIEWABLE, 0));
        assertThrows(IllegalArgumentException.class, () -> new StorageQuery(
                actor, "minecraft:overworld", 0, 0, 32, null, StorageQuery.Sort.DISTANCE,
                StorageQuery.Filter.VIEWABLE, 201));
    }

    @Test
    void transactionValidatesEndpointsCountAndRevisions() {
        UUID actor = UUID.randomUUID();
        Map<StorageId, Long> expected = new java.util.LinkedHashMap<>();
        expected.put(CHEST, 3L);

        StorageTransaction tx = new StorageTransaction(
                actor, "session-1", "op-1",
                StorageEndpoint.inventory(9),
                StorageEndpoint.storage(CHEST, 0),
                5, 0L, 0L, expected);
        assertEquals(5, tx.requestedCount());
        assertEquals(3L, tx.expectedRevisions().get(CHEST));

        assertThrows(IllegalArgumentException.class, () -> new StorageTransaction(
                actor, "s", "o", StorageEndpoint.inventory(0), StorageEndpoint.inventory(0),
                1, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new StorageTransaction(
                actor, "s", "o", StorageEndpoint.inventory(0), StorageEndpoint.storage(CHEST, 0),
                0, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new StorageTransaction(
                actor, "s", "o", StorageEndpoint.inventory(0), StorageEndpoint.storage(CHEST, 0),
                1, 0, 0, Map.of(CHEST, -1L)));
        assertThrows(IllegalArgumentException.class, () -> new StorageTransaction(
                actor, "s", "o", StorageEndpoint.storage(null, 0), StorageEndpoint.inventory(0),
                1, 0, 0, Map.of()));
    }

    @Test
    void resultDefinesStableFailureCodes() {
        assertEquals("success", StorageTransactionResult.SUCCESS);
        assertEquals("not_found", StorageTransactionResult.NOT_FOUND);
        assertEquals("chunk_unloaded", StorageTransactionResult.CHUNK_UNLOADED);
        assertEquals("adapter_unavailable", StorageTransactionResult.ADAPTER_UNAVAILABLE);
        assertEquals("not_claimed", StorageTransactionResult.NOT_CLAIMED);
        assertEquals("permission_denied", StorageTransactionResult.PERMISSION_DENIED);
        assertEquals("revision_conflict", StorageTransactionResult.REVISION_CONFLICT);

        StorageTransactionResult ok = StorageTransactionResult.success("moved", List.of(),
                Map.of(CHEST, 4L), Map.of(), UUID.randomUUID());
        assertTrue(ok.success());
        assertEquals("success", ok.code());

        StorageTransactionResult fail = StorageTransactionResult.failure(
                StorageTransactionResult.PERMISSION_DENIED, "denied");
        assertTrue(!fail.success());
        assertEquals("permission_denied", fail.code());
    }
}
