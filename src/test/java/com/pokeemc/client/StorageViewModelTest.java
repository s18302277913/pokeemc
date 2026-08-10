package com.pokeemc.client;

import com.pokeemc.storage.StorageGrant;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.StoragePermissionSet;
import com.pokeemc.storage.StoragePrincipal;
import com.pokeemc.storage.StorageTemplate;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageSnapshot;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StorageViewModel} 纯 JVM 测试（Task 9 验收：筛选、稳定排序、窄屏收起、
 * 范围校验、权限降级、快照增量更新）。
 */
class StorageViewModelTest {

    private static final StorageId ID_A = new StorageId("minecraft:overworld", "vanilla_chest", "0,0");
    private static final StorageId ID_B = new StorageId("minecraft:overworld", "vanilla_chest", "10,0");
    private static final StorageId ID_C = new StorageId("minecraft:overworld", "vanilla_barrel", "5,5");

    private static StorageDescriptor descriptor(StorageId id, String name, int distance,
                                                int slotCount, int usedSlots, long revision) {
        return descriptor(id, name, distance, slotCount, usedSlots, revision, null, 0L);
    }

    private static StorageDescriptor descriptor(StorageId id, String name, int distance,
                                                int slotCount, int usedSlots, long revision,
                                                String ownerName) {
        return descriptor(id, name, distance, slotCount, usedSlots, revision, ownerName, 0L);
    }

    private static StorageDescriptor descriptor(StorageId id, String name, int distance,
                                                int slotCount, int usedSlots, long revision,
                                                String ownerName, long createdAt) {
        return new StorageDescriptor(id, name, distance, true, UUID.randomUUID(), ownerName,
                EnumSet.of(StorageCapability.SNAPSHOT), slotCount, usedSlots, revision, true, createdAt);
    }

    private static StorageSnapshot snapshot(StorageId id, long revision, Map<Integer, StorageItemSlot> slots) {
        return new StorageSnapshot(id, revision, slots);
    }

    // ---------- 筛选 ----------

    @Test
    void searchMatchesDisplayNameAndId() {
        StorageViewModel vm = new StorageViewModel();
        vm.setStorages(List.of(
                descriptor(ID_A, "Alice 的箱子", 5, 27, 0, 1),
                descriptor(ID_B, "Bob 的木桶", 3, 27, 0, 1)));
        vm.setSearchText("alice");
        List<StorageDescriptor> visible = vm.visibleStorages();
        assertEquals(1, visible.size());
        assertEquals(ID_A, visible.get(0).storageId());
    }

    @Test
    void searchMatchesStorageIdAsString() {
        StorageViewModel vm = new StorageViewModel();
        vm.setStorages(List.of(descriptor(ID_A, "箱子", 5, 27, 0, 1)));
        vm.setSearchText("10,0");
        assertTrue(vm.visibleStorages().isEmpty());
        vm.setSearchText("0,0");
        assertEquals(1, vm.visibleStorages().size());
    }

    @Test
    void searchMatchesOwnerName() {
        StorageViewModel vm = new StorageViewModel();
        vm.setStorages(List.of(
                descriptor(ID_A, "Alice 的箱子", 5, 27, 0, 1, "Alice"),
                descriptor(ID_B, "未命名", 3, 27, 0, 1, "Bob")));
        vm.setSearchText("bob");
        List<StorageDescriptor> visible = vm.visibleStorages();
        assertEquals(1, visible.size());
        assertEquals(ID_B, visible.get(0).storageId());
    }

    // ---------- 稳定排序 ----------

    @Test
    void sortByNameIsStableWithIdTieBreak() {
        StorageDescriptor a = descriptor(ID_A, "Beta", 1, 27, 0, 1);
        StorageDescriptor b = descriptor(ID_B, "alpha", 2, 27, 0, 1);
        StorageDescriptor c = descriptor(ID_C, "alpha", 2, 27, 0, 1);
        List<StorageDescriptor> out = StorageViewModel.filterAndSort(
                List.of(a, b, c), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.NAME, Map.of());
        // alpha 在前（忽略大小写）；同名前按 storageId 序列稳定
        // asString 为 "minecraft:overworld|vanilla_chest|10,0" 与 "minecraft:overworld|vanilla_barrel|5,5"，
        // 字典序 "vanilla_barrel" < "vanilla_chest"，故 ID_C 在前。
        assertEquals(ID_C, out.get(0).storageId());
        assertEquals(ID_B, out.get(1).storageId());
        assertEquals(ID_A, out.get(2).storageId());
    }

    @Test
    void sortByFreeSlots() {
        StorageDescriptor a = descriptor(ID_A, "满", 1, 27, 27, 1);
        StorageDescriptor b = descriptor(ID_B, "空", 1, 27, 0, 1);
        List<StorageDescriptor> out = StorageViewModel.filterAndSort(
                List.of(a, b), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.FREE_SLOTS, Map.of());
        assertEquals(ID_B, out.get(0).storageId());
    }

    // ---------- 权限过滤 ----------

    @Test
    void filterByPermissionOnlyShowsGranted() {
        Map<StorageId, EnumSet<StoragePermission>> perms = Map.of(
                ID_A, EnumSet.of(StoragePermission.VIEW, StoragePermission.DEPOSIT),
                ID_B, EnumSet.of(StoragePermission.VIEW));
        List<StorageDescriptor> out = StorageViewModel.filterAndSort(
                List.of(descriptor(ID_A, "A", 1, 27, 0, 1),
                        descriptor(ID_B, "B", 1, 27, 0, 1)),
                "", StorageViewModel.FilterMode.DEPOSIT, StorageViewModel.SortMode.DISTANCE, perms);
        assertEquals(1, out.size());
        assertEquals(ID_A, out.get(0).storageId());
    }

    // ---------- 范围校验 ----------

    @Test
    void radiusIsClamped() {
        assertEquals(1, StorageViewModel.clampRadius(0, 512));
        assertEquals(512, StorageViewModel.clampRadius(9999, 512));
        assertEquals(42, StorageViewModel.clampRadius(42, 512));
    }

    @Test
    void overLimitRadiusIsFlagged() {
        StorageViewModel vm = new StorageViewModel();
        vm.setRadius(10000);
        assertTrue(vm.isRadiusOverLimit());
        // 超限钳制到客户端展示上限（会话 #9 提升到 648）
        assertEquals(StorageViewModel.MAX_RADIUS, vm.getRadius());
        vm.setRadius(128);
        assertFalse(vm.isRadiusOverLimit());
    }

    // ---------- 窄屏收起 ----------

    @Test
    void narrowScreenHidesDetailsPanel() {
        StorageViewModel vm = new StorageViewModel();
        assertTrue(vm.showDetailsPanel());
        vm.setNarrowScreen(true);
        assertFalse(vm.showDetailsPanel());
    }

    // ---------- 权限降级 ----------

    @Test
    void noViewHidesSlotContent() {
        StorageViewModel vm = new StorageViewModel();
        vm.selectStorage(ID_A, descriptor(ID_A, "A", 1, 27, 1, 5));
        vm.setMyPermissions(EnumSet.of(StoragePermission.DEPOSIT), false);
        vm.applySnapshot(snapshot(ID_A, 5, Map.of(
                0, new StorageItemSlot(0, "minecraft:dirt", 10, 12345L))));
        assertTrue(vm.visibleSlots().isEmpty()); // 无 VIEW，槽位内容不泄漏
    }

    @Test
    void viewAllowsSlotContent() {
        StorageViewModel vm = new StorageViewModel();
        vm.selectStorage(ID_A, descriptor(ID_A, "A", 1, 27, 1, 5));
        vm.setMyPermissions(EnumSet.of(StoragePermission.VIEW), false);
        vm.applySnapshot(snapshot(ID_A, 5, Map.of(
                0, new StorageItemSlot(0, "minecraft:dirt", 10, 12345L))));
        assertEquals(1, vm.visibleSlots().size());
    }

    @Test
    void noManageHidesAclDetails() {
        StorageViewModel vm = new StorageViewModel();
        vm.selectStorage(ID_A, descriptor(ID_A, "A", 1, 27, 0, 1));
        vm.setMyPermissions(EnumSet.of(StoragePermission.VIEW), false);
        Map<StoragePrincipal, StorageGrant> grants = Map.of(
                new StoragePrincipal.Public(),
                new StorageGrant(StoragePermissionSet.of(StoragePermission.VIEW),
                        StoragePermissionSet.EMPTY));
        vm.setGrants(grants);
        assertTrue(vm.visibleGrants().isEmpty()); // 无 MANAGE，私有 ACL 不泄漏
    }

    @Test
    void manageRevealsAclDetails() {
        StorageViewModel vm = new StorageViewModel();
        vm.selectStorage(ID_A, descriptor(ID_A, "A", 1, 27, 0, 1));
        vm.setMyPermissions(EnumSet.of(StoragePermission.MANAGE), false);
        Map<StoragePrincipal, StorageGrant> grants = Map.of(
                new StoragePrincipal.Public(),
                new StorageGrant(StoragePermissionSet.of(StoragePermission.VIEW),
                        StoragePermissionSet.EMPTY));
        vm.setGrants(grants);
        assertEquals(1, vm.visibleGrants().size());
    }

    // ---------- 会话 #21-E：同类型序号标记 + 放置时间/标记排序 ----------

    private static final StorageId ID_ENDER = new StorageId("minecraft:overworld", "vanilla_ender_chest",
            "player;" + UUID.randomUUID());

    @Test
    void assignMarkersNumbersSameTypeExcludingEnderChest() {
        StorageDescriptor chestA = descriptor(ID_A, "箱子A", 5, 27, 0, 1, null, 100L);
        StorageDescriptor chestB = descriptor(ID_B, "箱子B", 5, 27, 0, 1, null, 200L);
        StorageDescriptor barrelC = descriptor(ID_C, "木桶C", 5, 27, 0, 1, null, 300L);
        StorageDescriptor ender = descriptor(ID_ENDER, "末影箱", 0, 27, 0, 1, "Me", 50L);
        Map<String, Integer> markers = StorageViewModel.assignMarkers(
                List.of(chestA, ender, chestB, barrelC));
        // 同类型 chest 按给定顺序 ① ②；barrel 独立从 ① 开始；末影箱缺席
        assertEquals(1, markers.get(ID_A.asString()));
        assertEquals(2, markers.get(ID_B.asString()));
        assertEquals(1, markers.get(ID_C.asString()));
        assertFalse(markers.containsKey(ID_ENDER.asString()));
    }

    @Test
    void markerLabelCircledUpToTwenty() {
        assertEquals("", StorageViewModel.markerLabel(0));
        assertEquals("①", StorageViewModel.markerLabel(1));
        assertEquals("⑳", StorageViewModel.markerLabel(20));
        assertEquals("#21", StorageViewModel.markerLabel(21));
    }

    @Test
    void byCreatedAscOrdersOldestFirst() {
        StorageDescriptor old = descriptor(ID_A, "旧", 5, 27, 0, 1, null, 100L);
        StorageDescriptor mid = descriptor(ID_B, "中", 5, 27, 0, 1, null, 200L);
        StorageDescriptor new_ = descriptor(ID_C, "新", 5, 27, 0, 1, null, 300L);
        List<StorageDescriptor> out = StorageViewModel.byCreatedAsc(List.of(new_, old, mid));
        assertEquals(ID_A, out.get(0).storageId());
        assertEquals(ID_B, out.get(1).storageId());
        assertEquals(ID_C, out.get(2).storageId());
    }

    @Test
    void sortByCreatedAscDesc() {
        StorageDescriptor old = descriptor(ID_A, "旧", 5, 27, 0, 1, null, 100L);
        StorageDescriptor new_ = descriptor(ID_B, "新", 5, 27, 0, 1, null, 300L);
        List<StorageDescriptor> asc = StorageViewModel.filterAndSort(
                List.of(new_, old), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.CREATED_ASC, Map.of());
        assertEquals(ID_A, asc.get(0).storageId());
        List<StorageDescriptor> desc = StorageViewModel.filterAndSort(
                List.of(new_, old), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.CREATED_DESC, Map.of());
        assertEquals(ID_B, desc.get(0).storageId());
    }

    @Test
    void sortByMarkerGroupsTypeThenNumber() {
        StorageDescriptor barrelA = descriptor(ID_C, "木桶①", 5, 27, 0, 1);
        StorageDescriptor chestA = descriptor(ID_A, "箱子①", 5, 27, 0, 1);
        StorageDescriptor chestB = descriptor(ID_B, "箱子②", 5, 27, 0, 1);
        // 标记表：barrel ①，chest ① ②（由 assignMarkers 在放置时间序下产生）
        Map<String, Integer> markers = StorageViewModel.assignMarkers(
                StorageViewModel.byCreatedAsc(List.of(chestA, barrelA, chestB)));
        // MARKER_ASC：类型分组（barrel 在 chest 前）+ 组内序号升序
        List<StorageDescriptor> asc = StorageViewModel.filterAndSort(
                List.of(chestB, chestA, barrelA), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.MARKER_ASC, Map.of(), markers);
        assertEquals(ID_C, asc.get(0).storageId()); // barrel（①）
        assertEquals(ID_A, asc.get(1).storageId()); // chest ①
        assertEquals(ID_B, asc.get(2).storageId()); // chest ②
        // MARKER_DESC：类型倒序 + 组内序号倒序
        List<StorageDescriptor> desc = StorageViewModel.filterAndSort(
                List.of(chestB, chestA, barrelA), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.MARKER_DESC, Map.of(), markers);
        assertEquals(ID_B, desc.get(0).storageId());
        assertEquals(ID_A, desc.get(1).storageId());
        assertEquals(ID_C, desc.get(2).storageId());
    }

    // ---------- [NEW] 会话 #21-H 修订：物品总价值排序 ----------

    @Test
    void sortByValueAscDesc() {
        Map<String, Long> values = Map.of(
                ID_A.asString(), 100L,
                ID_B.asString(), 50L,
                ID_C.asString(), 200L);
        StorageDescriptor a = descriptor(ID_A, "A", 5, 27, 0, 1);
        StorageDescriptor b = descriptor(ID_B, "B", 5, 27, 0, 1);
        StorageDescriptor c = descriptor(ID_C, "C", 5, 27, 0, 1);
        // VALUE_ASC：便宜在前（B=50 < A=100 < C=200）
        List<StorageDescriptor> asc = StorageViewModel.filterAndSort(
                List.of(c, a, b), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.VALUE_ASC, Map.of(), Map.of(), values);
        assertEquals(ID_B, asc.get(0).storageId());
        assertEquals(ID_A, asc.get(1).storageId());
        assertEquals(ID_C, asc.get(2).storageId());
        // VALUE_DESC：值钱在前
        List<StorageDescriptor> desc = StorageViewModel.filterAndSort(
                List.of(a, c, b), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.VALUE_DESC, Map.of(), Map.of(), values);
        assertEquals(ID_C, desc.get(0).storageId());
        assertEquals(ID_A, desc.get(1).storageId());
        assertEquals(ID_B, desc.get(2).storageId());
    }

    @Test
    void sortByValueTreatsMissingAsZero() {
        // 无快照/无价的仓储按 0 处理：VALUE_ASC 升序时 0 在最前，VALUE_DESC 降序时垫底。
        Map<String, Long> values = Map.of(ID_A.asString(), 30L, ID_B.asString(), 10L);
        StorageDescriptor a = descriptor(ID_A, "A", 5, 27, 0, 1);
        StorageDescriptor b = descriptor(ID_B, "B", 5, 27, 0, 1);
        StorageDescriptor c = descriptor(ID_C, "C", 5, 27, 0, 1);
        List<StorageDescriptor> asc = StorageViewModel.filterAndSort(
                List.of(a, c, b), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.VALUE_ASC, Map.of(), Map.of(), values);
        // C(0) < B(10) < A(30)
        assertEquals(ID_C, asc.get(0).storageId());
        assertEquals(ID_B, asc.get(1).storageId());
        assertEquals(ID_A, asc.get(2).storageId());
        List<StorageDescriptor> desc = StorageViewModel.filterAndSort(
                List.of(a, b, c), "", StorageViewModel.FilterMode.ALL,
                StorageViewModel.SortMode.VALUE_DESC, Map.of(), Map.of(), values);
        // A(30) > B(10) > C(0，缺省垫底)
        assertEquals(ID_A, desc.get(0).storageId());
        assertEquals(ID_B, desc.get(1).storageId());
        assertEquals(ID_C, desc.get(2).storageId());
    }

    @Test
    void valueByStorageDefaultsAndRoundTrips() {
        StorageViewModel vm = new StorageViewModel();
        assertEquals(0L, vm.valueOf(ID_A.asString()));
        vm.setValueByStorage(Map.of(ID_A.asString(), 1234L));
        assertEquals(1234L, vm.valueOf(ID_A.asString()));
        assertEquals(0L, vm.valueOf(ID_B.asString()));
        // null 视为空表
        vm.setValueByStorage(null);
        assertEquals(0L, vm.valueOf(ID_A.asString()));
    }

    // ---------- 快照增量更新 ----------

    @Test
    void snapshotRevisionMonotonic() {
        StorageViewModel vm = new StorageViewModel();
        vm.selectStorage(ID_A, descriptor(ID_A, "A", 1, 27, 0, 1));
        vm.setMyPermissions(EnumSet.of(StoragePermission.VIEW), false);
        assertTrue(vm.applySnapshot(snapshot(ID_A, 3, Map.of())));
        assertEquals(3, vm.getSelectedSnapshotRevision());
        // 更旧快照被拒绝（不回退）
        assertFalse(vm.applySnapshot(snapshot(ID_A, 2, Map.of())));
        assertEquals(3, vm.getSelectedSnapshotRevision());
        // 更新快照接受
        assertTrue(vm.applySnapshot(snapshot(ID_A, 4, Map.of(
                1, new StorageItemSlot(1, "minecraft:stone", 5, 999L)))));
        assertEquals(4, vm.getSelectedSnapshotRevision());
    }

    @Test
    void snapshotSwitchStorageInvalidatesOld() {
        StorageViewModel vm = new StorageViewModel();
        vm.selectStorage(ID_A, descriptor(ID_A, "A", 1, 27, 0, 1));
        vm.setMyPermissions(EnumSet.of(StoragePermission.VIEW), false);
        vm.applySnapshot(snapshot(ID_A, 3, Map.of()));
        // 切换到仓储 B：A 的快照不再可见
        vm.selectStorage(ID_B, descriptor(ID_B, "B", 1, 27, 0, 1));
        assertTrue(vm.isSnapshotStale());
        assertEquals(-1L, vm.getSelectedSnapshotRevision());
    }

    // ---------- 模板预览与高风险 ----------

    @Test
    void highRiskDetectsBreakGrant() {
        Map<StoragePrincipal, StorageGrant> grants = Map.of(
                new StoragePrincipal.Public(),
                new StorageGrant(StoragePermissionSet.of(StoragePermission.BREAK),
                        StoragePermissionSet.EMPTY));
        assertTrue(StorageViewModel.isHighRisk(grants));
    }

    @Test
    void highRiskDetectsPublicManage() {
        Map<StoragePrincipal, StorageGrant> grants = Map.of(
                new StoragePrincipal.Public(),
                new StorageGrant(StoragePermissionSet.of(StoragePermission.MANAGE),
                        StoragePermissionSet.EMPTY));
        assertTrue(StorageViewModel.isHighRisk(grants));
    }

    @Test
    void lowRiskNormalGrants() {
        Map<StoragePrincipal, StorageGrant> grants = Map.of(
                new StoragePrincipal.Public(),
                new StorageGrant(StoragePermissionSet.of(StoragePermission.VIEW,
                        StoragePermission.DEPOSIT), StoragePermissionSet.EMPTY));
        assertFalse(StorageViewModel.isHighRisk(grants));
    }

    @Test
    void templatePreviewFollowKeepsLocalDeny() {
        // 本地显式 deny DEPOSIT；模板 allow DEPOSIT —— FOLLOW 合并为 allow+deny 并集，
        // deny 仍保留（由服务端 StorageAccessService 评估 deny 优先）。
        Map<StoragePrincipal, StorageGrant> current = Map.of(
                new StoragePrincipal.Public(),
                new StorageGrant(StoragePermissionSet.EMPTY,
                        StoragePermissionSet.of(StoragePermission.DEPOSIT)));
        StorageTemplate template = new StorageTemplate(
                "t1", StorageTemplate.Scope.PLAYER, UUID.randomUUID(), "模板",
                Map.of(new StoragePrincipal.Public(),
                        new StorageGrant(StoragePermissionSet.of(StoragePermission.DEPOSIT),
                                StoragePermissionSet.EMPTY)),
                System.currentTimeMillis(), System.currentTimeMillis(), 1);
        StorageViewModel.TemplatePreview preview =
                StorageViewModel.previewTemplate(template, true, current);
        StorageGrant target = preview.targetGrants().get(new StoragePrincipal.Public());
        // deny 并集保留本地 deny（deny 优先语义由服务端保证）
        assertTrue(target.deny().allows(StoragePermission.DEPOSIT));
        // FOLLOW 合并后 DEPOSIT 被本地 deny 抵消，实际授权不变 → 无差异
        assertFalse(preview.hasChanges());
    }

    @Test
    void emptyGrantsDetection() {
        assertTrue(StorageViewModel.isEmptyGrants(Map.of()));
        assertTrue(StorageViewModel.isEmptyGrants(Map.of(
                new StoragePrincipal.Public(), StorageGrant.NONE)));
        assertFalse(StorageViewModel.isEmptyGrants(Map.of(
                new StoragePrincipal.Public(),
                new StorageGrant(StoragePermissionSet.of(StoragePermission.VIEW),
                        StoragePermissionSet.EMPTY))));
    }
}
