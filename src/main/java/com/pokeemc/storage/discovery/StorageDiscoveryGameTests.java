package com.pokeemc.storage.discovery;

import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StorageProtectionEvents;
import com.pokeemc.storage.StorageProtectionEvents.ClaimResult;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageQuery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Task 6 认领与发现 GameTest（服务端运行，空结构 empty.nbt + 运行时放置方块）。
 *
 * 覆盖：
 * - 认领：真实放置事件延迟到下一 tick 自动认领；命令/扫描生成的容器保持无主；
 * - 双箱冲突：异所有者合并被拒，同所有者合并按显式规则迁移且 ACL 不扩大；
 * - 未加载区块：查询只扫已加载区块，命中未加载区块的登记仓储时标记扫描不完整，不强制加载；
 * - 频率限频：同 tick 二次查询返回缓存；重复标脏同一区块被去重。
 */
@GameTestHolder("poketrade")
@PrefixGameTestTemplate(false)
public class StorageDiscoveryGameTests {

    private static final String BATCH = "discovery";
    private static final String SINGLE_CHEST = "vanilla_chest";
    private static final String DOUBLE_CHEST = "vanilla_double_chest";

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void claimOnPlayerPlaceNextTickAndCommandContainersStayUnclaimed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StorageProtectionEvents protection = protection();
        BlockPos rel = new BlockPos(1, 1, 1);
        BlockPos abs = helper.absolutePos(rel);
        helper.setBlock(rel, Blocks.CHEST);

        // 命令/扫描生成的容器（setBlock，无玩家放置事件）保持无主
        check(savedData(helper).getRecord(singleKey(level, abs)).isEmpty(),
                "command-placed chest must stay unclaimed");

        // 真实放置事件：onPlace 排入延迟队列，记录在下一 tick 处理前不可见
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, abs);
        protection.onPlace(new BlockEvent.EntityPlaceEvent(snapshot, Blocks.AIR.defaultBlockState(), player));
        check(savedData(helper).getRecord(singleKey(level, abs)).isEmpty(),
                "claim must be deferred until the next tick");

        // 下一 tick（ServerTickEvent.Post）处理队列 → 自动认领
        protection.onServerTick(new ServerTickEvent.Post(() -> true, level.getServer()));
        StorageRecord record = savedData(helper).getRecord(singleKey(level, abs))
                .orElseThrow(() -> new IllegalStateException("chest must be claimed after the next tick"));
        check(record.ownerId().equals(player.getUUID()), "record must be owned by the placing player");

        // 同所有者重复认领 → ALREADY_CLAIMED
        check(protection.claim(level, abs, player.getUUID(), player.getName().getString())
                        == ClaimResult.ALREADY_CLAIMED,
                "same-owner re-claim must be ALREADY_CLAIMED");

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void doubleChestDifferentOwnerRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StorageProtectionEvents protection = protection();
        UUID ownerX = UUID.randomUUID();
        UUID ownerY = UUID.randomUUID();

        BlockState facingNorth = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        BlockPos rel1 = new BlockPos(1, 1, 1);
        BlockPos rel2 = new BlockPos(2, 1, 1);
        helper.setBlock(rel1, facingNorth);
        BlockPos abs1 = helper.absolutePos(rel1);
        BlockPos abs2 = helper.absolutePos(rel2);

        check(protection.claim(level, abs1, ownerX, "X") == ClaimResult.CLAIMED, "first half claimed by X");

        // 相邻放置第二个半区组成双箱：异所有者放置被 canPlace 拒绝，认领返回 CONFLICT
        helper.setBlock(rel2, facingNorth.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        check(!protection.canPlace(level, abs2, ownerY),
                "different-owner placement next to a claimed chest must be rejected");
        check(protection.claim(level, abs2, ownerY, "Y") == ClaimResult.CONFLICT,
                "different-owner claim of a merged double chest must conflict");

        // 原半区记录保持 X 所有，未被迁移或覆盖
        StorageRecord record = savedData(helper).getRecord(singleKey(level, abs1))
                .orElseThrow(() -> new IllegalStateException("original half must still be claimed"));
        check(record.ownerId().equals(ownerX), "original half must remain owned by X");

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void doubleChestSameOwnerMigratesWithAclIntact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StorageProtectionEvents protection = protection();
        UUID owner = UUID.randomUUID();

        BlockState facingNorth = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        BlockPos rel1 = new BlockPos(1, 1, 1);
        BlockPos rel2 = new BlockPos(2, 1, 1);
        helper.setBlock(rel1, facingNorth);
        BlockPos abs1 = helper.absolutePos(rel1);
        BlockPos abs2 = helper.absolutePos(rel2);

        check(protection.claim(level, abs1, owner, "Owner") == ClaimResult.CLAIMED, "single half claimed by owner");

        // 同一所有者放置第二个半区 → 旧半区记录迁移到规范化主键
        helper.setBlock(rel2, facingNorth.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        check(protection.claim(level, abs2, owner, "Owner") == ClaimResult.MIGRATED,
                "same-owner second half must migrate the record to the canonical key");

        // 规范主键记录存在、旧单箱键消失、ACL 保持为空（合并不静默扩大权限）
        StorageKey canonical = StorageKey.of(dim(level), DOUBLE_CHEST, AbstractContainerAdapter.toLocation(abs1));
        StorageRecord migrated = savedData(helper).getRecord(canonical)
                .orElseThrow(() -> new IllegalStateException("canonical double-chest record must exist after migration"));
        check(migrated.ownerId().equals(owner), "migrated record must keep the owner");
        check(savedData(helper).getRecord(singleKey(level, abs1)).isEmpty(), "legacy single-chest record must be gone");
        check(migrated.grants().isEmpty(), "ACL must stay empty after migration (no silent privilege expansion)");

        // 主半区再次认领 → 已按规范主键登记 → ALREADY_CLAIMED
        check(protection.claim(level, abs1, owner, "Owner") == ClaimResult.ALREADY_CLAIMED,
                "primary half re-claim after migration must be ALREADY_CLAIMED");

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void unloadedChunkNotForceLoadedAndMarkedIncomplete(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = dim(level);
        UUID owner = UUID.randomUUID();

        // 远离测试结构：该区块必然未加载
        BlockPos farAbs = helper.absolutePos(BlockPos.ZERO).offset(1000, 0, 1000);
        check(!level.isLoaded(farAbs), "far position must lie in an unloaded chunk");

        // 直接登记一条已认领记录（模拟此前放置、如今区块已卸载的仓储）
        StorageSavedData data = savedData(helper);
        StorageKey key = StorageKey.of(dim, SINGLE_CHEST, AbstractContainerAdapter.toLocation(farAbs));
        check(data.claim(key, StorageRecord.create(owner, "Far", System.currentTimeMillis()),
                farAbs.getX() >> 4, farAbs.getZ() >> 4), "record must be claimable in an unloaded chunk");

        // 以该位置为中心查询：命中元数据，但区块保持不加载，结果标记扫描不完整
        List<StorageDescriptor> results = StorageServices.discovery().querySync(
                query(owner, dim, farAbs.getX(), farAbs.getZ(), 16, 10));
        check(!level.isLoaded(farAbs), "query must not force-load the chunk");
        StorageDescriptor unloaded = results.stream()
                .filter(d -> d.storageId().adapterType().equals(SINGLE_CHEST))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "unloaded storage must still be discoverable by metadata"));
        check(!unloaded.scanComplete(), "unloaded storage must be flagged as scan-incomplete");
        check(unloaded.slotCount() == 0, "unloaded storage must report zero slots");

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void brokenChestPrunedAndHiddenFromQuery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = dim(level);
        UUID owner = UUID.randomUUID();

        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        BlockPos abs = helper.absolutePos(rel);
        check(protection().claim(level, abs, owner, "Owner") == ClaimResult.CLAIMED, "chest claimed");

        // 移除方块（setBlock 不触发破坏事件，等价于容器已经不存在）
        helper.setBlock(rel, Blocks.AIR);
        check(!level.getBlockState(abs).is(Blocks.CHEST), "chest block must be gone");

        // 破坏后首次查询：不再返回该仓储，存档里的幽灵记录也被删除
        // （同一 tick 内再次查询会命中限频缓存，因此只查询一次）
        List<StorageDescriptor> after = StorageServices.discovery().querySync(
                query(owner, dim, abs.getX(), abs.getZ(), 16, 10));
        check(after.stream().noneMatch(d -> d.storageId().adapterType().equals(SINGLE_CHEST)),
                "broken chest must not appear in query results");
        check(savedData(helper).getRecord(singleKey(level, abs)).isEmpty(),
                "broken chest record must be pruned from saved data");

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void personalEnderChestListedForOwnerAndHiddenFromOthers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = dim(level);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        UUID actor = player.getUUID();

        // 往玩家末影箱放一个物品
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 1));

        // 本人查询：末影箱始终列出，27 格，能统计已放物品
        // [CHANGED] 会话 #15-C：2 参 querySync 注入玩家名解析器，断言 ownerName 为真实玩家名
        List<StorageDescriptor> results = StorageServices.discovery().querySync(
                query(actor, dim, (int) player.getX(), (int) player.getZ(), 16, 10),
                id -> "Owner");
        StorageDescriptor ender = results.stream()
                .filter(d -> d.storageId().adapterType().equals(VanillaEnderChestAdapter.TYPE_ID))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("personal ender chest must be listed"));
        check(ender.ownerId().equals(actor), "ender chest must be owned by the actor");
        check("Owner".equals(ender.ownerName()),
                "ender chest ownerName must be resolved player name");
        check(ender.displayName().contains("末影箱"),
                "ender chest displayName must still contain 末影箱");
        check(ender.slotCount() == 27, "ender chest must expose 27 slots");
        check(ender.usedSlots() >= 0, "ender chest must report slot usage");
        check(ender.distance() == 0, "ender chest distance must be 0");

        // 其他玩家查询：看不到别人的末影箱（只列自己的）
        UUID other = UUID.randomUUID();
        List<StorageDescriptor> otherResults = StorageServices.discovery().querySync(
                query(other, dim, (int) player.getX(), (int) player.getZ(), 16, 10));
        check(otherResults.stream().noneMatch(d ->
                        d.storageId().adapterType().equals(VanillaEnderChestAdapter.TYPE_ID)
                                && d.ownerId().equals(actor)),
                "ender chest must be private to its owner");

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void queryRateLimitReturnsCacheAndDirtyDedupe(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = dim(level);
        StorageProtectionEvents protection = protection();
        UUID actor = UUID.randomUUID();

        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        BlockPos abs = helper.absolutePos(rel);
        check(protection.claim(level, abs, actor, "Owner") == ClaimResult.CLAIMED, "chest claimed");

        // 首次查询：命中已认领仓储
        List<StorageDescriptor> results1 = StorageServices.discovery().querySync(
                query(actor, dim, abs.getX(), abs.getZ(), 16, 10));
        check(results1.stream().anyMatch(d -> d.storageId().adapterType().equals(SINGLE_CHEST)),
                "first query must find the claimed chest");

        // 同一 tick 内以远离任何仓储的中心再次查询：命中限频 → 返回缓存而非重新扫描
        List<StorageDescriptor> results2 = StorageServices.discovery().querySync(
                query(actor, dim, abs.getX() + 500, abs.getZ() + 500, 16, 10));
        check(results2.equals(results1), "rate-limited query must return the cached result");
        StorageDescriptor chest1 = results1.stream()
                .filter(d -> d.storageId().adapterType().equals(SINGLE_CHEST))
                .findFirst().orElseThrow();
        check(results2.stream().filter(d -> d.storageId().adapterType().equals(SINGLE_CHEST))
                        .findFirst().map(d -> d.storageId().equals(chest1.storageId()))
                        .orElse(false),
                "cached result must match the first query");

        // 重复标脏同一区块：去重拒绝
        StorageDiscoveryService discovery = StorageServices.discovery();
        BlockPos farAbs = helper.absolutePos(BlockPos.ZERO).offset(400, 0, 400);
        int chunkX = farAbs.getX() >> 4;
        int chunkZ = farAbs.getZ() >> 4;
        check(discovery.markChunkDirty(dim, chunkX, chunkZ), "first dirty mark must be accepted");
        check(!discovery.markChunkDirty(dim, chunkX, chunkZ), "duplicate dirty mark must be rejected");

        helper.succeed();
    }

    // ---------------------------------------------------------------- 工具

    private static StorageProtectionEvents protection() {
        return new StorageProtectionEvents(StorageServices.registry(), StorageServices.discovery());
    }

    private static StorageSavedData savedData(GameTestHelper helper) {
        return helper.getLevel().getServer().overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    private static String dim(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static StorageKey singleKey(ServerLevel level, BlockPos abs) {
        return StorageKey.of(dim(level), SINGLE_CHEST, AbstractContainerAdapter.toLocation(abs));
    }

    private static StorageQuery query(UUID actorId, String dim, int centerX, int centerZ, int radius, int maxResults) {
        return new StorageQuery(actorId, dim, centerX, centerZ, radius, null,
                StorageQuery.Sort.DISTANCE, StorageQuery.Filter.VIEWABLE, maxResults);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
