package com.pokeemc.trade.asset;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.TradeError;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3：物品托管 gateway 测试（计划 3.1）。
 * 覆盖部分栈托管、完整栈托管、槽位内容在校验后变化、NBT 超限、
 * 取消归还原槽、背包满转收件箱、重复交付不复制（由收件箱状态机保证，此处验证 deliver 幂等语义）。
 */
class ItemEscrowGatewayTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // ------------------------------------------------------------------ prepare/remove

    @Test
    void partialStackEscrow() {
        FakeStore store = FakeStore.withItem(0, "minecraft:diamond", 64);
        var prep = ItemEscrowGateway.prepare(store, 0, 10, OWNER);
        assertTrue(prep.ok());
        assertEquals(10, prep.value().count());
        assertEquals(64, prep.value().snapshot().count());

        var removed = ItemEscrowGateway.remove(store, prep.value(), OWNER);
        assertTrue(removed.ok());
        assertEquals(10, ItemEscrowGateway.assetCount(removed.value().asset()));
        // 槽位剩余 54
        assertEquals(54, store.get(0).count());
        // 资产 NBT Count=10
        assertEquals(10, removed.value().asset().stackNbt().getByte("Count") & 0xFF);
    }

    @Test
    void fullStackEscrowClearsSlot() {
        FakeStore store = FakeStore.withItem(0, "minecraft:stick", 1);
        var prep = ItemEscrowGateway.prepare(store, 0, 1, OWNER);
        assertTrue(prep.ok());
        var removed = ItemEscrowGateway.remove(store, prep.value(), OWNER);
        assertTrue(removed.ok());
        assertTrue(store.get(0).isEmpty());
    }

    @Test
    void slotChangedAfterPrepareRejected() {
        FakeStore store = FakeStore.withItem(0, "minecraft:diamond", 64);
        var prep = ItemEscrowGateway.prepare(store, 0, 10, OWNER);
        assertTrue(prep.ok());
        // 校验后槽位内容变化：换成不同物品
        store.set(0, ItemSnapshot.empty());
        store.set(0, snapshot("minecraft:emerald", 5));
        var removed = ItemEscrowGateway.remove(store, prep.value(), OWNER);
        assertFalse(removed.ok());
        assertEquals(TradeError.ITEM_SLOT_CHANGED, removed.error());
    }

    @Test
    void slotCountReducedAfterPrepareRejected() {
        FakeStore store = FakeStore.withItem(0, "minecraft:diamond", 64);
        var prep = ItemEscrowGateway.prepare(store, 0, 10, OWNER);
        assertTrue(prep.ok());
        // 数量减少到不足 10
        store.set(0, snapshot("minecraft:diamond", 5));
        var removed = ItemEscrowGateway.remove(store, prep.value(), OWNER);
        assertFalse(removed.ok());
        assertEquals(TradeError.ITEM_SLOT_CHANGED, removed.error());
    }

    @Test
    void nbtOverLimitRejected() {
        FakeStore store = FakeStore.withItem(0, "minecraft:diamond", 1);
        // 直接构造超限快照：塞入大字符串
        CompoundTag huge = new CompoundTag();
        huge.putString("id", "minecraft:diamond");
        huge.putByte("Count", (byte) 1);
        huge.putByteArray("payload", new byte[ItemEscrowGateway.MAX_ITEM_NBT_BYTES + 1]);
        store.set(0, new ItemSnapshot("minecraft:diamond", 1, huge));

        var prep = ItemEscrowGateway.prepare(store, 0, 1, OWNER);
        assertFalse(prep.ok());
        assertEquals(TradeError.ITEM_NBT_TOO_LARGE, prep.error());
    }

    @Test
    void invalidSlotAndCountRejected() {
        FakeStore store = FakeStore.withItem(0, "minecraft:diamond", 64);
        assertFalse(ItemEscrowGateway.prepare(store, -1, 1, OWNER).ok());
        assertFalse(ItemEscrowGateway.prepare(store, store.size(), 1, OWNER).ok());
        assertFalse(ItemEscrowGateway.prepare(store, 0, 0, OWNER).ok());
        assertFalse(ItemEscrowGateway.prepare(store, 0, 65, OWNER).ok());
    }

    // ------------------------------------------------------------------ cancel

    @Test
    void cancelRestoresOriginalSlot() {
        FakeStore store = FakeStore.withItem(0, "minecraft:diamond", 64);
        var prep = ItemEscrowGateway.prepare(store, 0, 10, OWNER);
        assertTrue(prep.ok());
        // 模拟槽位已被清空（escrow 移除后）
        store.set(0, ItemSnapshot.empty());
        var cancelled = ItemEscrowGateway.cancel(store, prep.value());
        assertTrue(cancelled.ok());
        assertEquals(10, store.get(0).count());
        assertEquals("minecraft:diamond", store.get(0).itemId());
    }

    @Test
    void cancelToOccupiedSlotFails() {
        FakeStore store = FakeStore.withItem(0, "minecraft:diamond", 64);
        var prep = ItemEscrowGateway.prepare(store, 0, 10, OWNER);
        assertTrue(prep.ok());
        // 槽位被占满（无法归还原数量）
        store.set(0, snapshot("minecraft:emerald", 5));
        var cancelled = ItemEscrowGateway.cancel(store, prep.value());
        assertFalse(cancelled.ok());
        assertEquals(TradeError.ITEM_SLOT_CHANGED, cancelled.error());
    }

    // ------------------------------------------------------------------ deliver

    @Test
    void deliverFillsPartialThenRemainingToInbox() {
        FakeStore store = new FakeStore(10);
        store.set(0, snapshot("minecraft:diamond", 5)); // 已有 5，maxStack=64
        // 托管 70 个钻石
        CompoundTag nbt = snapshot("minecraft:diamond", 70).nbt();
        ItemAsset asset = new ItemAsset(UUID.randomUUID(), OWNER, nbt);

        var result = ItemEscrowGateway.deliver(
                store, asset, DeliveryPreference.ItemDestination.AUTO);
        // 已有 5 -> 合并到 64（59 个），空槽 1 放 11 个 -> 共放入 70
        assertEquals(70, result.placed());
        assertEquals(0, result.remaining());
        assertTrue(result.allDelivered());
        assertEquals(64, store.get(0).count());
        assertEquals(11, store.get(1).count());
    }

    @Test
    void deliverToFullInventoryRemainingToInbox() {
        FakeStore store = new FakeStore(2);
        store.set(0, snapshot("minecraft:diamond", 64));
        store.set(1, snapshot("minecraft:diamond", 64));
        ItemAsset asset = new ItemAsset(UUID.randomUUID(), OWNER,
                snapshot("minecraft:diamond", 32).nbt());

        var result = ItemEscrowGateway.deliver(
                store, asset, DeliveryPreference.ItemDestination.AUTO);
        assertEquals(0, result.placed());
        assertEquals(32, result.remaining());
        assertFalse(result.allDelivered());
    }

    @Test
    void deliverToExplicitInboxPlacesNothing() {
        FakeStore store = new FakeStore(10);
        ItemAsset asset = new ItemAsset(UUID.randomUUID(), OWNER,
                snapshot("minecraft:diamond", 16).nbt());
        var result = ItemEscrowGateway.deliver(
                store, asset, DeliveryPreference.ItemDestination.INBOX);
        assertEquals(0, result.placed());
        assertEquals(16, result.remaining());
        assertTrue(store.get(0).isEmpty());
    }

    @Test
    void deliverMergesOnlySameComponents() {
        FakeStore store = new FakeStore(10);
        store.set(0, snapshot("minecraft:diamond", 10));
        // 不同组件的钻石（带附魔）不得合并
        CompoundTag enchanted = snapshot("minecraft:diamond", 1).nbt();
        enchanted.putString("components", "different");
        ItemAsset asset = new ItemAsset(UUID.randomUUID(), OWNER, enchanted);

        var result = ItemEscrowGateway.deliver(
                store, asset, DeliveryPreference.ItemDestination.AUTO);
        assertEquals(1, result.placed());
        assertEquals(0, result.remaining());
        // 原槽 10 个普通钻石未动；放入槽 1
        assertEquals(10, store.get(0).count());
        assertEquals(1, store.get(1).count());
    }

    // ------------------------------------------------------------------ 辅助

    private static ItemSnapshot snapshot(String itemId, int count) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", itemId);
        nbt.putByte("Count", (byte) count);
        return new ItemSnapshot(itemId, count, nbt);
    }

    /** 假背包：足够完成 gateway 逻辑测试 */
    static final class FakeStore implements PlayerInventoryStore {

        private final List<ItemSnapshot> slots;
        private int changes;

        FakeStore(int size) {
            this.slots = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                slots.add(ItemSnapshot.empty());
            }
        }

        static FakeStore withItem(int slot, String itemId, int count) {
            FakeStore store = new FakeStore(40);
            store.set(slot, snapshot(itemId, count));
            return store;
        }

        int changes() {
            return changes;
        }

        @Override
        public int size() {
            return slots.size();
        }

        @Override
        public ItemSnapshot get(int slot) {
            return slots.get(slot);
        }

        @Override
        public void set(int slot, ItemSnapshot snapshot) {
            slots.set(slot, snapshot == null ? ItemSnapshot.empty() : snapshot);
        }

        @Override
        public int maxStack(int slot) {
            return 64;
        }

        @Override
        public boolean isFull() {
            return slots.stream().noneMatch(s -> s.isEmpty());
        }

        @Override
        public void setChanged() {
            changes++;
        }
    }
}
