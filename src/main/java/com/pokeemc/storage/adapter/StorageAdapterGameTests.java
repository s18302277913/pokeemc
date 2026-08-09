package com.pokeemc.storage.adapter;

import com.pokeemc.blockentity.CondenserBlockEntity;
import com.pokeemc.registry.ModBlocks;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StorageServices;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Task 5 适配器行为 GameTest（服务端运行，空结构 empty.nbt + 运行时放置方块）。
 *
 * 覆盖：
 * - 双箱合并/拆分：任一半访问返回同一规范化 StorageKey、统一槽位顺序与物理部件集合；
 * - 适配器变化：方块替换后 supports/open 相应切换；
 * - 未加载区块：open 返回 empty、supports 返回 false，不崩溃；
 * - 凝聚器槽位过滤：insert 仅槽位 0，extract 仅槽位 1。
 */
@GameTestHolder("poketrade")
@PrefixGameTestTemplate(false)
public class StorageAdapterGameTests {

    private static final String BATCH = "storage";
    private static final String DOUBLE_CHEST = "vanilla_double_chest";
    private static final String SINGLE_CHEST = "vanilla_chest";
    private static final String BARREL = "vanilla_barrel";
    private static final String CONDENSER = "poketrade_condenser";

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void doubleChestMergeAndSplit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();
        StorageAdapterRegistryImpl registry = StorageServices.registry();
        StorageAdapter doubleAdapter = adapter(registry, DOUBLE_CHEST);
        StorageAdapter singleAdapter = adapter(registry, SINGLE_CHEST);

        // 先放 SINGLE 主半区，再放 RIGHT 次半区；相邻放置会自动把主半区更新为 LEFT，构成双箱
        BlockState facingNorth = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        BlockPos primaryRel = new BlockPos(1, 1, 1);
        BlockPos secondaryRel = new BlockPos(2, 1, 1);
        helper.setBlock(primaryRel, facingNorth);
        helper.setBlock(secondaryRel, facingNorth.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        BlockPos primaryAbs = helper.absolutePos(primaryRel);
        BlockPos secondaryAbs = helper.absolutePos(secondaryRel);

        // 双箱适配器匹配两个半区；单箱适配器不匹配双箱半区
        check(doubleAdapter.supports(ctxOf(dim, DOUBLE_CHEST, primaryAbs)), "double adapter must support primary half");
        check(doubleAdapter.supports(ctxOf(dim, DOUBLE_CHEST, secondaryAbs)), "double adapter must support secondary half");
        check(!singleAdapter.supports(ctxOf(dim, SINGLE_CHEST, primaryAbs)), "single adapter must not match a double-chest half");

        // 无论从哪一半访问，规范化后都是同一 StorageKey，且归一为主半区位置
        StorageKey keyFromPrimary = registry.canonicalize(
                StorageKey.of(dim, DOUBLE_CHEST, AbstractContainerAdapter.toLocation(primaryAbs)));
        StorageKey keyFromSecondary = registry.canonicalize(
                StorageKey.of(dim, DOUBLE_CHEST, AbstractContainerAdapter.toLocation(secondaryAbs)));
        check(keyFromPrimary.equals(keyFromSecondary),
                "canonicalized key must be identical from both halves: " + keyFromPrimary + " vs " + keyFromSecondary);
        check(keyFromPrimary.location().equals(AbstractContainerAdapter.toLocation(primaryAbs)),
                "canonicalized key must point at primary half: " + keyFromPrimary);

        // 统一槽位顺序 + 物理部件集合：写入经槽位落到真实半区容器，两个半区视图一致
        try (StorageHandle hPrimary = doubleAdapter.open(ctxOf(dim, DOUBLE_CHEST, primaryAbs)).orElseThrow();
             StorageHandle hSecondary = doubleAdapter.open(ctxOf(dim, DOUBLE_CHEST, secondaryAbs)).orElseThrow()) {
            check(((StorageHandleExt) hPrimary).slotCount() == 54, "double chest must expose 54 slots");
            hPrimary.commitInsert(0, "minecraft:dirt", 2);
            hPrimary.commitInsert(27, "minecraft:stone", 3);
            check(hPrimary.snapshot().slots().equals(hSecondary.snapshot().slots()),
                    "slot snapshots from both halves must be identical");

            ChestBlockEntity chestA = (ChestBlockEntity) level.getBlockEntity(primaryAbs);
            ChestBlockEntity chestB = (ChestBlockEntity) level.getBlockEntity(secondaryAbs);
            check(chestA != null && chestA.getItem(0).getItem() == Items.DIRT && chestA.getItem(0).getCount() == 2,
                    "slot 0 must write to primary half container");
            check(chestB != null && chestB.getItem(0).getItem() == Items.STONE && chestB.getItem(0).getCount() == 3,
                    "slot 27 must write to secondary half container");
        }

        // 拆分：移除主半区后，次半区自动变回单箱
        helper.destroyBlock(primaryRel);
        check(!doubleAdapter.supports(ctxOf(dim, DOUBLE_CHEST, secondaryAbs)), "double adapter must stop supporting after split");
        check(singleAdapter.supports(ctxOf(dim, SINGLE_CHEST, secondaryAbs)), "remaining half must become a single chest");
        try (StorageHandle hSingle = singleAdapter.open(ctxOf(dim, SINGLE_CHEST, secondaryAbs)).orElseThrow()) {
            check(((StorageHandleExt) hSingle).slotCount() == 27, "remaining half must expose 27 slots");
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void adapterChangeOnBlockReplace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();
        StorageAdapterRegistryImpl registry = StorageServices.registry();

        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        BlockPos abs = helper.absolutePos(rel);

        StorageAdapter chestAdapter = adapter(registry, SINGLE_CHEST);
        check(chestAdapter.supports(ctxOf(dim, SINGLE_CHEST, abs)), "chest adapter must support a chest");
        try (StorageHandle h = chestAdapter.open(ctxOf(dim, SINGLE_CHEST, abs)).orElseThrow()) {
            check(((StorageHandleExt) h).slotCount() == 27, "single chest must expose 27 slots");
        }

        // 方块替换：箱子 → 木桶，适配器随之切换
        helper.destroyBlock(rel);
        helper.setBlock(rel, Blocks.BARREL);
        check(!chestAdapter.supports(ctxOf(dim, SINGLE_CHEST, abs)), "chest adapter must stop supporting after replacement");
        StorageAdapter barrelAdapter = adapter(registry, BARREL);
        check(barrelAdapter.supports(ctxOf(dim, BARREL, abs)), "barrel adapter must support a barrel");
        try (StorageHandle h = barrelAdapter.open(ctxOf(dim, BARREL, abs)).orElseThrow()) {
            check(((StorageHandleExt) h).slotCount() == 27, "barrel must expose 27 slots");
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void unloadedChunkReturnsEmpty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();
        StorageAdapterRegistryImpl registry = StorageServices.registry();

        // 远离测试结构的位置，保证区块未加载
        BlockPos farAbs = helper.absolutePos(BlockPos.ZERO).offset(10000, 0, 10000);
        check(!level.isLoaded(farAbs), "far position must be in an unloaded chunk");

        StorageAdapter chestAdapter = adapter(registry, SINGLE_CHEST);
        StorageAdapterContext ctx = ctxOf(dim, SINGLE_CHEST, farAbs);
        check(!chestAdapter.supports(ctx), "supports must be false for an unloaded chunk");
        check(chestAdapter.open(ctx).isEmpty(), "open must return empty for an unloaded chunk");

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void condenserSlotFiltering(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();
        StorageAdapterRegistryImpl registry = StorageServices.registry();

        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, ModBlocks.CONDENSER.get());
        BlockPos abs = helper.absolutePos(rel);

        StorageAdapter condenserAdapter = adapter(registry, CONDENSER);
        check(condenserAdapter.supports(ctxOf(dim, CONDENSER, abs)), "condenser adapter must support a condenser");
        try (StorageHandleExt h = (StorageHandleExt) condenserAdapter.open(ctxOf(dim, CONDENSER, abs)).orElseThrow()) {
            check(h.slotCount() == 2, "condenser view must expose exactly 2 slots (input/output)");

            // 只允许向槽位 0（输入槽）插入
            check(h.simulateInsert(0, "minecraft:dirt", 1), "insert into input slot must be allowed");
            check(!h.simulateInsert(1, "minecraft:dirt", 1), "insert into output slot must be rejected");
            h.commitInsert(0, "minecraft:dirt", 3);
            check(h.count(0) == 3 && "minecraft:dirt".equals(h.itemId(0)), "inserted item must land in input slot");

            // 只允许从槽位 1（输出槽）提取
            CondenserBlockEntity condenser = helper.getBlockEntity(rel);
            condenser.getOutputContainer().setItem(0, new ItemStack(Items.DIRT, 5));
            check(h.simulateExtract(1, "minecraft:dirt", 2), "extract from output slot must be allowed");
            check(!h.simulateExtract(0, "minecraft:dirt", 1), "extract from input slot must be rejected");
            h.commitExtract(1, "minecraft:dirt", 2);
            check(h.count(1) == 3, "extract must reduce the output slot");
        }

        helper.succeed();
    }

    private static StorageAdapter adapter(StorageAdapterRegistryImpl registry, String typeId) {
        return registry.byTypeId(typeId)
                .orElseThrow(() -> new IllegalStateException("missing adapter typeId: " + typeId));
    }

    private static StorageAdapterContext ctxOf(String dimension, String typeId, BlockPos absPos) {
        return new StorageAdapterContext(new StorageId(dimension, typeId, AbstractContainerAdapter.toLocation(absPos)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("GameTest assertion failed: " + message);
        }
    }
}
