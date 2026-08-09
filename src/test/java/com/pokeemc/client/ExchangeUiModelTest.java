package com.pokeemc.client;

import com.pokeemc.network.ExchangeCatalogPacket;
import com.poketrade.api.TradeResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeUiModelTest {

    @Test
    void catalogResponseMustMatchLatestRequestIdentity() {
        assertTrue(ExchangeUiModel.isCurrentCatalogResponse("request-2", "request-2"));
        assertFalse(ExchangeUiModel.isCurrentCatalogResponse("request-2", "request-1"));
        assertFalse(ExchangeUiModel.isCurrentCatalogResponse("request-2", null));
    }

    @Test
    void catalogSearchMatchesLocalizedNameRegistrationIdAndCategory() {
        assertTrue(ExchangeUiModel.matchesCatalogSearch("大师球", "大师球", "pixelmon:master_ball", "战斗用品"));
        assertTrue(ExchangeUiModel.matchesCatalogSearch("master_ball", "大师球", "pixelmon:master_ball", "战斗用品"));
        assertTrue(ExchangeUiModel.matchesCatalogSearch("战斗", "大师球", "pixelmon:master_ball", "战斗用品"));
        assertFalse(ExchangeUiModel.matchesCatalogSearch("精灵球", "大师球", "pixelmon:master_ball", "战斗用品"));
    }

    @Test
    void layoutExposesCatalogGeometryUsedByScreen() {
        ExchangeUiModel.Layout layout = ExchangeUiModel.Layout.expanded();

        assertEquals(layout.middle().x(), layout.catalogGrid().x());
        assertEquals(layout.middle().y() + 2, layout.catalogGrid().y());
        assertEquals(layout.controls().get("search"), layout.search());
        assertEquals(layout.controls().get("pagePrev"), layout.pagePrev());
        assertEquals(layout.controls().get("pageNext"), layout.pageNext());
        assertEquals(layout.controls().get("deposit"), layout.deposit());
        assertEquals(layout.controls().get("wallet"), layout.wallet());
        assertEquals(layout.controls().get("cartSell"), layout.cartSell());
    }

    @Test
    void expandedColumnsAndControlsFitWithinWindow() {
        ExchangeUiModel.Layout layout = ExchangeUiModel.Layout.expanded();

        assertEquals(470, layout.width());
        assertEquals(250, layout.height());
        assertTrue(layout.left().right() <= layout.middle().x());
        assertTrue(layout.middle().right() <= layout.right().x());
        assertTrue(layout.inventoryY() + 4 * 18 <= layout.height());
        assertTrue(layout.controls().values().stream().allMatch(layout::contains));
    }

    @Test
    void collapsedLayoutNarrowsWindowAndShiftsColumnsWithInventory() {
        ExchangeUiModel.Layout expanded = ExchangeUiModel.Layout.expanded();
        ExchangeUiModel.Layout leftCollapsed = ExchangeUiModel.Layout.forCollapsed(true, false);
        ExchangeUiModel.Layout rightCollapsed = ExchangeUiModel.Layout.forCollapsed(false, true);
        ExchangeUiModel.Layout bothCollapsed = ExchangeUiModel.Layout.forCollapsed(true, true);

        // 窗口宽度随收起动态收窄
        assertEquals(470, expanded.width());
        assertEquals(328, leftCollapsed.width());
        assertEquals(338, rightCollapsed.width());
        assertEquals(196, bothCollapsed.width());
        // 左栏收起：中栏与右栏整体左移，背包跟随中栏
        assertTrue(leftCollapsed.middle().x() < expanded.middle().x());
        assertTrue(leftCollapsed.right().x() < expanded.right().x());
        assertTrue(leftCollapsed.inventoryX() < expanded.inventoryX());
        assertEquals(expanded.middle().width(), leftCollapsed.middle().width());
        // 右栏收起：中栏右扩填充空位
        assertTrue(rightCollapsed.middle().width() > expanded.middle().width());
        assertTrue(bothCollapsed.middle().width() > leftCollapsed.middle().width());
        // 可见性标志
        assertFalse(leftCollapsed.leftVisible());
        assertFalse(rightCollapsed.rightVisible());
        assertFalse(bothCollapsed.leftVisible());
        assertFalse(bothCollapsed.rightVisible());
        // 各收起态下：中栏在窗口内、可见栏之间不重叠、背包锚定中栏
        for (ExchangeUiModel.Layout layout : List.of(expanded, leftCollapsed, rightCollapsed, bothCollapsed)) {
            assertTrue(layout.contains(layout.middle()));
            assertFalse(layout.leftVisible() && layout.left().overlaps(layout.middle()));
            assertFalse(layout.rightVisible() && layout.middle().overlaps(layout.right()));
            assertEquals(layout.middle().x() + 4, layout.inventoryX());
        }
    }

    @Test
    void cartGridSitsInsideRightColumnAndExposesTwentySevenCells() {
        ExchangeUiModel.Layout layout = ExchangeUiModel.Layout.expanded();
        ExchangeUiModel.Rect grid = layout.cartGrid();

        assertEquals(layout.right().x() + 2, grid.x());
        assertEquals(ExchangeUiModel.Layout.CART_COLS * 18, grid.width());
        assertEquals(ExchangeUiModel.Layout.CART_ROWS * 18, grid.height());
        assertTrue(layout.contains(grid));
        // 7x4 网格共 28 个格位，但购物车容量为 27：渲染/点击只使用前 27 格
        assertEquals(28, (grid.width() / 18) * (grid.height() / 18));
        assertEquals(27, ExchangeUiModel.Layout.CART_CELLS);
        int lastCell = ExchangeUiModel.Layout.CART_CELLS - 1;
        assertTrue(lastCell % ExchangeUiModel.Layout.CART_COLS < ExchangeUiModel.Layout.CART_COLS);
        assertTrue(lastCell / ExchangeUiModel.Layout.CART_COLS < ExchangeUiModel.Layout.CART_ROWS);
    }

    @Test
    void previewModalStaysInsideWindowForEveryCollapseState() {
        for (ExchangeUiModel.Layout layout : List.of(
                ExchangeUiModel.Layout.expanded(),
                ExchangeUiModel.Layout.forCollapsed(true, false),
                ExchangeUiModel.Layout.forCollapsed(false, true),
                ExchangeUiModel.Layout.forCollapsed(true, true))) {
            assertTrue(layout.contains(layout.previewModal()));
            assertTrue(layout.contains(layout.previewCancel()));
            assertTrue(layout.contains(layout.previewConfirm()));
            assertTrue(layout.contains(layout.previewLines()));
            assertTrue(layout.previewLines().y() >= layout.previewModal().y());
            assertTrue(layout.previewLines().bottom() <= layout.previewModal().bottom());
        }
    }

    @Test
    void quantityControlsLiveInsideRightColumnOperationArea() {
        ExchangeUiModel.Layout layout = ExchangeUiModel.Layout.expanded();
        for (String key : List.of("qtyOne", "qtyHalf", "qtyStack", "qtyClear", "quantityBox", "quantityApply")) {
            ExchangeUiModel.Rect rect = layout.controls().get(key);
            assertTrue(rect.x() >= layout.right().x());
            assertTrue(rect.right() <= layout.right().right());
            assertTrue(rect.y() >= layout.right().y());
            assertTrue(rect.bottom() <= layout.right().bottom());
        }
        // 1x/32x/64x/清空 四个快捷按钮按序排列在右栏操作区
        assertEquals(layout.right().x(), layout.qtyOne().x());
        assertTrue(layout.qtyHalf().x() > layout.qtyOne().right());
        assertTrue(layout.qtyStack().x() > layout.qtyHalf().right());
        assertTrue(layout.qtyClear().right() <= layout.right().right());
    }

    @Test
    void scrollOffsetClampsToPageBounds() {
        assertEquals(0, ExchangeUiModel.clampScroll(-3, 30, 27));
        assertEquals(0, ExchangeUiModel.clampScroll(0, 30, 27));
        assertEquals(3, ExchangeUiModel.clampScroll(5, 30, 27));
        assertEquals(3, ExchangeUiModel.clampScroll(99, 30, 27));
        assertEquals(0, ExchangeUiModel.clampScroll(5, 27, 27));
        assertEquals(0, ExchangeUiModel.clampScroll(1, 10, 27));
        assertEquals(4, ExchangeUiModel.clampScroll(7, 40, 36));
        // 分页向上取整
        assertEquals(3, ExchangeUiModel.pageCount(60, 27));
        assertEquals(1, ExchangeUiModel.pageCount(27, 27));
        assertEquals(2, ExchangeUiModel.pageCount(54, 27));
        assertEquals(0, ExchangeUiModel.pageCount(0, 27));
    }

    @Test
    void storageScopeCyclesSupportedRadiusPresets() {
        // 点击切换：每击翻倍，16 → 32 → 64 → 128 → 256 → 512 → 648 → 重置 16
        assertEquals(32, ExchangeUiModel.nextStorageRadius(16));
        assertEquals(64, ExchangeUiModel.nextStorageRadius(32));
        assertEquals(128, ExchangeUiModel.nextStorageRadius(64));
        assertEquals(256, ExchangeUiModel.nextStorageRadius(128));
        assertEquals(512, ExchangeUiModel.nextStorageRadius(256));
        assertEquals(648, ExchangeUiModel.nextStorageRadius(512));
        assertEquals(16, ExchangeUiModel.nextStorageRadius(648));
    }

    @Test
    void storageScopeSnapsNonStepValuesToNextLargerStepOrDefault() {
        // 非档位残留值跳到下一个更大档位（防历史输入破坏循环）
        assertEquals(16, ExchangeUiModel.nextStorageRadius(0));
        assertEquals(16, ExchangeUiModel.nextStorageRadius(10));
        assertEquals(32, ExchangeUiModel.nextStorageRadius(20));
        assertEquals(512, ExchangeUiModel.nextStorageRadius(300));
        // 已超上限的值绕回默认 16
        assertEquals(16, ExchangeUiModel.nextStorageRadius(700));
        assertEquals(16, ExchangeUiModel.nextStorageRadius(Integer.MAX_VALUE));
    }

    @Test
    void fullPreviewRetainsEverySellableLineWithoutTruncation() {
        List<ExchangeUiModel.SourceLine> source = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> new ExchangeUiModel.SourceLine("mod:item_" + i, "Item " + i, 1))
                .toList();
        Map<String, Long> prices = java.util.stream.IntStream.range(0, 30).boxed()
                .collect(java.util.stream.Collectors.toMap(i -> "mod:item_" + i, i -> 5L));

        ExchangeUiModel.SellPreview preview = ExchangeUiModel.SellPreview.scan(
                source, prices, Integer.MAX_VALUE, 0L, ExchangeUiModel.SellSource.STORAGE,
                Set.of(), Set.of(), false);

        assertEquals(30, preview.lines().size());
        assertFalse(preview.truncated());
        assertEquals(150L, preview.total());
    }

    @Test
    void cartMergesCapsCountsClearsAndFormatsTotal() {
        ExchangeUiModel.Cart cart = new ExchangeUiModel.Cart(27, 64);
        cart.add("minecraft:diamond", 16);
        cart.add("minecraft:diamond", 64);
        cart.add("minecraft:emerald", 2);

        assertEquals(2, cart.size());
        assertEquals(66, cart.totalItems());
        assertEquals(642L, cart.total(Map.of("minecraft:diamond", 10L, "minecraft:emerald", 1L)));
        assertEquals("642", ExchangeUiModel.compactAmount(642));
        cart.remove("minecraft:emerald");
        assertEquals(1, cart.size());
        cart.clear();
        assertTrue(cart.isEmpty());
    }

    @Test
    void cartRejectsNonPositiveAddsWithoutMutatingExistingLines() {
        ExchangeUiModel.Cart cart = new ExchangeUiModel.Cart(27, 64);
        assertTrue(cart.add("minecraft:diamond", 16));

        assertFalse(cart.add("minecraft:diamond", 0));
        assertFalse(cart.add("minecraft:diamond", -1));
        assertEquals(16, cart.get(0).count());
    }

    @Test
    void cartAdditionCannotOverflowPastStackLimit() {
        ExchangeUiModel.Cart cart = new ExchangeUiModel.Cart(27, 64);
        assertTrue(cart.add("minecraft:diamond", 63));

        assertTrue(cart.add("minecraft:diamond", Integer.MAX_VALUE));
        assertEquals(64, cart.get(0).count());
    }

    @Test
    void previewAggregatesSkipsUnsellableAndTruncates() {
        List<ExchangeUiModel.SourceLine> source = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> new ExchangeUiModel.SourceLine("mod:item_" + i, "Item " + i, 2))
                .toList();
        Map<String, Long> prices = java.util.stream.IntStream.range(0, 30).boxed()
                .collect(java.util.stream.Collectors.toMap(i -> "mod:item_" + i, i -> i == 0 ? 0L : 5L));

        ExchangeUiModel.SellPreview preview = ExchangeUiModel.SellPreview.scan(
                source, prices, 27, 200L, ExchangeUiModel.SellSource.INVENTORY,
                Set.of(), Set.of(), false);

        assertEquals(27, preview.lines().size());
        assertEquals(270L, preview.total());
        assertEquals(1, preview.skipped());
        assertTrue(preview.truncated());
        assertTrue(preview.requiresConfirmation());
    }

    @Test
    void previewSkipsBlacklistedItemsAndCountsThem() {
        List<ExchangeUiModel.SourceLine> source = List.of(
                new ExchangeUiModel.SourceLine("mod:banned", "Banned", 5),
                new ExchangeUiModel.SourceLine("mod:ok", "OK", 3));
        Map<String, Long> prices = Map.of("mod:banned", 10L, "mod:ok", 10L);

        ExchangeUiModel.SellPreview preview = ExchangeUiModel.SellPreview.scan(
                source, prices, 27, 0L, ExchangeUiModel.SellSource.INVENTORY,
                Set.of("mod:banned"), Set.of(), false);

        assertEquals(1, preview.lines().size());
        assertEquals("mod:ok", preview.lines().get(0).itemId());
        assertEquals(30L, preview.total());
        assertEquals(1, preview.skipped());
    }

    @Test
    void previewKeepsOnlyAllowedItemsWhenAllowlistEnabled() {
        List<ExchangeUiModel.SourceLine> source = List.of(
                new ExchangeUiModel.SourceLine("mod:allowed", "Allowed", 2),
                new ExchangeUiModel.SourceLine("mod:other", "Other", 4));
        Map<String, Long> prices = Map.of("mod:allowed", 10L, "mod:other", 10L);

        ExchangeUiModel.SellPreview preview = ExchangeUiModel.SellPreview.scan(
                source, prices, 27, 0L, ExchangeUiModel.SellSource.STORAGE,
                Set.of(), Set.of("mod:allowed"), true);

        assertEquals(1, preview.lines().size());
        assertEquals("mod:allowed", preview.lines().get(0).itemId());
        assertEquals(20L, preview.total());
        assertEquals(1, preview.skipped());
    }

    @Test
    void previewBlacklistOverridesAllowlist() {
        // 同物品同时出现在黑白名单时，黑名单优先拦截（与 SellRules.canSell 语义一致）。
        List<ExchangeUiModel.SourceLine> source = List.of(
                new ExchangeUiModel.SourceLine("mod:both", "Both", 2));
        Map<String, Long> prices = Map.of("mod:both", 10L);

        ExchangeUiModel.SellPreview preview = ExchangeUiModel.SellPreview.scan(
                source, prices, 27, 0L, ExchangeUiModel.SellSource.INVENTORY,
                Set.of("mod:both"), Set.of("mod:both"), true);

        assertEquals(0, preview.lines().size());
        assertEquals(0L, preview.total());
        assertEquals(1, preview.skipped());
    }

    @Test
    void requestStatePreservesOrClearsRecoverableDataByOperation() {
        ExchangeUiModel.Workflow workflow = new ExchangeUiModel.Workflow();
        workflow.begin(ExchangeUiModel.Operation.BUY, 10);
        assertFalse(workflow.begin(ExchangeUiModel.Operation.BUY, 10));
        assertTrue(workflow.pending());

        ExchangeUiModel.ResultAction failed = workflow.complete(11, ExchangeUiModel.Operation.BUY,
                TradeResult.INSUFFICIENT_FUNDS);
        assertEquals(ExchangeUiModel.ResultAction.KEEP_DRAFT, failed);
        assertFalse(workflow.pending());

        workflow.begin(ExchangeUiModel.Operation.STORAGE_SELL, 11);
        ExchangeUiModel.ResultAction succeeded = workflow.complete(12,
                ExchangeUiModel.Operation.STORAGE_SELL, TradeResult.SUCCESS);
        assertEquals(ExchangeUiModel.ResultAction.REFRESH_STORAGE, succeeded);
        assertEquals("poketrade.exchange.result.storage_sell.success", workflow.messageKey());
    }

    @Test
    void staleAndUnknownResultsAreStable() {
        ExchangeUiModel.Workflow workflow = new ExchangeUiModel.Workflow();
        workflow.begin(ExchangeUiModel.Operation.INVENTORY_SELL, 20);

        assertEquals(ExchangeUiModel.ResultAction.IGNORE,
                workflow.complete(20, ExchangeUiModel.Operation.INVENTORY_SELL, TradeResult.SUCCESS));
        assertEquals(ExchangeUiModel.ResultAction.IGNORE,
                workflow.complete(19, ExchangeUiModel.Operation.INVENTORY_SELL, TradeResult.SUCCESS));
        assertTrue(workflow.pending());
        assertEquals("poketrade.exchange.result.internal_error",
                ExchangeUiModel.resultKey(ExchangeUiModel.Operation.NONE, 999));
    }

    @Test
    void filterCatalogEntriesMatchesLocalizedDisplayNameAndSkipsNonMatches() {
        List<ExchangeCatalogPacket.EntryWire> entries = List.of(
                new ExchangeCatalogPacket.EntryWire("pixelmon:master_ball", 100L, 50L, "战斗用品", "common", "pixelmon"),
                new ExchangeCatalogPacket.EntryWire("minecraft:diamond", 100L, 50L, "矿物", "common", "minecraft"));
        Map<String, String> names = Map.of(
                "pixelmon:master_ball", "大师球",
                "minecraft:diamond", "钻石");

        // 按本地化显示名过滤
        List<ExchangeCatalogPacket.EntryWire> out =
                ExchangeUiModel.filterCatalogEntries(entries, "大师球", names::get);
        assertEquals(1, out.size());
        assertEquals("pixelmon:master_ball", out.get(0).itemId());

        // 空/空白查询不做二次过滤
        assertEquals(2, ExchangeUiModel.filterCatalogEntries(entries, "", names::get).size());
        assertEquals(2, ExchangeUiModel.filterCatalogEntries(entries, "   ", names::get).size());

        // 大小写不敏感（英文注册 ID）
        assertEquals(1, ExchangeUiModel.filterCatalogEntries(entries, "DIAMOND", names::get).size());

        // 显示名解析器为 null 时退化为 id/category 匹配，不抛异常
        assertEquals(1, ExchangeUiModel.filterCatalogEntries(entries, "minecraft:diamond", null).size());
        assertEquals(2, ExchangeUiModel.filterCatalogEntries(entries, "", null).size());
    }

    @Test
    void filterCatalogEntriesNeverExpandsServerSideCutoff() {
        List<ExchangeCatalogPacket.EntryWire> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            entries.add(new ExchangeCatalogPacket.EntryWire("mod:item_" + i, 100L, 50L, "分类", "common", "mod"));
        }
        List<ExchangeCatalogPacket.EntryWire> filtered =
                ExchangeUiModel.filterCatalogEntries(entries, "item_499", Map.of("mod:item_499", "目标物品")::get);
        assertEquals(1, filtered.size());
        assertEquals("mod:item_499", filtered.get(0).itemId());
        // 空查询原样返回（不放大、不缩小服务端截断结果）
        assertEquals(500, ExchangeUiModel.filterCatalogEntries(entries, "", s -> "").size());
    }

    @Test
    void previewRecordsReasonsForEverySkippedLine() {
        List<ExchangeUiModel.SourceLine> source = List.of(
                new ExchangeUiModel.SourceLine("mod:noprice", "NoPrice", 1),
                new ExchangeUiModel.SourceLine("mod:banned", "Banned", 1),
                new ExchangeUiModel.SourceLine("mod:notallowed", "NotAllowed", 1),
                new ExchangeUiModel.SourceLine("mod:ok", "Ok", 1));
        Map<String, Long> prices = Map.of("mod:banned", 5L, "mod:notallowed", 5L, "mod:ok", 5L);

        ExchangeUiModel.SellPreview preview = ExchangeUiModel.SellPreview.scan(
                source, prices, 27, 0L, ExchangeUiModel.SellSource.INVENTORY,
                Set.of("mod:banned"), Set.of("mod:ok"), true);

        assertEquals(1, preview.lines().size());
        assertEquals(3, preview.skipped());
        assertEquals(3, preview.skipReasons().size());
        assertTrue(preview.skipReasons().contains(ExchangeUiModel.SkipReason.NO_PRICE));
        assertTrue(preview.skipReasons().contains(ExchangeUiModel.SkipReason.BLACKLISTED));
        assertTrue(preview.skipReasons().contains(ExchangeUiModel.SkipReason.NOT_ALLOWED));
        assertFalse(preview.skipReasons().contains(ExchangeUiModel.SkipReason.ZERO_COUNT));
    }

    @Test
    void storageSellPreviewGatesConfirmationOnPermission() {
        ExchangeUiModel.SellPreview inner = ExchangeUiModel.SellPreview.scan(
                List.of(new ExchangeUiModel.SourceLine("mod:x", "X", 1)),
                Map.of("mod:x", 10L), 27, 0L, ExchangeUiModel.SellSource.STORAGE,
                Set.of(), Set.of(), false);

        assertTrue(new ExchangeUiModel.StorageSellPreview(inner, "箱子", "storage-1", true, 5L).canConfirm());
        // 无 SELL 权限禁止确认
        assertFalse(new ExchangeUiModel.StorageSellPreview(inner, "箱子", "storage-1", false, 5L).canConfirm());
        // 无行也禁止确认
        ExchangeUiModel.SellPreview empty = ExchangeUiModel.SellPreview.scan(
                List.of(), Map.of(), 27, 0L, ExchangeUiModel.SellSource.STORAGE,
                Set.of(), Set.of(), false);
        assertFalse(new ExchangeUiModel.StorageSellPreview(empty, "箱子", "storage-1", true, 5L).canConfirm());
    }

    @Test
    void storageSellPreviewRequiresLoadedSnapshotRevision() {
        ExchangeUiModel.SellPreview inner = ExchangeUiModel.SellPreview.scan(
                List.of(new ExchangeUiModel.SourceLine("mod:x", "X", 1)),
                Map.of("mod:x", 10L), 27, 0L,
                ExchangeUiModel.SellSource.STORAGE, Set.of(), Set.of(), false);
        // 快照尚未加载（revision = -1）时禁止确认，避免必然的 revision 冲突失败
        assertFalse(new ExchangeUiModel.StorageSellPreview(
                inner, "箱子", "storage-1", true, -1L).canConfirm());
        assertTrue(new ExchangeUiModel.StorageSellPreview(
                inner, "箱子", "storage-1", true, 5L).canConfirm());
    }

    @Test
    void cartCapacityStopsAtTwentySevenLines() {
        ExchangeUiModel.Cart cart = new ExchangeUiModel.Cart(27, 64);
        for (int i = 0; i < 27; i++) {
            assertTrue(cart.add("mod:item_" + i, 1), "add " + i + " should fit");
        }
        assertEquals(27, cart.size());
        assertFalse(cart.add("mod:item_27", 1), "28th distinct line must be rejected");
        assertEquals(27, cart.size());
    }

    @Test
    void snapshotPagingUsesFourteenCellPagesAndClampsOffset() {
        assertEquals(3, ExchangeUiModel.pageCount(40, 14));
        assertEquals(2, ExchangeUiModel.pageCount(28, 14));
        assertEquals(1, ExchangeUiModel.pageCount(14, 14));
        assertEquals(0, ExchangeUiModel.pageCount(0, 14));
        assertEquals(2, ExchangeUiModel.clampScroll(9, 3, 1));
        assertEquals(0, ExchangeUiModel.clampScroll(-1, 3, 1));
        assertEquals(2, ExchangeUiModel.clampScroll(5, 3, 1));
    }

    @Test
    void groupCountAccumulatesMatchingLinesOnly() {
        List<ExchangeUiModel.SourceLine> source = List.of(
                new ExchangeUiModel.SourceLine("mod:a", "A", 3),
                new ExchangeUiModel.SourceLine("mod:a", "A", 5),
                new ExchangeUiModel.SourceLine("mod:b", "B", 7),
                new ExchangeUiModel.SourceLine("mod:a", "A", 1));

        // 同 ID 多行累加、异 id 隔离
        assertEquals(9, ExchangeUiModel.groupCount(source, "mod:a"));
        assertEquals(7, ExchangeUiModel.groupCount(source, "mod:b"));
        assertEquals(0, ExchangeUiModel.groupCount(source, "mod:missing"));
        // 空源 / 0 计数
        assertEquals(0, ExchangeUiModel.groupCount(List.of(), "mod:a"));
        assertEquals(0, ExchangeUiModel.groupCount(
                List.of(new ExchangeUiModel.SourceLine("mod:a", "A", 0)), "mod:a"));
    }

    @Test
    void shiftSellNeedsConfirmRespectsThresholdGuardAndOverflow() {
        // 阈值 0 = 关闭确认（与 SellPreview.scan 的 confirmThreshold>0 守卫一致）
        assertFalse(ExchangeUiModel.shiftSellNeedsConfirm(10, 100, 0));
        // 数量/单价非法恒 false
        assertFalse(ExchangeUiModel.shiftSellNeedsConfirm(0, 100, 100_000));
        assertFalse(ExchangeUiModel.shiftSellNeedsConfirm(10, 0, 100_000));
        // 边界：等于阈值需确认、略低不确认、远超阈值需确认
        assertTrue(ExchangeUiModel.shiftSellNeedsConfirm(1000, 100, 100_000));
        assertFalse(ExchangeUiModel.shiftSellNeedsConfirm(999, 100, 100_000));
        assertTrue(ExchangeUiModel.shiftSellNeedsConfirm(2000, 100, 100_000));
        // 乘法溢出按需确认（安全处理）
        assertTrue(ExchangeUiModel.shiftSellNeedsConfirm(Long.MAX_VALUE, Long.MAX_VALUE, 100_000));
    }

    // ===== [CHANGED] 会话 #11：仓储手风琴网格行数（问题 2） =====

    @Test
    void accordionContentRowsFollowsCapacityNotOccupiedSlots() {
        // 未裁剪行数（滚动范围）：单箱 27/7→4、双箱 54/7→8（第 8 排可滚的关键）
        assertEquals(4, ExchangeUiModel.accordionContentRows(27, 7));
        assertEquals(8, ExchangeUiModel.accordionContentRows(54, 7));
        // 稀疏物品/空箱占位 1 行（不再因只放了 3 件就只显示 1 行）
        assertEquals(1, ExchangeUiModel.accordionContentRows(4, 7));
        assertEquals(1, ExchangeUiModel.accordionContentRows(0, 7));
        // 大容器 / 非法列数兜底
        assertEquals(16, ExchangeUiModel.accordionContentRows(108, 7));
        assertEquals(1, ExchangeUiModel.accordionContentRows(54, 0));
    }

    @Test
    void accordionVisibleRowsClampsToPanelLimit() {
        // 裁剪行数（面板高度）：单箱 4、双箱 7（8 行被裁剪）、大容器也封顶
        assertEquals(4, ExchangeUiModel.accordionVisibleRows(27, 7, 7));
        assertEquals(7, ExchangeUiModel.accordionVisibleRows(54, 7, 7));
        assertEquals(7, ExchangeUiModel.accordionVisibleRows(108, 7, 7));
        // 空箱/非法 maxRows 兜底至少 1 行
        assertEquals(1, ExchangeUiModel.accordionVisibleRows(0, 7, 7));
        assertEquals(1, ExchangeUiModel.accordionVisibleRows(54, 7, 0));
    }

    // ===== [CHANGED] 会话 #11：仓储首次查询门（问题 1 自动展开） =====

    @Test
    void firstQueryGateExpandsOnlyOnFirstVisibleNonEmptyQuery() {
        ExchangeUiModel.FirstQueryGate gate = new ExchangeUiModel.FirstQueryGate();
        // 首包 + visible 非空 + 当前全收起 → 应自动展开首个
        assertTrue(gate.onQuery(true, true));
        // 后续包（10 秒自动刷新）即使全收起也不再展开
        assertFalse(gate.onQuery(true, true));
        assertFalse(gate.onQuery(true, false));
    }

    @Test
    void firstQueryGateConsumesOnFirstQueryEvenWithoutAutoExpand() {
        // 首包已有展开（expanded 非空）→ 不自动展开，但 received 已置位
        ExchangeUiModel.FirstQueryGate gate = new ExchangeUiModel.FirstQueryGate();
        assertFalse(gate.onQuery(true, false));
        // 后续包恒 false
        assertFalse(gate.onQuery(true, true));
    }

    @Test
    void firstQueryGateVisibleEmptyDoesNotExpandLater() {
        // 首包 visible 空 → 不展开，且后续走近（visible 非空 + 全收起）也不再自动展开
        ExchangeUiModel.FirstQueryGate gate = new ExchangeUiModel.FirstQueryGate();
        assertFalse(gate.onQuery(false, true));
        assertFalse(gate.onQuery(true, true));
    }
}
