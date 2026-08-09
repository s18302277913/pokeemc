package com.pokeemc.storage;

import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Task 11 保护与自动化守卫 GameTest（服务端运行，空结构 empty.nbt + 运行时放置方块）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>破坏无 BREAK 权限的有主仓储被取消（claim 认领 + 直接调用 {@code onBreak}）；</li>
 *   <li>有 BREAK 权限的所有者可破坏；</li>
 *   <li>创造模式无 BREAK 权限同样被取消（不因 isCreative 放行）；</li>
 *   <li>自动化插入/抽取由所有者开关控制：默认拒绝，开启后放行
 *       （capability 集成 + 纯判定 {@link StorageAutomationGuard#allowAutomation}）；</li>
 *   <li>爆炸 {@code protectAffectedBlocks} 从受影响列表移除有主仓储位置。</li>
 * </ul>
 */
@GameTestHolder("poketrade")
@PrefixGameTestTemplate(false)
public class StorageProtectionGameTests {

    private static final String BATCH = "protection";
    private static final String SINGLE_CHEST = "vanilla_chest";
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000011");

    // ---------------------------------------------------------------- 破坏保护

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void breakWithoutPermissionIsCanceled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = chest(helper, 1);
        claim(helper, abs, OWNER);

        Player other = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(
                level, abs, level.getBlockState(abs), other);
        protection().onBreak(event);
        check(event.isCanceled(),
                "non-owner break of claimed storage must be canceled");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void ownerWithBreakPermissionMayBreak(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = chest(helper, 1);
        Player owner = helper.makeMockPlayer(GameType.SURVIVAL);
        claim(helper, abs, owner.getUUID());

        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(
                level, abs, level.getBlockState(abs), owner);
        protection().onBreak(event);
        check(!event.isCanceled(), "owner break must be allowed");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void creativeWithoutPermissionStillCanceled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = chest(helper, 1);
        claim(helper, abs, OWNER);

        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(
                level, abs, level.getBlockState(abs), creative);
        protection().onBreak(event);
        check(event.isCanceled(),
                "creative non-owner break must still be canceled");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 自动化守卫

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void automationInsertExtractGatedByOwnerSettings(GameTestHelper helper) {
        // 纯判定：默认拒绝、未认领拒绝、开启放行
        StorageRecord rec = StorageRecord.create(OWNER, "Owner", System.currentTimeMillis());
        check(!StorageAutomationGuard.allowAutomation(rec, true),
                "insert must be denied by default");
        check(!StorageAutomationGuard.allowAutomation(rec, false),
                "extract must be denied by default");
        check(!StorageAutomationGuard.allowAutomation(null, true),
                "unclaimed storage must be denied");
        StorageRecord enabled = rec.withAutomationInsert(true).withAutomationExtract(true);
        check(StorageAutomationGuard.allowAutomation(enabled, true),
                "insert must be allowed when enabled");
        check(StorageAutomationGuard.allowAutomation(enabled, false),
                "extract must be allowed when enabled");

        // capability 集成：受控包装器接管，默认全拒，开启后放行
        ServerLevel level = helper.getLevel();
        BlockPos abs = chest(helper, 1);
        claim(helper, abs, OWNER);
        StorageKey key = key(level, abs);
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, abs, null);
        check(handler instanceof StorageAutomationGuard.GuardedHandler,
                "guarded handler must take over the item handler capability");

        check(!handler.insertItem(0, new ItemStack(Items.STONE, 1), false).isEmpty(),
                "insert must be rejected while automation is disabled");

        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(new BlockPos(1, 1, 1));
        chest.setItem(0, new ItemStack(Items.STONE, 3));
        check(handler.extractItem(0, 1, false).isEmpty(),
                "extract must be rejected while automation is disabled");
        check(chest.getItem(0).getCount() == 3,
                "extract must not remove items while disabled");

        // 开启抽取后放行
        updateRecord(helper, key, r -> r.withAutomationExtract(true));
        ItemStack extracted = handler.extractItem(0, 1, false);
        check(extracted.getItem() == Items.STONE && extracted.getCount() == 1,
                "extract must succeed after enabling");
        check(chest.getItem(0).getCount() == 2, "extract must remove one stone");

        // 开启插入后放行（同物品堆叠合并）
        updateRecord(helper, key, r -> r.withAutomationInsert(true));
        check(handler.insertItem(0, new ItemStack(Items.STONE, 1), false).isEmpty(),
                "insert must succeed after enabling");
        check(chest.getItem(0).getCount() == 3, "stone count must return to 3");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 爆炸保护

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void explosionDetonateRemovesClaimedStorageBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = chest(helper, 1);
        claim(helper, abs, OWNER);
        BlockPos ordinary = helper.absolutePos(new BlockPos(2, 1, 1)); // 非容器，不受保护

        List<BlockPos> affected = new ArrayList<>();
        affected.add(abs);
        affected.add(ordinary);

        int protectedCount = protection().protectAffectedBlocks(level, affected);
        check(protectedCount == 1, "exactly the claimed storage must be protected");
        check(affected.size() == 1 && affected.contains(ordinary),
                "affected list must keep the ordinary block and drop the claimed one");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 工具

    private static StorageProtectionEvents protection() {
        return new StorageProtectionEvents(
                StorageServices.registry(), StorageServices.discovery());
    }

    /** 放置单个箱子并返回绝对坐标。 */
    private static BlockPos chest(GameTestHelper helper, int x) {
        BlockPos rel = new BlockPos(x, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        return helper.absolutePos(rel);
    }

    private static StorageKey key(ServerLevel level, BlockPos abs) {
        return StorageKey.of(dim(level), SINGLE_CHEST, AbstractContainerAdapter.toLocation(abs));
    }

    private static String dim(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static StorageSavedData savedData(GameTestHelper helper) {
        return helper.getLevel().getServer().overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    private static void claim(GameTestHelper helper, BlockPos abs, UUID ownerId) {
        StorageKey key = key(helper.getLevel(), abs);
        check(savedData(helper).claim(key,
                        StorageRecord.create(ownerId, "Owner", System.currentTimeMillis()),
                        abs.getX() >> 4, abs.getZ() >> 4),
                "claim must succeed for " + key);
    }

    private static void updateRecord(GameTestHelper helper, StorageKey key,
                                     UnaryOperator<StorageRecord> transform) {
        StorageSavedData data = savedData(helper);
        StorageRecord now = data.getRecord(key).orElseThrow();
        check(data.updateRecord(key, now.revision(), transform),
                "updateRecord must succeed for " + key);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("GameTest assertion failed: " + message);
        }
    }
}
