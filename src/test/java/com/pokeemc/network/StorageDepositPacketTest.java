package com.pokeemc.network;

import com.pokeemc.storage.adapter.SlotStore;
import com.pokeemc.storage.adapter.StorageHandleImpl;
import com.poketrade.api.storage.StorageId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 一键存入/转移共用的“自动找目标槽位”逻辑回归测试。 */
class StorageDepositPacketTest {

    private static final StorageId SID =
            new StorageId("minecraft:overworld", "vanilla_chest", "0;64;0");

    static final class FakeSlots implements SlotStore {
        final String[] ids;
        final int[] counts;
        final int defaultMax;

        FakeSlots(int size, int defaultMax) {
            this.ids = new String[size];
            this.counts = new int[size];
            this.defaultMax = defaultMax;
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
            return defaultMax;
        }

        @Override
        public long fingerprint(int slot) {
            return 0;
        }

        @Override
        public void set(int slot, String itemId, int count) {
            ids[slot] = itemId;
            counts[slot] = count;
        }

        @Override
        public void setChanged() {
        }
    }

    private static StorageHandleImpl handle(FakeSlots slots) {
        return StorageHandleImpl.of(SID, slots);
    }

    @Test
    void prefersMergeOverEmptySlot() {
        FakeSlots slots = new FakeSlots(3, 64);
        slots.set(0, "minecraft:diamond", 60);
        assertEquals(0, StorageDepositPacket.findDepositSlot(
                handle(slots), "minecraft:diamond", 4));
    }

    @Test
    void usesFirstEmptySlotWhenNoMergeTarget() {
        FakeSlots slots = new FakeSlots(3, 64);
        slots.set(0, "minecraft:diamond", 60);
        slots.set(2, "minecraft:stone", 10);
        assertEquals(1, StorageDepositPacket.findDepositSlot(
                handle(slots), "minecraft:emerald", 1));
    }

    @Test
    void returnsMinusOneWhenFull() {
        FakeSlots slots = new FakeSlots(2, 64);
        slots.set(0, "minecraft:diamond", 64);
        slots.set(1, "minecraft:stone", 64);
        assertEquals(-1, StorageDepositPacket.findDepositSlot(
                handle(slots), "minecraft:emerald", 1));
        assertEquals(-1, StorageDepositPacket.findDepositSlot(
                handle(slots), "minecraft:diamond", 1));
    }

    @Test
    void rejectsCountBeyondMaxStackOnEmptySlot() {
        FakeSlots slots = new FakeSlots(1, 16);
        assertEquals(-1, StorageDepositPacket.findDepositSlot(
                handle(slots), "minecraft:ender_pearl", 17));
        assertEquals(0, StorageDepositPacket.findDepositSlot(
                handle(slots), "minecraft:ender_pearl", 16));
    }
}
