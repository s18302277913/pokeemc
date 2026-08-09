package com.pokeemc.storage.adapter;

import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageSnapshot;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageHandleImplTest {

    private static final StorageId SID =
            new StorageId("minecraft:overworld", "vanilla_chest", "0;64;0");

    private static final class FakeSlots implements SlotStore {
        final String[] ids;
        final int[] counts;
        final int max;

        FakeSlots(int size, int max) {
            this.ids = new String[size];
            this.counts = new int[size];
            this.max = max;
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
            return max;
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

    private static FakeSlots newSlots(int size) {
        return new FakeSlots(size, 64);
    }

    @Test
    void insertIntoEmptySlotCommits() {
        FakeSlots slots = newSlots(4);
        StorageHandleImpl h = StorageHandleImpl.of(SID, slots);
        assertTrue(h.simulateInsert(0, "minecraft:stone", 5));
        h.commitInsert(0, "minecraft:stone", 5);
        assertEquals("minecraft:stone", slots.itemId(0));
        assertEquals(5, slots.count(0));
    }

    @Test
    void insertMergesIntoExistingStack() {
        FakeSlots slots = newSlots(4);
        slots.set(1, "minecraft:stone", 60);
        StorageHandleImpl h = StorageHandleImpl.of(SID, slots);
        assertTrue(h.simulateInsert(1, "minecraft:stone", 4));
        h.commitInsert(1, "minecraft:stone", 4);
        assertEquals(64, slots.count(1));
        assertFalse(h.simulateInsert(1, "minecraft:stone", 1), "stack must not exceed max");
    }

    @Test
    void insertRejectsDifferentItemIntoOccupiedSlot() {
        FakeSlots slots = newSlots(4);
        slots.set(0, "minecraft:stone", 1);
        StorageHandleImpl h = StorageHandleImpl.of(SID, slots);
        assertFalse(h.simulateInsert(0, "minecraft:dirt", 1));
    }

    @Test
    void insertRejectsInvalidArguments() {
        FakeSlots slots = newSlots(4);
        StorageHandleImpl h = StorageHandleImpl.of(SID, slots);
        assertFalse(h.simulateInsert(0, null, 1));
        assertFalse(h.simulateInsert(0, "minecraft:stone", 0));
        assertFalse(h.simulateInsert(-1, "minecraft:stone", 1));
        assertFalse(h.simulateInsert(4, "minecraft:stone", 1));
    }

    @Test
    void extractCommitsAndEmptiesSlot() {
        FakeSlots slots = newSlots(4);
        slots.set(2, "minecraft:stone", 10);
        StorageHandleImpl h = StorageHandleImpl.of(SID, slots);
        assertTrue(h.simulateExtract(2, "minecraft:stone", 10));
        h.commitExtract(2, "minecraft:stone", 10);
        assertNull(slots.itemId(2));
        assertEquals(0, slots.count(2));
    }

    @Test
    void extractRejectsPartialOrMismatchedItem() {
        FakeSlots slots = newSlots(4);
        slots.set(0, "minecraft:stone", 3);
        StorageHandleImpl h = StorageHandleImpl.of(SID, slots);
        assertFalse(h.simulateExtract(0, "minecraft:stone", 4), "cannot extract more than present");
        assertFalse(h.simulateExtract(0, "minecraft:dirt", 1));
        assertFalse(h.simulateExtract(1, "minecraft:stone", 1), "empty slot");
    }

    @Test
    void filteredSlotsRespectInsertAndExtractPredicates() {
        FakeSlots slots = newSlots(2);
        IntPredicate insertable = s -> s == 0;
        IntPredicate extractable = s -> s == 1;
        StorageHandleImpl h = new StorageHandleImpl(
                SID, slots, insertable, extractable, () -> 0L);
        assertTrue(h.simulateInsert(0, "minecraft:stone", 1));
        assertFalse(h.simulateInsert(1, "minecraft:stone", 1));
        slots.set(1, "minecraft:stone", 1);
        assertTrue(h.simulateExtract(1, "minecraft:stone", 1));
        assertFalse(h.simulateExtract(0, "minecraft:stone", 1));
    }

    @Test
    void commitWithoutSimulationThrows() {
        FakeSlots slots = newSlots(4);
        StorageHandleImpl h = StorageHandleImpl.of(SID, slots);
        assertThrows(IllegalArgumentException.class,
                () -> h.commitInsert(0, "minecraft:stone", 1000));
        assertThrows(IllegalArgumentException.class,
                () -> h.commitExtract(0, "minecraft:stone", 1));
    }

    @Test
    void snapshotReportsRevisionAndNonEmptySlots() {
        FakeSlots slots = newSlots(4);
        slots.set(0, "minecraft:stone", 5);
        slots.set(3, "minecraft:dirt", 2);
        StorageHandleImpl h = new StorageHandleImpl(SID, slots, s -> true, s -> true, () -> 7L);
        StorageSnapshot snap = h.snapshot();
        assertEquals(SID, snap.storageId());
        assertEquals(7L, snap.revision());
        assertEquals(2, snap.slots().size());
        assertEquals(5, snap.slots().get(0).count());
        assertEquals("minecraft:dirt", snap.slots().get(3).itemId());
        assertEquals(2, snap.slots().get(3).count());
    }

    @Test
    void temporaryHandleWithoutStorageIdCannotSnapshot() {
        FakeSlots slots = newSlots(4);
        StorageHandleImpl h = StorageHandleImpl.of(null, slots);
        assertThrows(IllegalStateException.class, h::snapshot);
    }

    @Test
    void closeIsNoOp() {
        StorageHandleImpl h = StorageHandleImpl.of(SID, newSlots(4));
        h.close(); // must not throw
    }

    @Test
    void revisionSourceIsConsultedPerSnapshot() {
        long[] revision = {3L};
        LongSupplier source = () -> revision[0];
        FakeSlots slots = newSlots(4);
        StorageHandleImpl h = new StorageHandleImpl(SID, slots, s -> true, s -> true, source);
        assertEquals(3L, h.snapshot().revision());
        revision[0] = 9L;
        assertEquals(9L, h.snapshot().revision());
    }
}
