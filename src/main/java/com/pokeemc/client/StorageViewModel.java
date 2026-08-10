package com.pokeemc.client;

import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageSnapshot;
import com.pokeemc.storage.StorageGrant;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.StoragePermissionSet;
import com.pokeemc.storage.StoragePrincipal;
import com.pokeemc.storage.StorageTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 仓储浏览的客户端视图模型（纯 JVM，可脱离 Minecraft 直接测试）。
 *
 * <p>持有浏览所需的全部<b>派生状态</b>：仓储列表、搜索、排序、权限过滤、
 * 范围校验、窄屏收起、选中仓储的快照与权限降级，以及模板应用差异（Task 10 UI）。
 * 本类<b>不持有</b>任何 Minecraft/NeoForge/Pixelmon 类型，服务端/网络层数据
 * 由屏幕（{@link StorageBrowserScreen}）通过传输抽象注入。</p>
 *
 * <p>权限降级语义：</p>
 * <ul>
 *   <li>无 {@link StoragePermission#VIEW} 时 {@link #visibleSlots()} 返回空（槽位内容不泄漏）；</li>
 *   <li>无 {@link StoragePermission#MANAGE} 时 {@link #visibleGrants()} 返回空（私有 ACL 详情不泄漏）；</li>
 *   <li>服务端在菜单数据里已经做过同样的过滤，本类是客户端侧的二次防御。</li>
 * </ul>
 */
public final class StorageViewModel {

    // —— 范围 ——
    public static final int RADIUS_PRESET_1 = 1;
    public static final int RADIUS_PRESET_32 = 32;
    public static final int RADIUS_PRESET_128 = 128;
    public static final int DEFAULT_RADIUS = RADIUS_PRESET_32;
    /** 客户端展示上限（服务端扫描预算上限需同步 ≥648，见 StorageConfig / QueryStoragesPacket）。 */
    public static final int MAX_RADIUS = 648;

    // —— 排序 ——
    public enum SortMode {
        /** 距离升序 */
        DISTANCE,
        /** 显示名（忽略大小写）升序 */
        NAME,
        /** 空位最多优先（slotCount - usedSlots 降序） */
        FREE_SLOTS,
        /** 最近更新优先（revision 降序） */
        RECENTLY_UPDATED,
        /** [CHANGED] 会话 #21-E：放置时间升序（旧→新，createdAtEpochMillis） */
        CREATED_ASC,
        /** [CHANGED] 会话 #21-E：放置时间降序（新→旧） */
        CREATED_DESC,
        /** [CHANGED] 会话 #21-E：标记正序（同类型按 ① ② ③；跨类型按适配器类型分组） */
        MARKER_ASC,
        /** [CHANGED] 会话 #21-E：标记倒序（同类型按 ③ ② ①） */
        MARKER_DESC,
        /** [NEW] 会话 #21-H 修订：物品总价值升序（便宜在前；价值 = 槽位数量×可出售单价之和） */
        VALUE_ASC,
        /** [NEW] 会话 #21-H 修订：物品总价值降序（值钱在前） */
        VALUE_DESC
    }

    // —— 权限过滤 ——
    public enum FilterMode {
        ALL(null),
        VIEW(StoragePermission.VIEW),
        DEPOSIT(StoragePermission.DEPOSIT),
        WITHDRAW(StoragePermission.WITHDRAW),
        SELL(StoragePermission.SELL),
        BREAK(StoragePermission.BREAK),
        MANAGE(StoragePermission.MANAGE);

        private final StoragePermission permission;

        FilterMode(StoragePermission permission) {
            this.permission = permission;
        }

        public StoragePermission permission() {
            return permission;
        }
    }

    // —— 模板应用差异（Task 10 UI） ——
    /** 单个主体在模板应用前后的权限差异。 */
    public record TemplateDiff(
            StoragePrincipal principal,
            Set<StoragePermission> added,
            Set<StoragePermission> removed) {
        public boolean hasChanges() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }

    /** 模板应用预览：目标授权表、逐主体差异、是否触发高风险确认。 */
    public record TemplatePreview(
            StorageTemplate template,
            boolean follow,
            Map<StoragePrincipal, StorageGrant> targetGrants,
            List<TemplateDiff> diffs,
            boolean highRisk) {

        public boolean hasChanges() {
            for (TemplateDiff diff : diffs) {
                if (diff.hasChanges()) {
                    return true;
                }
            }
            return false;
        }
    }

    // —— 状态 ——
    private List<StorageDescriptor> storages = List.of();
    private String searchText = "";
    private SortMode sortMode = SortMode.DISTANCE;
    private FilterMode filterMode = FilterMode.ALL;
    private int radius = DEFAULT_RADIUS;
    private boolean radiusOverLimit;
    private boolean narrowScreen;
    private boolean scanComplete = true;

    /** [CHANGED] 会话 #21-E：同类型仓储序号标记（storageId.asString() → 1,2,3…）。
     *  以放置时间升序为基准分配，末影箱（个人容器，唯一）不标号。UI 显示与标记排序共用。 */
    private Map<String, Integer> markersByStorage = Map.of();

    /** [NEW] 会话 #21-H 修订：每个仓储的物品总价值（storageId.asString() → 可出售总价）。
     *  由客户端以快照 + 全量出售价表计算后注入，供 {@link SortMode#VALUE_ASC}/{@link SortMode#VALUE_DESC}
     *  排序使用；未注入的仓储按 0 处理（无快照时垫底/置顶按方向）。 */
    private Map<String, Long> valueByStorage = Map.of();

    /** 每个仓储对当前玩家的有效权限（由服务端/菜单数据注入）。 */
    private Map<StorageId, EnumSet<StoragePermission>> permissionsByStorage = Map.of();

    // 选中仓储
    private StorageId selectedStorageId;
    private StorageDescriptor selectedDescriptor;
    private StorageSnapshot selectedSnapshot;
    private long selectedSnapshotRevision = -1L;
    private boolean snapshotStale = true;
    /** 最近一次快照增量更新中发生变化的槽位（用于 UI 高亮）。 */
    private Map<Integer, StorageItemSlot> lastChangedSlots = Map.of();

    /** 当前玩家的有效权限（针对选中仓储）。 */
    private EnumSet<StoragePermission> myPermissions = EnumSet.noneOf(StoragePermission.class);
    private boolean isOwner;
    /** 选中仓储的 ACL；null 表示无权查看（无 MANAGE 时 UI 不得展示详情）。 */
    private Map<StoragePrincipal, StorageGrant> grants;

    // 模板
    private List<StorageTemplate> templates = List.of();

    // ================= 范围校验 =================

    /** 钳制范围到合法区间 [1, max]；返回规范化后的值。 */
    public static int clampRadius(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }

    /** 设置范围；超出 {@link #MAX_RADIUS} 时标记 {@link #isRadiusOverLimit()} 并钳制。 */
    public void setRadius(int value) {
        radiusOverLimit = value > MAX_RADIUS;
        radius = clampRadius(value, MAX_RADIUS);
    }

    public int getRadius() {
        return radius;
    }

    /** 是否为预设按钮对应的值（1/32/128）。 */
    public boolean isRadiusPreset(int preset) {
        return radius == preset;
    }

    /** 是否超过客户端展示上限（UI 提示「超限」，服务端仍会按预算钳制）。 */
    public boolean isRadiusOverLimit() {
        return radiusOverLimit;
    }

    // ================= 仓储列表：搜索/排序/过滤 =================

    public void setStorages(List<StorageDescriptor> descriptors) {
        storages = descriptors == null ? List.of() : List.copyOf(descriptors);
    }

    public List<StorageDescriptor> getStorages() {
        return storages;
    }

    public void setSearchText(String text) {
        searchText = text == null ? "" : text;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSortMode(SortMode mode) {
        sortMode = mode == null ? SortMode.DISTANCE : mode;
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public void setFilterMode(FilterMode mode) {
        filterMode = mode == null ? FilterMode.ALL : mode;
    }

    public FilterMode getFilterMode() {
        return filterMode;
    }

    /** 注入每个仓储对当前玩家的有效权限（筛选用）。 */
    public void setPermissionsByStorage(Map<StorageId, EnumSet<StoragePermission>> permissions) {
        permissionsByStorage = permissions == null ? Map.of() : Map.copyOf(permissions);
    }

    /** 当前玩家对指定仓储是否拥有某权限（未注入时按 false 处理）。 */
    public boolean allowsOn(StorageId storageId, StoragePermission permission) {
        EnumSet<StoragePermission> set = permissionsByStorage.get(storageId);
        return set != null && set.contains(permission);
    }

    /** 应用搜索 + 权限过滤 + 稳定排序后的可见仓储列表。 */
    public List<StorageDescriptor> visibleStorages() {
        return filterAndSort(storages, searchText, filterMode, sortMode,
                permissionsByStorage, markersByStorage, valueByStorage);
    }

    /** [NEW] 会话 #21-H 修订：注入每个仓储的物品总价值表（VALUE 排序用）。 */
    public void setValueByStorage(Map<String, Long> values) {
        valueByStorage = values == null ? Map.of() : Map.copyOf(values);
    }

    /** [NEW] 会话 #21-H 修订：某仓储的当前总价值（未注入按 0）。 */
    public long valueOf(String storageKey) {
        return valueByStorage.getOrDefault(storageKey, 0L);
    }

    /** [CHANGED] 会话 #21-E：重新分配同类型序号标记（按给定有序列表，通常为放置时间升序）。 */
    public void recomputeMarkers(List<StorageDescriptor> ordered) {
        markersByStorage = assignMarkers(ordered);
    }

    /** [CHANGED] 会话 #21-E：当前标记表（storageId.asString() → 序号）；无标记的仓储缺席。 */
    public Map<String, Integer> getMarkers() {
        return markersByStorage;
    }

    /**
     * 静态可测入口：筛选 + 稳定排序。
     *
     * <p>排序使用稳定比较器（tie-break 用 {@code storageId} 序列，保证顺序确定）。</p>
     */
    public static List<StorageDescriptor> filterAndSort(
            List<StorageDescriptor> input,
            String searchText,
            FilterMode filterMode,
            SortMode sortMode,
            Map<StorageId, EnumSet<StoragePermission>> permissions) {
        return filterAndSort(input, searchText, filterMode, sortMode, permissions, Map.of());
    }

    /**
     * [CHANGED] 会话 #21-E：标记排序重载。{@code markers} 为同类型序号表
     * （storageId.asString() → 序号，缺省 0），仅 MARKER_ASC/MARKER_DESC 使用。
     * [NEW] 会话 #21-H 修订：价值排序重载。{@code values} 为 storageId.asString() → 物品总价值，
     * 仅 VALUE_ASC/VALUE_DESC 使用（缺省 0）。委托 7 参版本。
     */
    public static List<StorageDescriptor> filterAndSort(
            List<StorageDescriptor> input,
            String searchText,
            FilterMode filterMode,
            SortMode sortMode,
            Map<StorageId, EnumSet<StoragePermission>> permissions,
            Map<String, Integer> markers) {
        return filterAndSort(input, searchText, filterMode, sortMode, permissions, markers, Map.of());
    }

    /**
     * [NEW] 会话 #21-H 修订：完整签名——{@code values}（物品总价值表）供 VALUE 排序。
     */
    public static List<StorageDescriptor> filterAndSort(
            List<StorageDescriptor> input,
            String searchText,
            FilterMode filterMode,
            SortMode sortMode,
            Map<StorageId, EnumSet<StoragePermission>> permissions,
            Map<String, Integer> markers,
            Map<String, Long> values) {
        List<StorageDescriptor> result = new ArrayList<>();
        String query = searchText == null ? "" : searchText.trim().toLowerCase();
        for (StorageDescriptor d : input) {
            if (!matchesSearch(d, query)) {
                continue;
            }
            if (!matchesFilter(d, filterMode, permissions)) {
                continue;
            }
            result.add(d);
        }
        if (sortMode == SortMode.MARKER_ASC || sortMode == SortMode.MARKER_DESC) {
            // 标记排序：主键按适配器类型分组，次键按序号标记（无标记=0 在各自类型内最前）。
            // 倒序时整体反转（类型与标记皆反转）——「标记倒序」= 类型倒序 + 组内 ③②①。
            Comparator<StorageDescriptor> primary = Comparator
                    .comparing((StorageDescriptor d) -> d.storageId().adapterType())
                    .thenComparingInt(d -> markers.getOrDefault(d.storageId().asString(), 0));
            result.sort(sortMode == SortMode.MARKER_ASC ? primary : primary.reversed());
        } else {
            result.sort(comparator(sortMode, values));
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean matchesSearch(StorageDescriptor d, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return d.displayName().toLowerCase().contains(query)
                || d.storageId().asString().toLowerCase().contains(query)
                || (d.ownerName() != null
                        && d.ownerName().toLowerCase().contains(query));
    }

    /** 权限过滤；ALL 恒通过，其他模式要求当前玩家拥有对应权限。 */
    public static boolean matchesFilter(
            StorageDescriptor d,
            FilterMode mode,
            Map<StorageId, EnumSet<StoragePermission>> permissions) {
        if (mode == null || mode == FilterMode.ALL) {
            return true;
        }
        EnumSet<StoragePermission> set = permissions == null ? null : permissions.get(d.storageId());
        return set != null && set.contains(mode.permission());
    }

    /** 稳定排序比较器：主键 + storageId 序列 tie-break（无价值表，VALUE 按 0）。 */
    public static Comparator<StorageDescriptor> comparator(SortMode mode) {
        return comparator(mode, Map.of());
    }

    /**
     * [NEW] 会话 #21-H 修订：稳定排序比较器（带物品总价值表）。{@code values} 缺省按 0 处理。
     */
    public static Comparator<StorageDescriptor> comparator(SortMode mode, Map<String, Long> values) {
        Map<String, Long> val = values == null ? Map.of() : values;
        Comparator<StorageDescriptor> primary;
        switch (mode == null ? SortMode.DISTANCE : mode) {
            case NAME -> primary = Comparator.comparing(d -> d.displayName().toLowerCase());
            case FREE_SLOTS -> primary = Comparator.comparingInt(
                    (StorageDescriptor d) -> d.slotCount() - d.usedSlots()).reversed();
            case RECENTLY_UPDATED -> primary = Comparator.comparingLong(
                    StorageDescriptor::revision).reversed();
            // [CHANGED] 会话 #21-E：放置时间升/降序（createdAtEpochMillis）
            case CREATED_ASC -> primary = Comparator.comparingLong(StorageDescriptor::createdAtEpochMillis);
            case CREATED_DESC -> primary = Comparator.comparingLong(
                    StorageDescriptor::createdAtEpochMillis).reversed();
            // 标记排序的完整语义（跨类型分组 + 序号）在 filterAndSort 实现；
            // 此处作为独立比较器的兜底：仅按适配器类型分组（无序号维度）。
            case MARKER_ASC -> primary = Comparator.comparing(
                    (StorageDescriptor d) -> d.storageId().adapterType());
            case MARKER_DESC -> primary = Comparator.comparing(
                    (StorageDescriptor d) -> d.storageId().adapterType()).reversed();
            // [NEW] 会话 #21-H 修订：物品总价值升/降序（无快照/无价物品按 0）
            case VALUE_ASC -> primary = Comparator.comparingLong(
                    (StorageDescriptor d) -> val.getOrDefault(d.storageId().asString(), 0L));
            case VALUE_DESC -> primary = Comparator.comparingLong(
                    (StorageDescriptor d) -> val.getOrDefault(d.storageId().asString(), 0L)).reversed();
            case DISTANCE -> primary = Comparator.comparingInt(StorageDescriptor::distance);
            default -> throw new IllegalStateException("unknown sort mode " + mode);
        }
        return primary.thenComparing(d -> d.storageId().asString());
    }

    // ================= 同类型序号标记（会话 #21-E） =================

    /** 末影箱适配器类型 ID（个人容器，唯一，不标号）。 */
    static final String ENDER_CHEST_TYPE = "vanilla_ender_chest";

    /**
     * 按给定有序列表为同类型仓储分配序号标记（1,2,3…）：同适配器类型按列表顺序标号，
     * 末影箱排除（个人容器全局唯一）。纯函数，便于测试。
     */
    public static Map<String, Integer> assignMarkers(List<StorageDescriptor> ordered) {
        Map<String, Integer> markers = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (ordered == null) {
            return Collections.unmodifiableMap(markers);
        }
        for (StorageDescriptor d : ordered) {
            String type = d.storageId().adapterType();
            if (ENDER_CHEST_TYPE.equals(type)) {
                continue;
            }
            int n = counts.merge(type, 1, Integer::sum);
            markers.put(d.storageId().asString(), n);
        }
        return Collections.unmodifiableMap(markers);
    }

    /** 序号 → 显示标记（①-⑳ 全角圈号；超 20 回退 "#n"）。 */
    public static String markerLabel(int n) {
        if (n <= 0) {
            return "";
        }
        if (n <= 20) {
            return Character.toString((char) (0x2460 + n - 1));
        }
        return "#" + n;
    }

    /** 以放置时间升序（旧→新）为基准的稳定排列（标记分配基准 + CREATED_ASC 排序）。 */
    public static List<StorageDescriptor> byCreatedAsc(List<StorageDescriptor> input) {
        List<StorageDescriptor> out = new ArrayList<>(input);
        out.sort(Comparator.comparingLong(StorageDescriptor::createdAtEpochMillis)
                .thenComparing(d -> d.storageId().asString()));
        return Collections.unmodifiableList(out);
    }

    // ================= 窄屏收起 =================

    public void setNarrowScreen(boolean narrow) {
        narrowScreen = narrow;
    }

    public boolean isNarrowScreen() {
        return narrowScreen;
    }

    /** 是否展示右侧详情面板（宽屏时展示；窄屏收起）。 */
    public boolean showDetailsPanel() {
        return !narrowScreen;
    }

    // ================= 扫描状态 =================

    public void setScanComplete(boolean complete) {
        scanComplete = complete;
    }

    public boolean isScanComplete() {
        return scanComplete;
    }

    // ================= 选中仓储与快照 =================

    public void selectStorage(StorageId storageId, StorageDescriptor descriptor) {
        this.selectedStorageId = storageId;
        this.selectedDescriptor = descriptor;
        // 切换仓储后旧快照失效
        if (!Objects.equals(this.selectedSnapshot == null ? null : this.selectedSnapshot.storageId(), storageId)) {
            this.selectedSnapshot = null;
            this.selectedSnapshotRevision = -1L;
            this.lastChangedSlots = Map.of();
        }
        this.snapshotStale = true;
        this.myPermissions = permissionsByStorage.getOrDefault(
                storageId, EnumSet.noneOf(StoragePermission.class));
    }

    public void clearSelection() {
        selectedStorageId = null;
        selectedDescriptor = null;
        selectedSnapshot = null;
        selectedSnapshotRevision = -1L;
        snapshotStale = true;
        lastChangedSlots = Map.of();
        myPermissions = EnumSet.noneOf(StoragePermission.class);
        grants = null;
    }

    public StorageId getSelectedStorageId() {
        return selectedStorageId;
    }

    public StorageDescriptor getSelectedDescriptor() {
        return selectedDescriptor;
    }

    public StorageSnapshot getSelectedSnapshot() {
        return selectedSnapshot;
    }

    public long getSelectedSnapshotRevision() {
        return selectedSnapshotRevision;
    }

    public boolean isSnapshotStale() {
        return snapshotStale;
    }

    public void markSnapshotStale(boolean stale) {
        snapshotStale = stale;
    }

    /** 最近一次快照更新中变化的槽位（增量高亮）。 */
    public Map<Integer, StorageItemSlot> getLastChangedSlots() {
        return lastChangedSlots;
    }

    /**
     * 应用完整快照（增量语义）：记录相对旧快照变化的槽位，更新 revision。
     * 仅当快照对应选中仓储时才生效。
     */
    public boolean applySnapshot(StorageSnapshot snapshot) {
        if (snapshot == null || selectedStorageId == null
                || !snapshot.storageId().equals(selectedStorageId)) {
            return false;
        }
        if (snapshot.revision() < selectedSnapshotRevision && selectedSnapshot != null) {
            // 收到比当前更旧的快照，忽略（拒绝回退）
            return false;
        }
        lastChangedSlots = diffSlots(selectedSnapshot, snapshot);
        selectedSnapshot = snapshot;
        selectedSnapshotRevision = snapshot.revision();
        snapshotStale = false;
        return true;
    }

    /**
     * 应用批量快照更新（事务成功后服务端返回的 {@code updatedSnapshots}）。
     * 若包含选中仓储则增量更新之。
     */
    public boolean applySnapshotUpdates(Map<StorageId, StorageSnapshot> updatedSnapshots) {
        if (updatedSnapshots == null || selectedStorageId == null) {
            return false;
        }
        StorageSnapshot snap = updatedSnapshots.get(selectedStorageId);
        return snap != null && applySnapshot(snap);
    }

    /** 计算两个快照之间发生变化的槽位（旧快照为 null 时全部视为变化）。 */
    public static Map<Integer, StorageItemSlot> diffSlots(StorageSnapshot before, StorageSnapshot after) {
        if (before == null) {
            return after == null ? Map.of() : new LinkedHashMap<>(after.slots());
        }
        if (after == null) {
            return Map.of();
        }
        Map<Integer, StorageItemSlot> changed = new LinkedHashMap<>();
        for (Map.Entry<Integer, StorageItemSlot> entry : after.slots().entrySet()) {
            StorageItemSlot prev = before.slots().get(entry.getKey());
            if (prev == null || !prev.equals(entry.getValue())) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(changed);
    }

    // ================= 权限降级 =================

    /** 设置当前玩家对选中仓储的有效权限（由菜单数据注入）。 */
    public void setMyPermissions(EnumSet<StoragePermission> permissions, boolean owner) {
        myPermissions = permissions == null
                ? EnumSet.noneOf(StoragePermission.class)
                : EnumSet.copyOf(permissions);
        isOwner = owner;
    }

    public boolean isOwner() {
        return isOwner;
    }

    public boolean hasPermission(StoragePermission permission) {
        return isOwner || myPermissions.contains(permission);
    }

    /** 无 VIEW 时返回空槽位（不泄漏槽位内容）。 */
    public Map<Integer, StorageItemSlot> visibleSlots() {
        if (!hasPermission(StoragePermission.VIEW) || selectedSnapshot == null) {
            return Map.of();
        }
        return selectedSnapshot.slots();
    }

    /** 设置选中仓储的 ACL（由服务端在菜单数据中按 MANAGE 过滤后注入）。 */
    public void setGrants(Map<StoragePrincipal, StorageGrant> grants) {
        this.grants = grants == null ? null : Map.copyOf(grants);
    }

    /** 无 MANAGE 时返回空（不泄漏私有 ACL 详情）。 */
    public Map<StoragePrincipal, StorageGrant> visibleGrants() {
        if (!hasPermission(StoragePermission.MANAGE) || grants == null) {
            return Map.of();
        }
        return grants;
    }

    // ================= 模板（Task 10 UI） =================

    public void setTemplates(List<StorageTemplate> templates) {
        this.templates = templates == null ? List.of() : List.copyOf(templates);
    }

    public List<StorageTemplate> getTemplates() {
        return templates;
    }

    /**
     * 计算模板应用差异（COPY 或 FOLLOW）。
     *
     * <p>COPY：目标授权 = 模板授权；FOLLOW：目标授权 = 模板与本地授权合并
     * （本地显式 deny 覆盖模板 allow，由 {@link StorageTemplate#mergeGrants} 保证）。</p>
     *
     * <p>高风险判定：任何主体获得 {@code BREAK}、或 PUBLIC 主体获得
     * {@code MANAGE}/{@code WITHDRAW}，则 {@code highRisk=true}（客户端仅提示确认，
     * 服务端仍独立校验）。</p>
     */
    public static TemplatePreview previewTemplate(
            StorageTemplate template, boolean follow,
            Map<StoragePrincipal, StorageGrant> currentGrants) {
        Map<StoragePrincipal, StorageGrant> target =
                follow ? StorageTemplate.mergeGrants(template.grants(), currentGrants)
                        : template.grants();
        List<TemplateDiff> diffs = diffGrants(currentGrants, target);
        return new TemplatePreview(template, follow, target, diffs, isHighRisk(target));
    }

    /** 逐主体计算授权差异（added = 目标新增授予，removed = 目标收回）。 */
    public static List<TemplateDiff> diffGrants(
            Map<StoragePrincipal, StorageGrant> current,
            Map<StoragePrincipal, StorageGrant> target) {
        List<TemplateDiff> diffs = new ArrayList<>();
        Set<StoragePrincipal> principals = new java.util.LinkedHashSet<>();
        if (current != null) {
            principals.addAll(current.keySet());
        }
        if (target != null) {
            principals.addAll(target.keySet());
        }
        for (StoragePrincipal principal : principals) {
            StorageGrant before = current == null ? null : current.get(principal);
            StorageGrant after = target == null ? null : target.get(principal);
            Set<StoragePermission> added = EnumSet.noneOf(StoragePermission.class);
            Set<StoragePermission> removed = EnumSet.noneOf(StoragePermission.class);
            for (StoragePermission p : StoragePermission.values()) {
                boolean had = before != null && before.allows(p);
                boolean will = after != null && after.allows(p);
                if (!had && will) {
                    added.add(p);
                } else if (had && !will) {
                    removed.add(p);
                }
            }
            diffs.add(new TemplateDiff(principal, added, removed));
        }
        return Collections.unmodifiableList(diffs);
    }

    /**
     * 高风险授权判定：任何主体被授予 {@code BREAK}，或 PUBLIC 主体被授予
     * {@code MANAGE}/{@code WITHDRAW}。客户端仅用于确认提示，不构成安全边界。
     */
    public static boolean isHighRisk(Map<StoragePrincipal, StorageGrant> grants) {
        if (grants == null) {
            return false;
        }
        for (Map.Entry<StoragePrincipal, StorageGrant> entry : grants.entrySet()) {
            StorageGrant grant = entry.getValue();
            if (grant == null) {
                continue;
            }
            if (grant.allows(StoragePermission.BREAK)) {
                return true;
            }
            if (entry.getKey() instanceof StoragePrincipal.Public
                    && (grant.allows(StoragePermission.MANAGE)
                    || grant.allows(StoragePermission.WITHDRAW))) {
                return true;
            }
        }
        return false;
    }

    /** 授权表是否完全为空（用于「清空」判定）。 */
    public static boolean isEmptyGrants(Map<StoragePrincipal, StorageGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            return true;
        }
        for (StorageGrant grant : grants.values()) {
            if (grant != null && !grant.allow().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
