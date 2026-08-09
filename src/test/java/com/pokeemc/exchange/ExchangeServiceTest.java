package com.pokeemc.exchange;

import com.pokeemc.exchange.price.ExchangePriceService;
import com.pokeemc.exchange.price.OfficialPriceParser;
import com.pokeemc.storage.StorageAccessService;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.adapter.SlotStore;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import com.pokeemc.storage.adapter.StorageHandleImpl;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.storage.StorageTransactionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExchangeService} 出售回归测试：重点锁定 finalreview C1 ——
 * 同一仓储同一槽位的重复行（虚报总量）必须整体失败且钱包零入账、槽位零扣减。
 */
class ExchangeServiceTest {

    private static final long TTL_MILLIS = 10_000L;
    private static final UUID OWNER = UUID.randomUUID();
    private static final String DIM = "minecraft:overworld";
    private static final String ADAPTER_TYPE = "test_chest";

    private static final StorageId SID_A = new StorageId(DIM, ADAPTER_TYPE, "0;64;0");
    private static final StorageId SID_B = new StorageId(DIM, ADAPTER_TYPE, "10;64;0");

    /** 槽位桩（与 StorageTransactionServiceTest 一致）。 */
    private static final class FakeSlots implements SlotStore {
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

    private static final class FakeAdapter implements StorageAdapter {
        private final String typeId;
        private final Map<StorageId, FakeSlots> slotsByStorage;

        FakeAdapter(String typeId, Map<StorageId, FakeSlots> slotsByStorage) {
            this.typeId = typeId;
            this.slotsByStorage = slotsByStorage;
        }

        @Override
        public String typeId() {
            return typeId;
        }

        @Override
        public Set<StorageCapability> capabilities() {
            return Set.of();
        }

        @Override
        public boolean supports(StorageAdapterContext context) {
            return context.storageId().adapterType().equals(typeId);
        }

        @Override
        public Optional<StorageHandle> open(StorageAdapterContext context) {
            FakeSlots slots = slotsByStorage.get(context.storageId());
            return slots == null ? Optional.empty()
                    : Optional.of(StorageHandleImpl.of(context.storageId(), slots));
        }
    }

    private StorageSavedData data;
    private final Map<StorageId, FakeSlots> storageSlots = new HashMap<>();
    private final long[] credited = {0L}; // 钱包入账累计（验证失败路径零入账）
    private ExchangeService service;

    @BeforeEach
    void setUp() {
        StorageAdapterRegistryImpl registry = new StorageAdapterRegistryImpl();
        registry.register(new FakeAdapter(ADAPTER_TYPE, storageSlots));
        storageSlots.put(SID_A, new FakeSlots(1, 64));
        storageSlots.put(SID_B, new FakeSlots(1, 64));
        data = new StorageSavedData();
        StorageAccessService access = new StorageAccessService(
                id -> Optional.empty(), id -> false, (actor, owner, perm) -> {
                });
        ExchangePriceService prices = new ExchangePriceService(
                Map.of(
                        TradeItemId.parse("minecraft:diamond"), new OfficialPriceParser.DoublePrice(20.0, 10.0),
                        TradeItemId.parse("minecraft:emerald"), new OfficialPriceParser.DoublePrice(20.0, 10.0)),
                Map.of());
        service = new ExchangeService(
                registry, access, () -> data,
                (actorId, amount) -> {
                    credited[0] += amount;
                    return true;
                },
                prices,
                () -> 1_000_000L, TTL_MILLIS);
    }

    private StorageKey keyOf(StorageId sid) {
        return StorageKey.of(sid.dimension(), sid.adapterType(), sid.location());
    }

    private void claim(StorageId sid) {
        StorageRecord record = StorageRecord.create(OWNER, "Owner", 1_000L);
        assertTrue(data.claim(keyOf(sid), record, 0, 0), "claim must succeed: " + sid);
    }

    private StorageTransactionResult sell(List<ExchangeService.SellEntry> entries) {
        Map<StorageId, Long> revisions = new HashMap<>();
        for (ExchangeService.SellEntry e : entries) {
            revisions.put(e.storageId(), data.getRecord(keyOf(e.storageId())).orElseThrow().revision());
        }
        return service.sell(OWNER, "s1", "op-" + System.nanoTime(), entries, revisions);
    }

    @Test
    void duplicateSlotLinesRejectedWithoutWalletCredit() {
        // C1 回归：槽位仅 10 件，两条重复行各 10（虚报总量 20）必须整体失败，
        // 钱包零入账、槽位物品零扣减（否则可"钱包按 20 件入账、实际只扣 10 件"刷钱）。
        claim(SID_A);
        storageSlots.get(SID_A).set(0, "minecraft:diamond", 10);
        credited[0] = 0;

        StorageTransactionResult r = sell(List.of(
                new ExchangeService.SellEntry(SID_A, 0, 10, 0),
                new ExchangeService.SellEntry(SID_A, 0, 10, 0)));

        assertFalse(r.success(), "重复行虚报总量必须拒绝，实际 " + r);
        assertEquals(ExchangeService.SOURCE_EMPTY, r.code());
        assertEquals(0L, credited[0], "拒绝后钱包必须零入账");
        assertEquals("minecraft:diamond", storageSlots.get(SID_A).itemId(0), "槽位物品不应被扣减");
        assertEquals(10, storageSlots.get(SID_A).count(0), "槽位数量不应被扣减");
    }

    @Test
    void aggregateMustNotRejectLegitimateMultiSlotSell() {
        // 聚合只作用于同一槽位：不同仓储/不同槽位多行出售不受影响，仍正常入账与扣减。
        claim(SID_A);
        claim(SID_B);
        storageSlots.get(SID_A).set(0, "minecraft:diamond", 10);
        storageSlots.get(SID_B).set(0, "minecraft:emerald", 10);
        credited[0] = 0;

        StorageTransactionResult r = sell(List.of(
                new ExchangeService.SellEntry(SID_A, 0, 10, 0),
                new ExchangeService.SellEntry(SID_B, 0, 10, 0)));

        assertTrue(r.success(), "不同槽位多行出售应成功，实际 " + r);
        assertEquals(2_000L, credited[0], "两槽各 10 件 × 100 PKM 应入账 2000");
        assertEquals(0, storageSlots.get(SID_A).count(0), "A 槽应扣至 0");
        assertEquals(0, storageSlots.get(SID_B).count(0), "B 槽应扣至 0");
    }
}
