package com.pokeemc.storage.discovery;

import com.mojang.logging.LogUtils;
import com.pokeemc.storage.StorageAccessService;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import com.pokeemc.storage.adapter.StorageHandleExt;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageQuery;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * 仓储发现服务：范围查询、预算保护与增量刷新。
 *
 * <p>查询遵循计划文档 4.2「发现与扫描」的规则：</p>
 * <ul>
 *   <li>只读取已加载区块中的 block entity，绝不强制加载区块；未加载区块中的仓储
 *       仍以元数据呈现但标记为扫描不完整。</li>
 *   <li>每 tick（后台刷新）、每查询（扫描预算）、结果数量与查询频率均有硬预算；
 *       限频/队列满时延后并告警，不阻塞主线程。</li>
 *   <li>结果先按 VIEW 或可执行动作过滤，再按距离、规范 ID 稳定排序并截断。</li>
 * </ul>
 */
public final class StorageDiscoveryService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 允许出现在仓储列表/浏览器中的适配器类型（仅原版容器；模组容器不识别）。 */
    static final Set<String> LISTABLE_STORAGE_TYPES = Set.of(
            "vanilla_chest", "vanilla_double_chest", "vanilla_trapped_chest", "vanilla_barrel",
            VanillaEnderChestAdapter.TYPE_ID);

    private final StorageAdapterRegistryImpl registry;
    private final StorageAccessService access;
    private final StorageConfig config;
    private final Predicate<UUID> adminChecker;

    /** 每个 actor 最近一次查询的状态与结果缓存。 */
    private final Map<UUID, PlayerQueryState> states = new LinkedHashMap<>();
    /** 查询限频：actor -> 上次查询时的世界 tick。 */
    private final Map<UUID, Long> lastQueryTick = new LinkedHashMap<>();
    /** 脏区块去重集合（容量上限，防止无限增长）。 */
    private final BoundedDedupeSet<ChunkKey> dirtyChunks;
    /** 后台增量刷新队列（容量上限，满时丢弃最旧任务并告警）。 */
    private final ArrayDeque<RefreshTask> refreshQueue = new ArrayDeque<>();

    public StorageDiscoveryService(StorageAdapterRegistryImpl registry,
                                   StorageAccessService access,
                                   StorageConfig config,
                                   Predicate<UUID> adminChecker) {
        this.registry = registry;
        this.access = access;
        this.config = config;
        this.adminChecker = adminChecker;
        this.dirtyChunks = new BoundedDedupeSet<>(config.dirtyDedupeCapacity());
    }

    // ==================== 同步查询 ====================

    /**
     * 服务端主线程执行的同步查询。限频期间返回上次结果（无则空列表），
     * 从不抛出也不会触发区块加载。
     */
    public List<StorageDescriptor> querySync(StorageQuery query) {
        UUID actorId = query.actorId();
        long now = currentTick();
        Long last = lastQueryTick.get(actorId);
        if (last != null && rateLimited(last, now, config.queryCooldownTicks())) {
            PlayerQueryState cached = states.get(actorId);
            if (cached != null) {
                return cached.results();
            }
            return List.of();
        }
        lastQueryTick.put(actorId, now);

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return List.of();
        }
        ServerLevel level = resolveLevel(server, query.dimension());
        if (level == null) {
            return List.of();
        }
        StorageSavedData data = savedData(server);
        int radius = config.clampRadius(query.radius(), adminChecker.test(actorId));
        int maxResults = config.clampMaxResults(query.maxResults());

        List<Candidate> hits = new ArrayList<>();
        ScanBudget budget = new ScanBudget(config.maxScannedPerQuery());
        for (StorageKey key : candidateKeys(data, query, radius)) {
            evaluate(level, data, key, query, radius, budget, hits);
        }
        // 玩家个人末影箱：虚拟仓储，始终按本人列出（不依赖世界方块）
        addEnderChestCandidate(data, query, hits);
        hits.sort(stableComparator(query.sort()));

        List<StorageDescriptor> results = toDescriptors(hits, maxResults);
        states.put(actorId, new PlayerQueryState(
                query.dimension(), query.centerX(), query.centerZ(), radius, results));
        return results;
    }

    /** 最近一次查询的结果缓存；从未查询过返回空列表。 */
    public List<StorageDescriptor> cached(UUID actorId) {
        PlayerQueryState state = states.get(actorId);
        return state == null ? List.of() : state.results();
    }

    /**
     * 玩家移动超过阈值、切换维度或修改范围时返回 {@code true}，
     * 供调用方决定是否触发增量刷新。
     */
    public boolean shouldRefresh(UUID actorId, String dimension, int centerX, int centerZ, int radius) {
        PlayerQueryState state = states.get(actorId);
        if (state == null) {
            return true;
        }
        if (!state.dimension().equals(dimension) || state.radius() != radius) {
            return true;
        }
        long dx = state.centerX() - (long) centerX;
        long dz = state.centerZ() - (long) centerZ;
        long threshold = config.moveRefreshThresholdBlocks();
        return dx * dx + dz * dz > threshold * threshold;
    }

    // ==================== 后台增量刷新 ====================

    /** 将一次范围扫描排入后台队列，分片执行（每 tick 最多 maxChunksPerTick 个区块）。 */
    public void scheduleRefresh(UUID actorId, StorageQuery query) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        if (refreshQueue.size() >= config.refreshQueueCapacity()) {
            refreshQueue.removeFirst();
            LOGGER.warn("discovery: refresh queue full ({}), dropping oldest task", config.refreshQueueCapacity());
        }
        int radius = config.clampRadius(query.radius(), adminChecker.test(actorId));
        int maxResults = config.clampMaxResults(query.maxResults());
        List<ChunkKey> chunks = new ArrayList<>();
        StorageSavedData data = savedData(server);
        for (StorageKey key : candidateKeys(data, query, radius)) {
            BlockPos pos = AbstractContainerAdapter.parsePos(key.location());
            if (pos != null) {
                chunks.add(new ChunkKey(query.dimension(), pos.getX() >> 4, pos.getZ() >> 4));
            }
        }
        refreshQueue.addLast(new RefreshTask(actorId, query, radius, maxResults, chunks));
    }

    /** 每 tick 由服务端事件驱动：分片执行后台刷新任务。 */
    public void tick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || refreshQueue.isEmpty()) {
            return;
        }
        Iterator<RefreshTask> it = refreshQueue.iterator();
        while (it.hasNext()) {
            RefreshTask task = it.next();
            ServerLevel level = resolveLevel(server, task.query().dimension());
            if (level == null) {
                it.remove();
                continue;
            }
            StorageSavedData data = savedData(server);
            int budget = config.maxChunksPerTick();
            while (budget > 0 && !task.pendingChunks.isEmpty()) {
                ChunkKey chunk = task.pendingChunks.removeFirst();
                for (StorageKey key : data.keysInChunk(chunk.dimension(), chunk.chunkX(), chunk.chunkZ())) {
                    evaluate(level, data, key, task.query(), task.radius(), task.budget(), task.accumulated());
                }
                budget--;
            }
            if (task.pendingChunks.isEmpty()) {
                it.remove();
                task.accumulated().sort(stableComparator(task.query().sort()));
                List<StorageDescriptor> results = toDescriptors(task.accumulated(), task.maxResults());
                states.put(task.actorId(), new PlayerQueryState(task.query().dimension(),
                        task.query().centerX(), task.query().centerZ(), task.radius(), results));
            }
        }
    }

    // ==================== 脏区块标脏 ====================

    /**
     * 标记区块内的仓储已变化（放置/破坏容器时调用）。返回 {@code true} 表示新接受；
     * 重复标脏返回 {@code false}（去重）；集合已满时拒绝并告警。
     */
    public boolean markChunkDirty(String dimension, int chunkX, int chunkZ) {
        if (dirtyChunks.size() >= dirtyChunks.capacity()) {
            LOGGER.warn("discovery: dirty chunk set full ({}), dropping {}/{}", dirtyChunks.capacity(), dimension, chunkX + "," + chunkZ);
            return false;
        }
        return dirtyChunks.add(new ChunkKey(dimension, chunkX, chunkZ));
    }

    /** 当前脏区块数量（测试与告警用）。 */
    public int dirtyChunkCount() {
        return dirtyChunks.size();
    }

    // ==================== 候选评估 ====================

    /** 以玩家位置所在区块为圆心、半径为边界的候选键集。 */
    private Set<StorageKey> candidateKeys(StorageSavedData data, StorageQuery query, int radius) {
        if (data == null) {
            return Set.of();
        }
        int centerChunkX = query.centerX() >> 4;
        int centerChunkZ = query.centerZ() >> 4;
        int span = (radius >> 4) + 1;
        return data.keysInChunks(query.dimension(),
                centerChunkX - span, centerChunkX + span,
                centerChunkZ - span, centerChunkZ + span);
    }

    /** 评估单个候选键：半径/权限/搜索/预算过滤后追加到 hits。 */
    private void evaluate(ServerLevel level, StorageSavedData data, StorageKey key,
                          StorageQuery query, int radius, ScanBudget budget, List<Candidate> hits) {
        BlockPos pos = AbstractContainerAdapter.parsePos(key.location());
        if (pos == null) {
            return;
        }
        long dx = pos.getX() - (long) query.centerX();
        long dz = pos.getZ() - (long) query.centerZ();
        if (!withinRadius(dx, dz, radius)) {
            return;
        }
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            return;
        }
        StorageAdapter adapter = registry.byTypeId(key.adapterType()).orElse(null);
        if (adapter == null) {
            return;
        }
        // 只识别原版储存容器：凝聚器（poketrade_condenser）等模组容器不再出现在列表里
        if (!LISTABLE_STORAGE_TYPES.contains(key.adapterType())) {
            return;
        }
        // 幽灵记录清理：加载区块中容器已不存在/类型不匹配时删除记录并跳过，
        // 否则被破坏的箱子会一直出现在仓储列表里（"没摆箱子也有箱子"）。
        // 未加载区块保持元数据呈现，绝不强制加载。
        if (level.isLoaded(pos)
                && !adapter.supports(new StorageAdapterContext(toStorageId(key)))) {
            if (data.deleteStorage(key)) {
                LOGGER.info("discovery: pruned stale storage record {} (block gone)", key.asString());
            }
            return;
        }
        UUID actorId = query.actorId();
        if (!visibleTo(actorId, record)) {
            return;
        }
        if (!passesFilter(query.filter(), record, adapter.capabilities(), actorId)) {
            return;
        }
        String search = query.searchText();
        if (search != null && !search.isBlank()
                && !containsIgnoreCase(record.displayName(), search)
                && !containsIgnoreCase(record.ownerName(), search)) {
            return;
        }
        int distance = (int) Math.sqrt(dx * dx + dz * dz);
        if (!budget.consume()) {
            // 预算耗尽：剩余候选仅用元数据呈现，标记为未完整扫描（不呈现为"没有仓储"）。
            hits.add(new Candidate(key, record, adapter, distance, 0, 0, false));
            return;
        }
        int slotCount = 0;
        int usedSlots = 0;
        boolean complete = false;
        if (level.isLoaded(pos)) {
            StorageAdapterContext context = new StorageAdapterContext(toStorageId(key));
            try (StorageHandle handle = adapter.open(context).orElse(null)) {
                if (handle instanceof StorageHandleExt ext) {
                    slotCount = ext.slotCount();
                    for (int i = 0; i < slotCount; i++) {
                        if (ext.itemId(i) != null) {
                            usedSlots++;
                        }
                    }
                    complete = true;
                }
            } catch (RuntimeException ignored) {
                // 容器打开失败：以元数据呈现并标记不完整。
            }
        }
        hits.add(new Candidate(key, record, adapter, distance, slotCount, usedSlots, complete));
    }

    /** 查询玩家个人末影箱：无记录则自动登记（owner=本人），再按权限/过滤加入候选。 */
    private void addEnderChestCandidate(StorageSavedData data, StorageQuery query,
                                        List<Candidate> hits) {
        UUID actorId = query.actorId();
        StorageKey key = StorageKey.of("minecraft:overworld",
                VanillaEnderChestAdapter.TYPE_ID, VanillaEnderChestAdapter.locationOf(actorId));
        StorageAdapter adapter = registry.byTypeId(key.adapterType()).orElse(null);
        if (adapter == null) {
            return;
        }
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            record = StorageRecord.create(actorId, "末影箱", System.currentTimeMillis());
            if (!data.claim(key, record, 0, 0)) {
                record = data.getRecord(key).orElse(null);
            }
            if (record == null) {
                return;
            }
        }
        if (!visibleTo(actorId, record)
                || !passesFilter(query.filter(), record, adapter.capabilities(), actorId)) {
            return;
        }
        // 末影箱容量恒为 27；玩家离线时无法打开，按元数据呈现（27 格、0 占用、不完整）
        int slotCount = 27;
        int usedSlots = 0;
        boolean complete = false;
        try (StorageHandle handle = adapter.open(
                new StorageAdapterContext(toStorageId(key))).orElse(null)) {
            if (handle instanceof StorageHandleExt ext) {
                slotCount = ext.slotCount();
                for (int i = 0; i < slotCount; i++) {
                    if (ext.itemId(i) != null) {
                        usedSlots++;
                    }
                }
                complete = true;
            }
        } catch (RuntimeException ignored) {
            // 打开失败：以元数据呈现并标记不完整
        }
        hits.add(new Candidate(key, record, adapter, 0, slotCount, usedSlots, complete));
    }

    /** 结果按权限与可见性过滤：VIEW 或任一可执行动作。 */
    private boolean visibleTo(UUID actorId, StorageRecord record) {
        StorageAccessService.AccessSnapshot snapshot =
                new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
        return access.canView(actorId, snapshot)
                || access.canDeposit(actorId, snapshot)
                || access.canWithdraw(actorId, snapshot)
                || access.canSell(actorId, snapshot)
                || access.canManage(actorId, snapshot);
    }

    private boolean passesFilter(StorageQuery.Filter filter, StorageRecord record,
                                 Set<StorageCapability> capabilities, UUID actorId) {
        StorageAccessService.AccessSnapshot snapshot =
                new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
        return switch (filter) {
            case VIEWABLE -> access.canView(actorId, snapshot);
            case DEPOSITABLE -> access.canDeposit(actorId, snapshot);
            case WITHDRAWABLE -> access.canWithdraw(actorId, snapshot);
            case SELLABLE -> access.canSell(actorId, snapshot)
                    && capabilities.contains(StorageCapability.SELL_SOURCE);
            case OWNED -> record.ownerId().equals(actorId) || access.canManage(actorId, snapshot);
            case MANAGEABLE -> access.canManage(actorId, snapshot);
        };
    }

    // ==================== 结果构造与排序 ====================

    private List<StorageDescriptor> toDescriptors(List<Candidate> hits, int maxResults) {
        int limit = Math.min(maxResults, hits.size());
        List<StorageDescriptor> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Candidate c = hits.get(i);
            out.add(new StorageDescriptor(toStorageId(c.key()), c.record().displayName(),
                    c.distance(), true, c.record().ownerId(), c.adapter().capabilities(),
                    c.slotCount(), c.usedSlots(), c.record().revision(), c.scanComplete()));
        }
        return out;
    }

    /** 距离 → 规范 ID 的稳定排序（tie-break 保证结果次序确定性）。 */
    static Comparator<Candidate> stableComparator(StorageQuery.Sort sort) {
        Comparator<Candidate> primary = switch (sort) {
            case NAME -> Comparator.comparing((Candidate c) -> c.record().displayName());
            case FREE_SLOTS -> Comparator
                    .comparingInt((Candidate c) -> c.slotCount() - c.usedSlots())
                    .reversed();
            case RECENTLY_UPDATED -> Comparator
                    .comparingLong((Candidate c) -> c.record().updatedAtEpochMillis())
                    .reversed();
            default -> Comparator.comparingInt(Candidate::distance);
        };
        return primary.thenComparing(c -> c.key().asString());
    }

    private static StorageId toStorageId(StorageKey key) {
        return new StorageId(key.dimension(), key.adapterType(), key.location());
    }

    // ==================== 纯逻辑（JVM 可测） ====================

    /** 欧氏距离平方是否落在半径内。 */
    public static boolean withinRadius(long dx, long dz, long radius) {
        return dx * dx + dz * dz <= radius * radius;
    }

    /** 查询频率判定：距上次查询不足 cooldownTicks 视为限频。 */
    public static boolean rateLimited(long lastTick, long nowTick, int cooldownTicks) {
        return nowTick - lastTick < cooldownTicks;
    }

    private static boolean containsIgnoreCase(String text, String fragment) {
        return text != null && text.toLowerCase(Locale.ROOT)
                .contains(fragment.toLowerCase(Locale.ROOT));
    }

    /** 服务端当前 tick（供限频与状态使用）。 */
    private static long currentTick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? 0L : server.getTickCount();
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(dimension));
            return server.getLevel(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static StorageSavedData savedData(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    // ==================== 内部类型 ====================

    /** 候选命中（评估结果）。 */
    record Candidate(StorageKey key, StorageRecord record, StorageAdapter adapter,
                     int distance, int slotCount, int usedSlots, boolean scanComplete) {
    }

    /** 单次扫描预算计数器：达到上限后 consume() 返回 false。 */
    static final class ScanBudget {
        private final int limit;
        private int consumed;

        ScanBudget(int limit) {
            this.limit = limit;
        }

        boolean consume() {
            if (consumed >= limit) {
                return false;
            }
            consumed++;
            return true;
        }
    }

    /** 每查询状态：位置 + 半径 + 结果缓存（用于限频返回与增量刷新判定）。 */
    record PlayerQueryState(String dimension, int centerX, int centerZ, int radius,
                            List<StorageDescriptor> results) {
    }

    /** 区块标脏键。 */
    record ChunkKey(String dimension, int chunkX, int chunkZ) {
    }

    /** 容量有界的去重集合：重复元素返回 false，满时拒绝新增。 */
    static final class BoundedDedupeSet<E> {
        private final int capacity;
        private final LinkedHashSet<E> set = new LinkedHashSet<>();

        BoundedDedupeSet(int capacity) {
            this.capacity = capacity;
        }

        boolean add(E element) {
            if (set.contains(element)) {
                return false;
            }
            if (set.size() >= capacity) {
                return false;
            }
            set.add(element);
            return true;
        }

        int size() {
            return set.size();
        }

        int capacity() {
            return capacity;
        }
    }

    /** 后台增量刷新任务：待扫描区块分片执行。 */
    static final class RefreshTask {
        private final UUID actorId;
        private final StorageQuery query;
        private final int radius;
        private final int maxResults;
        private final List<ChunkKey> pendingChunks;
        private final List<Candidate> accumulated = new ArrayList<>();
        private final ScanBudget budget = new ScanBudget(Integer.MAX_VALUE);

        RefreshTask(UUID actorId, StorageQuery query, int radius, int maxResults,
                    List<ChunkKey> pendingChunks) {
            this.actorId = actorId;
            this.query = query;
            this.radius = radius;
            this.maxResults = maxResults;
            this.pendingChunks = pendingChunks;
        }

        UUID actorId() {
            return actorId;
        }

        StorageQuery query() {
            return query;
        }

        int radius() {
            return radius;
        }

        int maxResults() {
            return maxResults;
        }

        List<ChunkKey> pendingChunks() {
            return pendingChunks;
        }

        List<Candidate> accumulated() {
            return accumulated;
        }

        ScanBudget budget() {
            return budget;
        }
    }
}
