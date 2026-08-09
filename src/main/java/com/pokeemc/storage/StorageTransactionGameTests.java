package com.pokeemc.storage;

import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import com.pokeemc.storage.adapter.StorageHandleExt;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageEndpoint;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageSnapshot;
import com.poketrade.api.storage.StorageTransaction;
import com.poketrade.api.storage.StorageTransactionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 7 真实槽位事务 GameTest（服务端运行，空结构 empty.nbt + 运行时放置方块）。
 *
 * <p>全部场景使用 STORAGE→STORAGE 事务（模拟玩家菜单在两个仓储间拖放物品），
 * 直接调用 {@link StorageServices#transactionService()} 执行，覆盖：</p>
 * <ul>
 *   <li>竞态槽变化：请求发出后目标槽被第三方修改，指纹冲突整体拒绝、源不动、revision 不变；</li>
 *   <li>重复包：同 actor/session/op 二次提交返回缓存结果，不重复执行、不重复审计；</li>
 *   <li>部分容量：目标槽剩余空间不足请求量，simulate 拒绝、整体失败、源不动；</li>
 *   <li>不可堆叠组件：同物品已在目标槽且最大堆叠 1，simulate 拒绝；清空后可成功；</li>
 *   <li>第三方非原子适配器：仅实现公共 StorageHandle 的适配器降级为
 *       {@code ADAPTER_UNAVAILABLE}，不参与事务。</li>
 * </ul>
 */
@GameTestHolder("poketrade")
@PrefixGameTestTemplate(false)
public class StorageTransactionGameTests {

    private static final String BATCH = "storage";
    private static final String CHEST = "vanilla_chest";
    private static final String DIM = "minecraft:overworld";
    private static final String NON_ATOMIC = "fake_non_atomic";
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000007");

    // ---------------------------------------------------------------- 竞态槽变化

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void raceSlotChangeRejected(GameTestHelper helper) {
        StorageSavedData data = data(helper);
        BlockPos srcAbs = chest(helper, new BlockPos(1, 1, 1));
        BlockPos tgtAbs = chest(helper, new BlockPos(3, 1, 1));
        claim(helper, srcAbs, data);
        claim(helper, tgtAbs, data);

        ChestBlockEntity src = (ChestBlockEntity) helper.getBlockEntity(
                new BlockPos(1, 1, 1));
        ChestBlockEntity tgt = (ChestBlockEntity) helper.getBlockEntity(
                new BlockPos(3, 1, 1));
        src.setItem(0, new ItemStack(Items.STONE, 5));
        tgt.setItem(0, new ItemStack(Items.DIRT, 1));

        try (StorageHandleExt hSrc = open(srcAbs, CHEST);
             StorageHandleExt hTgt = open(tgtAbs, CHEST)) {
            long srcFp = hSrc.fingerprint(0);
            long tgtFp = hTgt.fingerprint(0);
            long rev = record(data, keyAt(srcAbs, CHEST)).revision();
            StorageTransaction t = tx(OWNER, "s1", "race-1",
                    sid(srcAbs, CHEST), 0, sid(tgtAbs, CHEST), 0,
                    5, srcFp, tgtFp,
                    Map.of(sid(srcAbs, CHEST), rev, sid(tgtAbs, CHEST), rev));

            // 包发出后、服务端执行前，目标槽被第三方直接改掉 → 指纹冲突
            tgt.setItem(0, new ItemStack(Items.DIRT, 2));

            StorageTransactionResult r = execute(t);
            check(!r.success(), "race-modified target must fail: " + r);
            check("content_changed".equals(r.code()),
                    "expected content_changed, got " + r.code());
            check(src.getItem(0).getCount() == 5, "source must be untouched after race failure");
            check(record(data, keyAt(srcAbs, CHEST)).revision() == rev,
                    "revision must not bump on failed transaction");
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 重复包（幂等）

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void duplicatePacketIsIdempotent(GameTestHelper helper) {
        StorageSavedData data = data(helper);
        BlockPos srcAbs = chest(helper, new BlockPos(1, 1, 1));
        BlockPos tgtAbs = chest(helper, new BlockPos(3, 1, 1));
        claim(helper, srcAbs, data);
        claim(helper, tgtAbs, data);

        ChestBlockEntity src = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        src.setItem(0, new ItemStack(Items.STONE, 10));
        long rev = record(data, keyAt(srcAbs, CHEST)).revision();
        long auditBefore = data.auditSize();

        StorageTransaction t = tx(OWNER, "s1", "dup-1",
                sid(srcAbs, CHEST), 0, sid(tgtAbs, CHEST), 0,
                5, 0, 0, Map.of(sid(srcAbs, CHEST), rev, sid(tgtAbs, CHEST), rev));

        StorageTransactionResult first = execute(t);
        check(first.success(), "first execution must succeed: " + first);
        check(src.getItem(0).getCount() == 5, "source must drop to 5 after first execution");

        // 同一字节重发（同 actor/session/op）：返回首次结果，不二次执行
        StorageTransactionResult second = execute(t);
        check(second.success(), "duplicate must return cached success");
        check(second.message().equals(first.message()),
                "duplicate must return the identical result message");
        check(src.getItem(0).getCount() == 5, "duplicate must not move items again");
        check(data.auditSize() == auditBefore + 2,
                "duplicate must not append audit entries again (expected 2, got "
                        + (data.auditSize() - auditBefore) + ")");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 部分容量

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void partialCapacityRejected(GameTestHelper helper) {
        StorageSavedData data = data(helper);
        BlockPos srcAbs = chest(helper, new BlockPos(1, 1, 1));
        BlockPos tgtAbs = chest(helper, new BlockPos(3, 1, 1));
        claim(helper, srcAbs, data);
        claim(helper, tgtAbs, data);

        ChestBlockEntity src = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        ChestBlockEntity tgt = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(3, 1, 1));
        src.setItem(0, new ItemStack(Items.STONE, 10));
        tgt.setItem(0, new ItemStack(Items.STONE, 60)); // 64 上限，仅剩 4 格
        long rev = record(data, keyAt(srcAbs, CHEST)).revision();

        StorageTransaction t = tx(OWNER, "s1", "cap-1",
                sid(srcAbs, CHEST), 0, sid(tgtAbs, CHEST), 0,
                10, 0, 0, Map.of(sid(srcAbs, CHEST), rev, sid(tgtAbs, CHEST), rev));

        StorageTransactionResult r = execute(t);
        check(!r.success(), "over-capacity move must fail as a whole: " + r);
        check("target_blocked".equals(r.code()), "expected target_blocked, got " + r.code());
        check(src.getItem(0).getCount() == 10, "source must be untouched after capacity failure");
        check(tgt.getItem(0).getCount() == 60, "target must be untouched after capacity failure");
        check(record(data, keyAt(srcAbs, CHEST)).revision() == rev, "revision must not bump on failure");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 不可堆叠组件

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void nonStackableComponentBlocked(GameTestHelper helper) {
        StorageSavedData data = data(helper);
        BlockPos srcAbs = chest(helper, new BlockPos(1, 1, 1));
        BlockPos tgtAbs = chest(helper, new BlockPos(3, 1, 1));
        claim(helper, srcAbs, data);
        claim(helper, tgtAbs, data);

        ChestBlockEntity src = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        ChestBlockEntity tgt = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(3, 1, 1));
        src.setItem(0, new ItemStack(Items.DIAMOND_SWORD, 1));
        tgt.setItem(0, new ItemStack(Items.DIAMOND_SWORD, 1)); // 最大堆叠 1
        long rev = record(data, keyAt(srcAbs, CHEST)).revision();

        // 同物品已占满目标槽：simulate 拒绝，整体失败
        StorageTransaction blocked = tx(OWNER, "s1", "ns-1",
                sid(srcAbs, CHEST), 0, sid(tgtAbs, CHEST), 0,
                1, 0, 0, Map.of(sid(srcAbs, CHEST), rev, sid(tgtAbs, CHEST), rev));
        StorageTransactionResult rb = execute(blocked);
        check(!rb.success(), "non-stackable merge must fail: " + rb);
        check("target_blocked".equals(rb.code()), "expected target_blocked, got " + rb.code());
        check(src.getItem(0).getCount() == 1, "source sword must stay after block");

        // 清空目标槽后同一物品可成功搬入
        tgt.setItem(0, ItemStack.EMPTY);
        StorageTransaction ok = tx(OWNER, "s1", "ns-2",
                sid(srcAbs, CHEST), 0, sid(tgtAbs, CHEST), 0,
                1, 0, 0, Map.of(sid(srcAbs, CHEST), rev, sid(tgtAbs, CHEST), rev));
        StorageTransactionResult rk = execute(ok);
        check(rk.success(), "move into empty target must succeed: " + rk);
        check(src.getItem(0).isEmpty(), "source sword must be moved out");
        check(tgt.getItem(0).getItem() == Items.DIAMOND_SWORD,
                "target must hold the moved sword");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 第三方非原子适配器降级

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void thirdPartyNonAtomicAdapterDegraded(GameTestHelper helper) {
        StorageSavedData data = data(helper);
        StorageAdapterRegistryImpl registry = StorageServices.registry();
        registry.register(new StorageAdapter() {
            @Override
            public String typeId() {
                return NON_ATOMIC;
            }

            @Override
            public Set<StorageCapability> capabilities() {
                return Set.of();
            }

            @Override
            public boolean supports(StorageAdapterContext context) {
                return NON_ATOMIC.equals(context.storageId().adapterType());
            }

            @Override
            public Optional<StorageHandle> open(StorageAdapterContext context) {
                return Optional.of(new NonAtomicHandle());
            }
        });

        // 第三方仓储无需真实方块，直接按 typeId 认领
        BlockPos fakeAbs = helper.absolutePos(new BlockPos(1, 1, 1));
        claimKey(helper, fakeAbs, keyAt(fakeAbs, NON_ATOMIC), data);
        BlockPos realAbs = chest(helper, new BlockPos(3, 1, 1));
        claim(helper, realAbs, data);

        ChestBlockEntity real = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(3, 1, 1));
        real.setItem(0, new ItemStack(Items.STONE, 1));

        long rev = record(data, keyAt(fakeAbs, NON_ATOMIC)).revision();
        StorageTransaction t = tx(OWNER, "s1", "na-1",
                sid(fakeAbs, NON_ATOMIC), 0, sid(realAbs, CHEST), 0,
                1, 0, 0, Map.of(sid(fakeAbs, NON_ATOMIC), rev, sid(realAbs, CHEST), rev));

        StorageTransactionResult r = execute(t);
        check(!r.success(), "non-atomic adapter must be rejected: " + r);
        check(StorageTransactionResult.ADAPTER_UNAVAILABLE.equals(r.code()),
                "expected adapter_unavailable, got " + r.code());
        check(real.getItem(0).getCount() == 1, "real target must be untouched");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 辅助

    /** 非原子句柄：只实现公共接口，模拟第三方适配器不具备槽位级事务能力。 */
    private static final class NonAtomicHandle implements StorageHandle {
        @Override
        public StorageSnapshot snapshot() {
            return new StorageSnapshot(
                    new StorageId(DIM, NON_ATOMIC, "0;0;0"), 1, Map.of());
        }

        @Override
        public boolean simulateInsert(int slotIndex, String itemId, int count) {
            return true;
        }

        @Override
        public boolean simulateExtract(int slotIndex, String itemId, int count) {
            return true;
        }

        @Override
        public void commitInsert(int slotIndex, String itemId, int count) {
        }

        @Override
        public void commitExtract(int slotIndex, String itemId, int count) {
        }

        @Override
        public void close() {
        }
    }

    private static StorageSavedData data(GameTestHelper helper) {
        return ServerLifecycleHooks.getCurrentServer().overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    private static StorageRecord record(StorageSavedData data, StorageKey key) {
        return data.getRecord(key).orElseThrow();
    }

    private static StorageKey keyAt(BlockPos abs, String typeId) {
        return StorageKey.of(DIM, typeId, AbstractContainerAdapter.toLocation(abs));
    }

    private static StorageId sid(BlockPos abs, String typeId) {
        return new StorageId(DIM, typeId, AbstractContainerAdapter.toLocation(abs));
    }

    /** 放置单个箱子并返回绝对坐标。 */
    private static BlockPos chest(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.CHEST);
        return helper.absolutePos(rel);
    }

    private static void claim(GameTestHelper helper, BlockPos abs, StorageSavedData data) {
        claimKey(helper, abs, keyAt(abs, CHEST), data);
    }

    private static void claimKey(GameTestHelper helper, BlockPos abs, StorageKey key, StorageSavedData data) {
        check(data.claim(key, StorageRecord.create(
                        OWNER, "Owner", System.currentTimeMillis()),
                abs.getX() >> 4, abs.getZ() >> 4),
                "claim failed for " + key);
    }

    private static StorageHandleExt open(BlockPos abs, String typeId) {
        return (StorageHandleExt) StorageServices.registry()
                .byTypeId(typeId).orElseThrow()
                .open(new StorageAdapterContext(sid(abs, typeId)))
                .orElseThrow();
    }

    private static StorageTransaction tx(
            UUID actor, String session, String op,
            StorageId src, int srcSlot, StorageId tgt, int tgtSlot,
            int count, long srcFp, long tgtFp, Map<StorageId, Long> revisions) {
        return new StorageTransaction(
                actor, session, op,
                StorageEndpoint.storage(src, srcSlot),
                StorageEndpoint.storage(tgt, tgtSlot),
                count, srcFp, tgtFp, revisions);
    }

    private static StorageTransactionResult execute(StorageTransaction t) {
        return StorageServices.transactionService().execute(t);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("GameTest assertion failed: " + message);
        }
    }
}
