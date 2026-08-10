package com.pokeemc.storage;

import com.mojang.logging.LogUtils;
import com.pokeemc.network.StorageChangedPacket;
import com.pokeemc.registry.ModBlocks;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.ChestPairSupport;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import com.pokeemc.storage.adapter.VanillaShulkerBoxAdapter;
import com.pokeemc.storage.discovery.StorageDiscoveryService;
import com.pokeemc.thirdparty.ThirdPartyServices;
import com.poketrade.api.permission.ProtectionAction;
import com.poketrade.api.storage.StorageId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 仓储认领与容器变化事件（Task 6 + Task 11）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>玩家放置受支持容器方块后，下一 tick 自动登记为认领：仅 owner 通过
 *       {@link StorageAccessService} 的所有者特例获得权限，记录 ACL 为空，
 *       不向任何主体广播（notify_all 语义为 false）。</li>
 *   <li>放置第二个箱体形成双箱时：若主半区已有同 owner 认领则把旧半区记录
 *       原样迁移到规范化主键（ACL 保持不变）；不同 owner 直接在放置事件中
 *       拒绝并告警，绝不允许两个所有者的箱子静默合并。</li>
 *   <li>容器放置/破坏后标记对应区块为脏，驱动发现服务增量刷新。</li>
 *   <li>Task 11：破坏有主仓储严格校验 BREAK 权限（创造模式同样适用），
 *       环境破坏（破坏者为空）一律取消；爆炸只从受影响列表移除有主仓储、
 *       不取消爆炸本身；活塞推/拉路径上有有主仓储时取消移动。</li>
 * </ul>
 *
 * <p>认领核心逻辑 {@link #claim(ServerLevel, BlockPos, UUID, String)} 与冲突判定
 * {@link #canPlace(ServerLevel, BlockPos, UUID)} 为公开方法，GameTest 可直接调用
 * （{@code GameTestHelper#setBlock} 不触发 NeoForge 放置事件）。</p>
 */
public final class StorageProtectionEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String SINGLE_CHEST = "vanilla_chest";
    private static final String DOUBLE_CHEST = "vanilla_double_chest";
    private static final String TRAPPED_CHEST = "vanilla_trapped_chest";
    private static final String BARREL = "vanilla_barrel";
    private static final String CONDENSER = "poketrade_condenser";

    /** 认领结果（供事件处理与测试断言）。 */
    public enum ClaimResult {
        /** 新建认领成功。 */
        CLAIMED,
        /** 旧半区记录迁移到规范化主键成功。 */
        MIGRATED,
        /** 该仓储已由同一 owner 认领，无需变更。 */
        ALREADY_CLAIMED,
        /** 该位置已被其他 owner 认领，拒绝认领/放置。 */
        CONFLICT,
        /** 位置上的方块不是受支持容器。 */
        NOT_SUPPORTED
    }

    private final StorageAdapterRegistryImpl registry;
    private final StorageDiscoveryService discovery;
    /** 放置后待认领队列（下一 tick 处理，避免在方块状态稳定前认领）。 */
    private final ArrayDeque<PendingClaim> pendingClaims = new ArrayDeque<>();
    /** 双箱破坏后待降级检查队列（下一 tick 处理，破坏完成、另一半已变 SINGLE）。 */
    private final ArrayDeque<PendingDoubleCheck> pendingDoubleChecks = new ArrayDeque<>();
    /** 是否有待广播的仓储列表失效通知。 */
    private boolean refreshQueued;

    public StorageProtectionEvents(StorageAdapterRegistryImpl registry,
                                   StorageDiscoveryService discovery) {
        this.registry = registry;
        this.discovery = discovery;
    }

    // ==================== 事件监听 ====================

    /** 服务端 tick：增量刷新 + 处理延迟认领与双箱降级检查 + 刷新通知。 */
    public void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer() == null) {
            return;
        }
        discovery.tick();
        processPendingClaims();
        processPendingDoubleChecks();
        flushQueuedRefresh(event.getServer());
    }

    /** 放置事件：冲突则取消；否则标脏并排队下一 tick 认领。 */
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = serverLevel.getBlockState(pos);
        if (typeIdFor(state) == null) {
            return; // 非受支持容器，不参与认领
        }
        if (!canPlace(serverLevel, pos, player.getUUID())) {
            event.setCanceled(true);
            LOGGER.warn("storage: rejected chest placement at {} by {} (owner conflict)",
                    pos, player.getName().getString());
            return;
        }
        markDirty(serverLevel, pos);
        refreshQueued = true;
        pendingClaims.addLast(new PendingClaim(
                serverLevel, pos, player.getUUID(), player.getName().getString()));
    }

    /**
     * 破坏事件（Task 11）：有主仓储严格校验 BREAK 权限——无权限或环境破坏
     * （破坏者为空）一律取消；未被取消才标记区块为脏。
     *
     * <p>创造模式玩家同样检查（不因 {@code isCreative()} 放行）。双箱任一半区
     * 被破坏都按规范化主键对应的记录判定，保证两侧保护一致。</p>
     */
    public void onBreak(BlockEvent.BreakEvent event) {
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        String typeId = typeIdFor(state);
        if (typeId == null) {
            return; // 非受支持容器，不参与保护
        }
        String dim = serverLevel.dimension().location().toString();
        StorageKey key = canonicalKey(serverLevel, dim, typeId, pos);
        StorageSavedData data = savedData(serverLevel);
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record != null) {
            Player player = event.getPlayer();
            if (player == null) {
                // 环境破坏（水冲、爆炸等）：有主仓储不可被环境破坏
                event.setCanceled(true);
                return;
            }
            StorageAccessService.AccessSnapshot snapshot =
                    new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
            if (!StorageServices.access().canBreak(player.getUUID(), snapshot)) {
                LOGGER.warn("storage: denied break of claimed storage at {} by {}",
                        pos, player.getName().getString());
                event.setCanceled(true);
                return;
            }
            StorageId storageId = new StorageId(
                    key.dimension(), key.adapterType(), key.location());
            if (!ThirdPartyServices.protectionHook()
                    .allows(player.getUUID(), storageId, ProtectionAction.BREAK)) {
                LOGGER.warn("storage: denied break of claimed storage at {} by {} (third-party protection)",
                        pos, player.getName().getString());
                event.setCanceled(true);
                return;
            }
        }
        markDirty(serverLevel, pos);
        // 双箱任一半区被破坏：下一 tick 检查另一半是否降级为单箱并迁移认领记录
        if (isDoubleHalf(state)) {
            pendingDoubleChecks.addLast(new PendingDoubleCheck(
                    serverLevel, pos,
                    pos.relative(ChestBlock.getConnectedDirection(state))));
        }
        refreshQueued = true;
    }

    /**
     * 爆炸影响列表（Task 11）：把有主仓储的物理部件从受影响方块中移除，
     * 但不取消爆炸本身——其余方块照常破坏。
     */
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        protectAffectedBlocks(serverLevel, event.getAffectedBlocks());
    }

    /**
     * 从受影响方块列表中移除有主仓储的物理部件；返回被保护的方块数。
     * 公开供 GameTest 直接构造受影响列表调用。
     */
    public int protectAffectedBlocks(ServerLevel level, List<BlockPos> affected) {
        if (affected == null || affected.isEmpty()) {
            return 0;
        }
        String dim = level.dimension().location().toString();
        StorageSavedData data = savedData(level);
        int protectedCount = 0;
        Iterator<BlockPos> it = affected.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (isProtectedStorage(level, dim, data, pos)) {
                it.remove();
                protectedCount++;
            }
        }
        if (protectedCount > 0) {
            LOGGER.info("storage: protected {} storage block(s) from explosion", protectedCount);
        }
        return protectedCount;
    }

    /**
     * 活塞事件（Task 11）：推/拉路径上存在有主仓储时取消移动，防止自动化
     * 把认领容器搬走。
     *
     * <p>优先使用 {@link PistonStructureResolver} 拿到实际移动的方块列表
     * （推与拉都覆盖）；结构解析不可用时回退为沿移动方向线性扫描最多 12 格。</p>
     */
    public void onPistonPre(PistonEvent.Pre event) {
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof ServerLevel serverLevel)) {
            return;
        }
        String dim = serverLevel.dimension().location().toString();
        StorageSavedData data = savedData(serverLevel);

        PistonStructureResolver resolver = event.getStructureHelper();
        if (resolver != null) {
            try {
                if (resolver.resolve()) {
                    for (BlockPos pos : resolver.getToPush()) {
                        if (isProtectedStorage(serverLevel, dim, data, pos)) {
                            event.setCanceled(true);
                            return;
                        }
                    }
                    for (BlockPos pos : resolver.getToDestroy()) {
                        if (isProtectedStorage(serverLevel, dim, data, pos)) {
                            event.setCanceled(true);
                            return;
                        }
                    }
                    return; // 结构解析成功且无有主仓储 → 放行
                }
            } catch (RuntimeException e) {
                LOGGER.warn("storage: piston structure resolution failed at {}",
                        event.getPos(), e);
            }
        }
        // 回退：沿移动方向线性扫描最多 12 格（推取 facing，拉取反方向）
        Direction scanDir = event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND
                ? event.getDirection()
                : event.getDirection().getOpposite();
        BlockPos cursor = event.getPos();
        for (int i = 0; i < 13; i++) {
            cursor = cursor.relative(scanDir);
            if (isProtectedStorage(serverLevel, dim, data, cursor)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** 位置上是否是有主仓储的物理部件（typeId 非 null 且存档中有记录）。 */
    private boolean isProtectedStorage(ServerLevel level, String dim,
                                       StorageSavedData data, BlockPos pos) {
        String typeId = typeIdFor(level.getBlockState(pos));
        if (typeId == null) {
            return false;
        }
        StorageKey key = canonicalKey(level, dim, typeId, pos);
        return data.getRecord(key).isPresent();
    }

    /** 处理积压的延迟认领（每 tick 全部处理，队列天然有界于放置频率）。 */
    private void processPendingClaims() {
        while (!pendingClaims.isEmpty()) {
            PendingClaim pending = pendingClaims.removeFirst();
            try {
                claim(pending.level(), pending.pos(), pending.ownerId(), pending.ownerName());
            } catch (RuntimeException e) {
                LOGGER.warn("storage: deferred claim failed at {} for {}",
                        pending.pos(), pending.ownerId(), e);
            }
        }
    }

    /**
     * 处理积压的双箱降级检查（每 tick 全部处理，队列天然有界于破坏频率）。
     * 公开供 GameTest 直接调用。
     */
    public void processPendingDoubleChecks() {
        while (!pendingDoubleChecks.isEmpty()) {
            PendingDoubleCheck pending = pendingDoubleChecks.removeFirst();
            try {
                handleDoubleCheck(pending);
            } catch (RuntimeException e) {
                LOGGER.warn("storage: double-chest degradation check failed at {}",
                        pending.otherPos(), e);
            }
        }
    }

    /**
     * 双箱破坏后半区降级为单箱：把双箱认领记录迁移到剩余半区的单箱键，
     * 完整继承 owner/ACL（会话 #29：修复「双箱破坏一半剩下箱子失去认领」）。
     *
     * <p>上一 tick 的破坏事件已在 {@code onBreak} 入队，此刻另一半已由原版
     * 邻居更新改为 {@code SINGLE}，状态稳定。不匹配（另一半也被破坏 / 仍双箱
     * 成员 / 非箱类）一律跳过，双箱记录留待 {@code evaluate()} 幽灵清理。</p>
     */
    private void handleDoubleCheck(PendingDoubleCheck pending) {
        ServerLevel level = pending.level();
        BlockPos remaining = pending.otherPos();
        if (!level.isLoaded(remaining)) {
            return;
        }
        BlockState state = level.getBlockState(remaining);
        if (isDoubleHalf(state)) {
            return; // 仍为双箱成员（破坏被取消等），记录本就可用
        }
        String family = chestFamily(state);
        if (family == null) {
            return; // 非箱类（另一半也被破坏等）
        }
        String doubleType = "chest".equals(family) ? DOUBLE_CHEST : TRAPPED_CHEST;
        String singleType = "chest".equals(family) ? SINGLE_CHEST : TRAPPED_CHEST;
        String dim = level.dimension().location().toString();
        BlockPos primary = ChestPairSupport.primaryOf(pending.brokenPos(), remaining);
        StorageKey doubleKey = StorageKey.of(
                dim, doubleType, AbstractContainerAdapter.toLocation(primary));
        StorageKey singleKey = StorageKey.of(
                dim, singleType, AbstractContainerAdapter.toLocation(remaining));
        if (savedData(level).migrateRecord(doubleKey, singleKey,
                remaining.getX() >> 4, remaining.getZ() >> 4)) {
            markDirty(level, remaining);
            LOGGER.info("storage: double {} degraded at {}, claim migrated {} -> {}",
                    family, remaining, doubleKey, singleKey);
        }
        // migrateRecord 返回 false：无记录 / 主半区幸存且单双同键（陷阱箱）/
        // 目标单箱键已存在 → 保守跳过，不覆盖既有记录
    }

    /**
     * 广播仓储列表失效通知（会话 #29）：先重置所有在线玩家的查询限频缓存，
     * 再发送 {@link StorageChangedPacket}，开着的仓储/交易所屏幕收到后以当前
     * 条件重查、天然走全量扫描。空玩家列表为安全空操作（GameTest 环境无在线
     * 浏览者）。公开供 GameTest 直接调用。
     */
    public void flushQueuedRefresh(MinecraftServer server) {
        if (!refreshQueued) {
            return;
        }
        refreshQueued = false;
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            discovery.resetQueryState(player.getUUID());
            PacketDistributor.sendToPlayer(player, new StorageChangedPacket());
        }
    }

    /** 是否有待广播的刷新通知（GameTest 断言用）。 */
    boolean hasPendingRefresh() {
        return refreshQueued;
    }

    // ==================== 认领核心 ====================

    /**
     * 同步认领位置上的容器。双箱半区归一为主半区；旧半区单箱/双箱记录在
     * 同 owner 时迁移到主键（ACL 不变），异 owner 返回 {@link ClaimResult#CONFLICT}。
     */
    public ClaimResult claim(ServerLevel level, BlockPos pos, UUID ownerId, String ownerName) {
        BlockState state = level.getBlockState(pos);
        String typeId = typeIdFor(state);
        if (typeId == null) {
            return ClaimResult.NOT_SUPPORTED;
        }
        String dim = level.dimension().location().toString();
        StorageKey key = canonicalKey(level, dim, typeId, pos);
        StorageSavedData data = savedData(level);

        StorageRecord existing = data.getRecord(key).orElse(null);
        if (existing != null) {
            return existing.ownerId().equals(ownerId)
                    ? ClaimResult.ALREADY_CLAIMED : ClaimResult.CONFLICT;
        }

        // 双箱：另一半半区可能以单箱身份（或旧主键）已认领 → 迁移到规范化主键
        if (isDoubleHalf(state)) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            String family = chestFamily(state);
            if (level.isLoaded(other) && family != null && family.equals(chestFamily(level.getBlockState(other)))) {
                for (String legacyType : chestTypeIds(family)) {
                    StorageKey legacyKey = StorageKey.of(
                            dim, legacyType, AbstractContainerAdapter.toLocation(other));
                    if (legacyKey.equals(key)) {
                        continue; // 与目标键一致，existing 分支已处理
                    }
                    StorageRecord legacy = data.getRecord(legacyKey).orElse(null);
                    if (legacy != null) {
                        if (!legacy.ownerId().equals(ownerId)) {
                            return ClaimResult.CONFLICT;
                        }
                        BlockPos canonical = AbstractContainerAdapter.parsePos(key.location());
                        if (canonical == null) {
                            return ClaimResult.NOT_SUPPORTED;
                        }
                        if (data.migrateRecord(legacyKey, key,
                                canonical.getX() >> 4, canonical.getZ() >> 4)) {
                            markDirty(level, pos);
                            return ClaimResult.MIGRATED;
                        }
                        return ClaimResult.CONFLICT; // 目标键已被占用（异常态），保守拒绝
                    }
                }
            }
        }

        BlockPos canonical = AbstractContainerAdapter.parsePos(key.location());
        if (canonical == null) {
            return ClaimResult.NOT_SUPPORTED;
        }
        StorageRecord record = StorageRecord.create(
                ownerId, ownerName, System.currentTimeMillis());
        if (!data.claim(key, record, canonical.getX() >> 4, canonical.getZ() >> 4)) {
            return ClaimResult.CONFLICT; // 并发下已被认领
        }
        markDirty(level, pos);
        return ClaimResult.CLAIMED;
    }

    /**
     * 放置前冲突判定：放置箱类方块且会与任一已认领邻接箱形成双箱时，
     * 若邻接箱被其他 owner 认领则返回 {@code false}（调用方应取消放置）。
     *
     * <p>保守策略：对 4 个水平邻接的同族箱类全部检查，宁可拒绝也不允许
     * 两个不同所有者的箱子静默合并。木桶、凝聚器不配对，恒放行。</p>
     */
    public boolean canPlace(ServerLevel level, BlockPos pos, UUID ownerId) {
        BlockState state = level.getBlockState(pos);
        String family = chestFamily(state);
        if (family == null) {
            return true; // 木桶/凝聚器等不配对
        }
        String dim = level.dimension().location().toString();
        StorageSavedData data = savedData(level);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(dir);
            if (!level.isLoaded(neighbor)) {
                continue;
            }
            if (!family.equals(chestFamily(level.getBlockState(neighbor)))) {
                continue;
            }
            for (String typeId : chestTypeIds(family)) {
                StorageKey key = StorageKey.of(
                        dim, typeId, AbstractContainerAdapter.toLocation(neighbor));
                StorageRecord record = data.getRecord(key).orElse(null);
                if (record != null && !record.ownerId().equals(ownerId)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ==================== 工具 ====================

    /** 规范化主键：双箱半区归一为主半区，其余交由注册表 canonicalize。 */
    private StorageKey canonicalKey(ServerLevel level, String dim, String typeId, BlockPos pos) {
        return canonicalKey(registry, level, dim, typeId, pos);
    }

    /**
     * 静态版规范化主键（供本类事件与 {@link StorageAutomationGuard} 共用）：
     * 双箱半区归一为主半区，其余交由注册表 canonicalize。
     */
    static StorageKey canonicalKey(StorageAdapterRegistryImpl registry,
                                   ServerLevel level, String dim, String typeId, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isDoubleHalf(state)) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            String family = chestFamily(state);
            if (level.isLoaded(other) && family != null
                    && family.equals(chestFamily(level.getBlockState(other)))) {
                return StorageKey.of(dim, typeId,
                        AbstractContainerAdapter.toLocation(
                                ChestPairSupport.primaryOf(pos, other)));
            }
        }
        return registry.canonicalize(
                StorageKey.of(dim, typeId, AbstractContainerAdapter.toLocation(pos)));
    }

    private void markDirty(ServerLevel level, BlockPos pos) {
        discovery.markChunkDirty(
                level.dimension().location().toString(),
                pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** 当前服务端的 StorageSavedData（包级可见，供自动化守卫共用）。 */
    static StorageSavedData savedData(Level level) {
        MinecraftServer server = level.getServer();
        return server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    /** 方块状态对应的适配器 typeId；不受支持返回 null（包级可见，供自动化守卫共用）。 */
    static String typeIdFor(BlockState state) {
        if (state.getBlock() == Blocks.CHEST) {
            return isDoubleHalf(state) ? DOUBLE_CHEST : SINGLE_CHEST;
        }
        if (state.getBlock() instanceof TrappedChestBlock) {
            return TRAPPED_CHEST;
        }
        if (state.getBlock() == Blocks.BARREL) {
            return BARREL;
        }
        if (state.getBlock() == ModBlocks.CONDENSER.get()) {
            return CONDENSER;
        }
        if (state.getBlock() instanceof ShulkerBoxBlock shulker) {
            return VanillaShulkerBoxAdapter.typeIdFor(shulker.getColor());
        }
        return null;
    }

    /** 箱类家族：普通箱 / 陷阱箱；其他方块返回 null。 */
    private static String chestFamily(BlockState state) {
        if (state.getBlock() == Blocks.CHEST) {
            return "chest";
        }
        if (state.getBlock() instanceof TrappedChestBlock) {
            return "trapped";
        }
        return null;
    }

    /** 家族内可能的认领 typeId 集合（覆盖单箱与双箱记录）。 */
    private static List<String> chestTypeIds(String family) {
        return "chest".equals(family)
                ? List.of(SINGLE_CHEST, DOUBLE_CHEST)
                : List.of(TRAPPED_CHEST);
    }

    /** 是否为双箱成员半区（普通箱与陷阱箱共用 TYPE 属性）。 */
    private static boolean isDoubleHalf(BlockState state) {
        return state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
    }

    /** 待认领任务：位置 + 认领人。 */
    private record PendingClaim(ServerLevel level, BlockPos pos,
                                UUID ownerId, String ownerName) {
    }

    /** 双箱破坏降级检查任务：破坏半区位置 + 另一半（可能幸存）位置。 */
    private record PendingDoubleCheck(ServerLevel level, BlockPos brokenPos,
                                      BlockPos otherPos) {
    }
}
