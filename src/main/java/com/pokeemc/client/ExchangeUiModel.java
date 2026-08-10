package com.pokeemc.client;

import com.pokeemc.network.ExchangeCatalogPacket;
import com.poketrade.api.TradeResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class ExchangeUiModel {
    static final int WIDTH = 470;
    static final int HEIGHT = 250;
    static final int INVENTORY_X = 154;
    static final int INVENTORY_Y = 168;
    static final int HOTBAR_Y = 222;

    record Rect(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(int px, int py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        /** 矩形完全包含测试（如模态包含其子控件）。 */
        boolean contains(Rect other) {
            return other.x >= x && other.y >= y
                    && other.right() <= right() && other.bottom() <= bottom();
        }

        boolean overlaps(Rect other) {
            return x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y;
        }
    }

    /**
     * 屏幕几何的<b>单一来源</b>：窗口宽高、三栏矩形、目录/购物车网格、背包区域、
     * 搜索框、各按钮矩形、预览模态矩形全部在此派生；屏幕的
     * {@code init/renderBg/mouseClicked/mouseScrolled/renderTooltip/renderSellPreview}
     * 一律读取本布局值，不保留重复几何常量。
     *
     * <p>收起派生：左栏收起 → 窗口收窄、中栏与右栏左移、背包跟随；右栏收起 →
     * 窗口收窄、中栏向右延伸填补右栏空间。收起时窗口宽度动态变化，
     * 屏幕通过 {@code applyLayout()} 同步 {@code imageWidth/leftPos} 并重排玩家槽位。</p>
     */
    record Layout(int width, int height, Rect left, Rect middle, Rect right, int inventoryX,
                  int inventoryY, int hotbarY, boolean leftVisible, boolean rightVisible,
                  Map<String, Rect> controls) {
        /** 中栏目录网格：9 列 × 5 行 = 45 格/页（箱子格式，下方留给存入格/钱包/页码） */
        static final int GRID_COLS = 9;
        static final int GRID_ROWS = 5;
        /** 右栏购物车网格：7 列 × 4 行可见（28 格），容量 {@value #CART_CAPACITY}（双箱），
         *  超出可见格的部分经 {@code cartScroll} 行式滚动访问（≤28 格无滑条）。 */
        static final int CART_COLS = 7;
        static final int CART_ROWS = 4;
        static final int CART_CAPACITY = 54;
        /** 左栏快照网格：7 列 × 2 行 = 14 格/页（可滚动查看全部槽位） */
        static final int SNAP_COLS = 7;
        static final int SNAP_ROWS = 2;
        /** 预览模态行数（可滚动查看全部条目，最多 27 行） */
        static final int PREVIEW_ROWS = 6;

        private static final int LEFT_W = 132;
        private static final int MID_W = 170;
        private static final int RIGHT_W = 132;
        private static final int COL_GAP = 10;
        private static final int MARGIN_L = 8;
        private static final int MARGIN_R = 8;

        static Layout expanded() {
            return of(false, false);
        }

        /** 收起态派生的主入口（屏幕与测试统一使用；{@code forCollapsed}/{@code of} 为别名）。 */
        static Layout layoutFor(boolean leftCollapsed, boolean rightCollapsed) {
            return of(leftCollapsed, rightCollapsed);
        }

        /** {@link #layoutFor} 的兼容别名。 */
        static Layout forCollapsed(boolean leftCollapsed, boolean rightCollapsed) {
            return layoutFor(leftCollapsed, rightCollapsed);
        }

        static Layout of(boolean leftCollapsed, boolean rightCollapsed) {
            // 左栏贯穿窗口高度：搜索行 + 列表 + 完整快照网格 + 底部操作按钮
            Rect left = new Rect(MARGIN_L, 28, LEFT_W, 214);
            int middleX = leftCollapsed ? MARGIN_L : MARGIN_L + LEFT_W + COL_GAP;
            int rightX = middleX + MID_W + COL_GAP;
            int windowWidth = rightCollapsed ? rightX + MARGIN_R : rightX + RIGHT_W + MARGIN_R;
            Rect middle = new Rect(middleX, 28,
                    rightCollapsed ? windowWidth - MARGIN_R - middleX : MID_W, 128);
            Rect right = new Rect(rightX, 28, RIGHT_W, 128);
            int inventoryX = middleX + 4;
            // 收起时展开按钮与顶行控件让位：右栏收起 → 排序左移让出右缘；
            // 左栏收起 → 搜索框右移让出左缘（展开按钮贴窗口左缘），互不重叠。
            int leftReserve = leftCollapsed ? 14 : 0;
            int rightReserve = rightCollapsed ? 18 : 0;
            Map<String, Rect> controls = new LinkedHashMap<>();
            // 中栏顶行：目录模式指示（学习/全高亮）+ 搜索框 + ‹/› 分页按钮（独立于左栏仓储搜索）
            // [NEW] 会话 #21-H：modeText 占最左 32px，搜索框右移 32 让位（右缘与 pagePrev 间距不变）。
            controls.put("modeText", new Rect(middleX + leftReserve, 12, 32, 14));
            controls.put("search", new Rect(middleX + leftReserve + 32, 12,
                    Math.max(42, middle.width() - 42 - rightReserve - leftReserve - 32), 14));
            controls.put("pagePrev", new Rect(middle.right() - 36 - rightReserve, 12, 18, 14));
            controls.put("pageNext", new Rect(middle.right() - 18 - rightReserve, 12, 18, 14));
            // 商品网格下方：存入格 + 钱包（整数金额）+ 页码 + 悬停价格
            controls.put("deposit", new Rect(middleX + 2, 122, 18, 18));
            controls.put("wallet", new Rect(middleX + 24, 128, 96, 8));
            controls.put("pageText", new Rect(middleX + 126, 128, 40, 8));
            // [CHANGED] Bug 4：y 144→146 让出下移到钱包下方的标题行（标题底部约 146），避免叠印
            // [CHANGED] 会话 #21-B：y 146→137 与标题对调（标题再往下挪到 146，悬停价格上移
            // 至原标题位），仍居 wallet 行（128..136）下方、不与标题(146..155)重叠。
            controls.put("priceHint", new Rect(middleX + 2, 137, 130, 8));
            // 左栏底部操作：刷新 / 一键展开收起 / 一键存入 / 排序（批量出售已移到购物车）。
            // [CHANGED] 会话 #21-E：新增 storageSort —— 贴左栏右缘（存入 x+62 宽 40 止于
            // x+102，剩余 x+104..x+132），点击循环切换排序（距离/放置时间/标记正倒序）。
            // [CHANGED] 会话 #21-F Bug 3：storageClear 文案改「一键展开/一键收起」（4 字），
            // 放宽到 38px；storageDeposit 同步收窄到 34px 让位（仍够「一键存入」4 字）。
            controls.put("storageRefresh", new Rect(left.x, 228, 26, 12));
            controls.put("storageClear", new Rect(left.x + 28, 228, 38, 12));
            controls.put("storageDeposit", new Rect(left.x + 68, 228, 34, 12));
            controls.put("storageSort", new Rect(left.x + 104, 228, 28, 12));
            // 物品搜索框（过滤展开箱子的槽位）
            controls.put("storageSearch", new Rect(left.x + 2, left.y + 16, 118, 10));
            // [CHANGED] 会话 #21-B：操作说明按钮 —— 位于范围输入框（radiusInput y=30）上方、
            // 与展开按钮同 Y（12），点击 toggle 帮助面板。此前操作提示用指针/底部大字挡视野，
            // 玩家决定改为按钮触发、不用时隐藏。仅左栏展开时渲染。
            controls.put("helpButton", new Rect(left.x + 2, 12, 60, 14));
            // 右栏底部操作：清空 / 批量出售 / 批量买入（一键买入紧贴一键出售之下）
            controls.put("cartClear", new Rect(right.x, 154, 40, 12));
            controls.put("cartSell", new Rect(right.x + 42, 154, 88, 12));
            controls.put("cartBuy", new Rect(right.x + 42, 168, 88, 12));
            // 右栏统计行（购物车网格之下、数量控制之上）
            // [CHANGED] 会话 #20-B：行距 8px → 11px（默认字形高 9px，8px 行距导致三行上下重叠）
            controls.put("cartCapacity", new Rect(right.x, right.y + 76, RIGHT_W, 8));
            controls.put("cartItems", new Rect(right.x, right.y + 87, RIGHT_W, 8));
            controls.put("cartTotal", new Rect(right.x, right.y + 98, RIGHT_W, 8));
            // 购物车数量控制（右栏操作区）：1x / 32x / 64x / 清空 + 自定义数量输入
            controls.put("qtyOne", new Rect(right.x, 130, 26, 12));
            controls.put("qtyHalf", new Rect(right.x + 30, 130, 26, 12));
            controls.put("qtyStack", new Rect(right.x + 60, 130, 26, 12));
            controls.put("qtyClear", new Rect(right.x + 90, 130, 26, 12));
            controls.put("quantityBox", new Rect(right.x + 2, 142, 64, 12));
            controls.put("quantityApply", new Rect(right.x + 68, 142, 30, 12));
            // 范围输入框（默认 16，上限 256）+ 右侧「一键出售(整箱全部)」按钮 +
            // 分类循环 + 可售筛选。sellWhole 紧贴左栏右缘（x 92..140 = left.right()），
            // 与收窄后的 radiusInput（x 42..82）不重叠。
            controls.put("radiusInput", new Rect(left.x + 34, left.y + 2, 40, 12));
            controls.put("sellWhole", new Rect(left.x + 84, left.y + 2, 48, 12));
            controls.put("slotCategory", new Rect(left.x + 2, left.y + 28, 78, 12));
            controls.put("filterSell", new Rect(left.x + 82, left.y + 28, 36, 12));
            // 收起按钮：左栏收起时贴窗口左缘（避免压住搜索框），否则贴左栏网格右缘；
            // 右栏收起时贴窗口右缘，否则贴右栏网格右缘。
            // [CHANGED] Bug 3：展开态与收起态 Y 统一为 12（与顶行分页按钮 pagePrev/pageNext
            // 完全对齐，玩家反馈「收起时与转页按键 Y 轴对齐、X 轴靠拢」）；右栏收起态 X
            // windowWidth-30 → windowWidth-22（按钮宽 14 → 右缘恰贴窗口右内边距 MARGIN_R=8，
            // 更靠右缘）。[CHANGED] 会话 #21-B：左栏收起态 x MARGIN_L=8 → 4（更贴窗口左缘，
            // 仍不与收起态搜索框 leftReserve 起点 x=22 冲突）。
            controls.put("collapseLeft", new Rect(
                    leftCollapsed ? MARGIN_L - 4 : left.x + LEFT_W - 18,
                    12, 14, 14));
            controls.put("collapseRight", new Rect(
                    rightCollapsed ? windowWidth - 22 : right.x + CART_COLS * 18 - 18,
                    12, 14, 14));
            // 预览模态（居中；窄窗口时收窄以保持在窗口内）。高度需容纳：
            // 标题 + 来源行 + 仓储信息行 + 6 行物品 + 总计/跳过/截断 + 按钮
            int modalW = Math.min(200, windowWidth - 8);
            Rect preview = new Rect((windowWidth - modalW) / 2, 30, modalW, 160);
            controls.put("previewModal", preview);
            controls.put("previewCancel", new Rect(preview.x + 20, preview.y + 132, 58, 14));
            controls.put("previewConfirm", new Rect(preview.x + 84, preview.y + 132, 78, 14));
            // [CHANGED] 会话 #21-B：操作说明帮助面板（点击 helpButton 切换显示；居中于窗口）。
            // 宽 188，标题 + 5 行说明 × 11px + 上下留白 → 高 88，放 24..112。
            int helpW = Math.min(188, windowWidth - 8);
            controls.put("helpModal", new Rect((windowWidth - helpW) / 2, 24, helpW, 88));
            // [CHANGED] 会话 #21-C：一键出售模式选择弹窗（点击 sellWhole 显示；居中于窗口）。
            // 标题 + 左右两个选项（全部/展开）+「不再提示」勾选框 + 关闭。高 82 放 24..106。
            int swW = Math.min(184, windowWidth - 8);
            Rect swModal = new Rect((windowWidth - swW) / 2, 24, swW, 82);
            controls.put("sellWholeModal", swModal);
            controls.put("sellWholeAll", new Rect(swModal.x + 10, swModal.y + 18, 76, 24));
            controls.put("sellWholeExpanded", new Rect(swModal.x + 96, swModal.y + 18, 78, 24));
            controls.put("sellWholeDontAsk", new Rect(swModal.x + 10, swModal.y + 48, 130, 12));
            controls.put("sellWholeClose", new Rect(swModal.x + swW - 30, swModal.y + 62, 20, 12));
            // [NEW] 会话 #21-H 修订：仓储分类选择弹窗（点击 slotCategory 显示；居中于窗口）。
            // 标题 14 + 可视 9 行 × 12 + 上下留白 → 高 128；宽 168 放 24..152。
            int catW = Math.min(168, windowWidth - 8);
            controls.put("categoryModal", new Rect((windowWidth - catW) / 2, 24, catW, 128));
            return new Layout(windowWidth, HEIGHT, left, middle, right, inventoryX, INVENTORY_Y,
                    HOTBAR_Y, !leftCollapsed, !rightCollapsed, Map.copyOf(controls));
        }

        Rect search() {
            return controls.get("search");
        }

        /** [NEW] 会话 #21-H：中栏目录模式指示器（学习/全高亮）。 */
        Rect modeText() {
            return controls.get("modeText");
        }

        Rect pagePrev() {
            return controls.get("pagePrev");
        }

        Rect pageNext() {
            return controls.get("pageNext");
        }

        Rect pageText() {
            return controls.get("pageText");
        }

        /** 中栏存入格（放入物品 = 卖出/学习）。 */
        Rect deposit() {
            return controls.get("deposit");
        }

        /** 中栏钱包显示（完整整数金额）。 */
        Rect wallet() {
            return controls.get("wallet");
        }

        /** 中栏悬停商品价格提示行。 */
        Rect priceHint() {
            return controls.get("priceHint");
        }

        Rect storageRefresh() {
            return controls.get("storageRefresh");
        }

        Rect storageClear() {
            return controls.get("storageClear");
        }

        /** 一键存入（把背包物品存入选中的附近仓储）。 */
        Rect storageDeposit() {
            return controls.get("storageDeposit");
        }

        /** [CHANGED] 会话 #21-E：仓储列表排序按钮（点击循环切换排序档）。 */
        Rect storageSort() {
            return controls.get("storageSort");
        }

        Rect cartClear() {
            return controls.get("cartClear");
        }

        /** 购物车批量出售（待售队列非空时结算仓储待售，否则预览背包出售）。 */
        Rect cartSell() {
            return controls.get("cartSell");
        }

        /** 购物车批量买入（把购物车内全部条目一次买入，紧贴批量出售之下）。 */
        Rect cartBuy() {
            return controls.get("cartBuy");
        }

        Rect qtyOne() {
            return controls.get("qtyOne");
        }

        Rect qtyHalf() {
            return controls.get("qtyHalf");
        }

        Rect qtyStack() {
            return controls.get("qtyStack");
        }

        Rect qtyClear() {
            return controls.get("qtyClear");
        }

        Rect quantityBox() {
            return controls.get("quantityBox");
        }

        Rect quantityApply() {
            return controls.get("quantityApply");
        }

        Rect radiusInput() {
            return controls.get("radiusInput");
        }

        /** 一键出售(整箱全部) 按钮（左栏范围输入右侧）。 */
        Rect sellWhole() {
            return controls.get("sellWhole");
        }

        /** 物品分类循环（左栏）。 */
        Rect slotCategory() {
            return controls.get("slotCategory");
        }

        /** 仓储筛选切换（仅可售）按钮。 */
        Rect filterSell() {
            return controls.get("filterSell");
        }

        Rect cartCapacity() {
            return controls.get("cartCapacity");
        }

        Rect cartItems() {
            return controls.get("cartItems");
        }

        Rect cartTotal() {
            return controls.get("cartTotal");
        }

        Rect collapseLeft() {
            return controls.get("collapseLeft");
        }

        Rect collapseRight() {
            return controls.get("collapseRight");
        }

        Rect previewModal() {
            return controls.get("previewModal");
        }

        /** 操作说明帮助面板（会话 #21-B：helpButton 点击切换）。 */
        Rect helpModal() {
            return controls.get("helpModal");
        }

        /** 操作说明按钮（左栏范围输入框上方，与展开按钮同 Y）。 */
        Rect helpButton() {
            return controls.get("helpButton");
        }

        /** 一键出售模式选择弹窗（会话 #21-C：点击 sellWhole 显示）。 */
        Rect sellWholeModal() {
            return controls.get("sellWholeModal");
        }

        /** 弹窗选项：一键出售（全部）。 */
        Rect sellWholeAll() {
            return controls.get("sellWholeAll");
        }

        /** 弹窗选项：一键出售（展开）。 */
        Rect sellWholeExpanded() {
            return controls.get("sellWholeExpanded");
        }

        /** 弹窗「不再提示」勾选框。 */
        Rect sellWholeDontAsk() {
            return controls.get("sellWholeDontAsk");
        }

        /** 弹窗关闭按钮。 */
        Rect sellWholeClose() {
            return controls.get("sellWholeClose");
        }

        /** [NEW] 会话 #21-H 修订：仓储分类选择弹窗布局。 */
        Rect categoryModal() {
            return controls.get("categoryModal");
        }

        Rect previewCancel() {
            return controls.get("previewCancel");
        }

        Rect previewConfirm() {
            return controls.get("previewConfirm");
        }

        Rect catalogGrid() {
            return new Rect(middle.x(), middle.y() + 2, GRID_COLS * 18, GRID_ROWS * 18);
        }

        Rect cartGrid() {
            return new Rect(right.x() + 2, right.y() + 2, CART_COLS * 18, CART_ROWS * 18);
        }

        /** 底部背包区域（3×9 背包 + 1×9 快捷栏）。 */
        Rect inventoryRect() {
            return new Rect(inventoryX, inventoryY, 9 * 18, 4 * 18);
        }

        /** 左栏仓储列表区几何。 */
        int listTop() {
            return left.y() + 44;
        }

        int listRowHeight() {
            return 14;
        }

        int listRows() {
            return 4;
        }

        Rect storageSearch() {
            return controls.get("storageSearch");
        }

        /** 预览条目列表区域（每页 {@value #PREVIEW_ROWS} 行，可滚动查看全部条目）。 */
        Rect previewLines() {
            Rect modal = previewModal();
            return new Rect(modal.x + 10, modal.y + 40, modal.width - 20, PREVIEW_ROWS * 11);
        }

        /** 整个窗口矩形（0,0 起），用于越界断言与命中测试。 */
        Rect window() {
            return new Rect(0, 0, width, height);
        }

        boolean contains(Rect rect) {
            return rect.x >= 0 && rect.y >= 0 && rect.right() <= width && rect.bottom() <= height;
        }
    }

    record CartLine(String itemId, int count) {
    }

    static final class Cart {
        private final int capacity;
        private final int stackLimit;
        private final List<CartLine> lines = new ArrayList<>();

        Cart(int capacity, int stackLimit) {
            this.capacity = capacity;
            this.stackLimit = stackLimit;
        }

        boolean add(String itemId, int count) {
            if (count <= 0) {
                return false;
            }
            for (int i = 0; i < lines.size(); i++) {
                CartLine line = lines.get(i);
                if (line.itemId().equals(itemId)) {
                    long merged = (long) line.count() + count;
                    lines.set(i, new CartLine(itemId, (int) Math.min(stackLimit, merged)));
                    return true;
                }
            }
            if (lines.size() >= capacity) {
                return false;
            }
            lines.add(new CartLine(itemId, Math.min(stackLimit, Math.max(1, count))));
            return true;
        }

        void setCount(int index, int count) {
            if (index < 0 || index >= lines.size()) {
                return;
            }
            if (count <= 0) {
                lines.remove(index);
            } else {
                CartLine line = lines.get(index);
                lines.set(index, new CartLine(line.itemId(), Math.min(stackLimit, count)));
            }
        }

        void remove(String itemId) {
            lines.removeIf(line -> line.itemId().equals(itemId));
        }

        void clear() {
            lines.clear();
        }

        boolean isEmpty() {
            return lines.isEmpty();
        }

        int size() {
            return lines.size();
        }

        CartLine get(int index) {
            return lines.get(index);
        }

        int totalItems() {
            return lines.stream().mapToInt(CartLine::count).sum();
        }

        long total(Map<String, Long> prices) {
            long total = 0;
            try {
                for (CartLine line : lines) {
                    total = Math.addExact(total, Math.multiplyExact(prices.getOrDefault(line.itemId(), 0L), line.count()));
                }
                return total;
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }

        List<CartLine> lines() {
            return List.copyOf(lines);
        }
    }

    enum SellSource { INVENTORY, STORAGE }

    /** 条目被跳过出售的原因（客户端展示"已跳过 N 项"明细时使用）。 */
    enum SkipReason { ZERO_COUNT, NO_PRICE, BLACKLISTED, NOT_ALLOWED }

    record SourceLine(String itemId, String displayName, int count) {
    }

    record PreviewLine(String itemId, String displayName, int count, long unitPrice, long subtotal) {
    }

    record SellPreview(List<PreviewLine> lines, long total, int skipped, boolean truncated,
                       boolean requiresConfirmation, SellSource source, List<SkipReason> skipReasons) {
        static SellPreview scan(List<SourceLine> source, Map<String, Long> prices, int limit,
                                long confirmThreshold, SellSource sellSource,
                                Set<String> blockedItems, Set<String> allowedItems,
                                boolean allowlistEnabled) {
            Map<String, SourceLine> aggregated = new LinkedHashMap<>();
            int skipped = 0;
            List<SkipReason> reasons = new ArrayList<>();
            for (SourceLine line : source) {
                SkipReason reason = skipReason(line, prices.getOrDefault(line.itemId(), 0L),
                        blockedItems, allowedItems, allowlistEnabled);
                if (reason != null) {
                    skipped++;
                    reasons.add(reason);
                    continue;
                }
                SourceLine previous = aggregated.get(line.itemId());
                aggregated.put(line.itemId(), previous == null ? line
                        : new SourceLine(line.itemId(), line.displayName(), previous.count() + line.count()));
            }
            boolean truncated = aggregated.size() > limit;
            List<PreviewLine> lines = new ArrayList<>();
            long total = 0;
            for (SourceLine line : aggregated.values()) {
                if (lines.size() >= limit) {
                    break;
                }
                long unit = prices.get(line.itemId());
                long subtotal;
                try {
                    subtotal = Math.multiplyExact(unit, line.count());
                    total = Math.addExact(total, subtotal);
                } catch (ArithmeticException ignored) {
                    subtotal = Long.MAX_VALUE;
                    total = Long.MAX_VALUE;
                }
                lines.add(new PreviewLine(line.itemId(), line.displayName(), line.count(), unit, subtotal));
            }
            return new SellPreview(List.copyOf(lines), total, skipped, truncated,
                    confirmThreshold > 0 && total >= confirmThreshold, sellSource, List.copyOf(reasons));
        }

        /** 返回跳过原因；{@code null} 表示该条目可入预览。判定顺序：数量 → 价格 → 黑名单 → 白名单。 */
        static SkipReason skipReason(SourceLine line, long price, Set<String> blockedItems,
                                     Set<String> allowedItems, boolean allowlistEnabled) {
            if (line.count() <= 0) {
                return SkipReason.ZERO_COUNT;
            }
            if (price <= 0) {
                return SkipReason.NO_PRICE;
            }
            if (blockedItems != null && blockedItems.contains(line.itemId())) {
                return SkipReason.BLACKLISTED;
            }
            if (allowlistEnabled && (allowedItems == null || !allowedItems.contains(line.itemId()))) {
                return SkipReason.NOT_ALLOWED;
            }
            return null;
        }
    }

    /** [CHANGED] 会话 #10：统计同 ID 物品在扫描源中的总数量（Shift 右键卖整组用；纯逻辑便于测试）。 */
    static long groupCount(List<SourceLine> source, String itemId) {
        long total = 0L;
        for (SourceLine line : source) {
            if (line.itemId().equals(itemId)) {
                total += line.count();
            }
        }
        return total;
    }

    /** [CHANGED] 会话 #10：Shift 贩卖是否需二次确认。阈值/数量非法恒 false（与
     *  {@link SellPreview#scan} 的 {@code confirmThreshold > 0} 守卫一致，保证配置
     *  0=关闭确认生效）；乘法溢出按需确认（安全处理）。 */
    static boolean shiftSellNeedsConfirm(long count, long unitPrice, long confirmThreshold) {
        if (confirmThreshold <= 0 || count <= 0) {
            return false;
        }
        try {
            return Math.multiplyExact(unitPrice, count) >= confirmThreshold;
        } catch (ArithmeticException e) {
            return true;
        }
    }

    /**
     * 仓储出售预览：在 {@link SellPreview} 之上附加仓储元数据（来源=仓储、选中仓储
     * 名称/ID、SELL 权限、revision）。无 SELL 权限或预览为空时禁止确认。
     */
    record StorageSellPreview(SellPreview preview, String storageName, String storageId,
                              boolean permissionAllowed, long revision) {
        boolean canConfirm() {
            return permissionAllowed && revision >= 0
                    && preview != null && !preview.lines().isEmpty();
        }
    }

    enum Operation { NONE, BUY, INVENTORY_SELL, STORAGE_SELL }

    enum ResultAction { IGNORE, KEEP_DRAFT, CLEAR_CART, CLEAR_PREVIEW, REFRESH_STORAGE }

    static final class Workflow {
        private boolean pending;
        private Operation operation = Operation.NONE;
        private int requestNonce = -1;
        private String messageKey = "";

        boolean begin(Operation next, int currentNonce) {
            if (pending || next == Operation.NONE) {
                return false;
            }
            pending = true;
            operation = next;
            requestNonce = currentNonce;
            messageKey = "poketrade.exchange.status.pending";
            return true;
        }

        ResultAction complete(int nonce, Operation resultOperation, TradeResult result) {
            if (!pending || nonce != requestNonce + 1 || resultOperation != operation) {
                return ResultAction.IGNORE;
            }
            pending = false;
            messageKey = resultKey(operation, result == null ? -1 : result.ordinal());
            ResultAction action = result != TradeResult.SUCCESS ? ResultAction.KEEP_DRAFT : switch (operation) {
                case BUY -> ResultAction.CLEAR_CART;
                case INVENTORY_SELL -> ResultAction.CLEAR_PREVIEW;
                case STORAGE_SELL -> ResultAction.REFRESH_STORAGE;
                case NONE -> ResultAction.IGNORE;
            };
            operation = Operation.NONE;
            requestNonce = nonce;
            return action;
        }

        boolean pending() {
            return pending;
        }

        String messageKey() {
            return messageKey;
        }
    }

    static boolean isCurrentCatalogResponse(String latestRequestId, String responseRequestId) {
        return latestRequestId != null && latestRequestId.equals(responseRequestId);
    }

    static boolean matchesCatalogSearch(String query, String displayName, String itemId, String category) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return true;
        }
        return searchable(displayName).contains(normalized)
                || searchable(itemId).contains(normalized)
                || searchable(category).contains(normalized);
    }

    /**
     * 客户端本地化二次过滤：服务端已按英文显示名过滤并截断 500 条；客户端在展示前
     * 用本地化显示名（{@code displayNameResolver} 注入，屏幕传 {@code itemId -> 本地化名}，
     * 测试传固定映射）再过滤一次。空查询不做二次过滤，绝不放大服务端截断结果。
     */
    static List<ExchangeCatalogPacket.EntryWire> filterCatalogEntries(
            List<ExchangeCatalogPacket.EntryWire> entries, String searchQuery,
            Function<String, String> displayNameResolver) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        String normalized = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return List.copyOf(entries);
        }
        List<ExchangeCatalogPacket.EntryWire> filtered = new ArrayList<>();
        for (ExchangeCatalogPacket.EntryWire entry : entries) {
            String displayName = displayNameResolver == null ? "" : displayNameResolver.apply(entry.itemId());
            if (matchesCatalogSearch(normalized, displayName, entry.itemId(), entry.category())) {
                filtered.add(entry);
            }
        }
        return List.copyOf(filtered);
    }

    private static String searchable(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /**
     * 仓储扫描半径档位（点击切换按钮）：16 → 32 → 64 → 128 → 256 → 512 → 648 → 16。
     * 每击翻倍，达到最大 648 后重置为默认 16；非档位值（如历史输入残留）跳到下一个更大档位。
     */
    static final int[] STORAGE_RADIUS_STEPS = {16, 32, 64, 128, 256, 512, 648};

    /** 点击切换后的下一个半径档位。 */
    static int nextStorageRadius(int currentRadius) {
        for (int step : STORAGE_RADIUS_STEPS) {
            if (currentRadius < step) {
                return step;
            }
        }
        return STORAGE_RADIUS_STEPS[0];
    }

    /**
     * 滚动偏移上界钳制：目录/购物车/预览/快照/仓储列表的滚动值统一收敛到
     * [0, max(0, totalRows - pageSize)]，防止越界滚出内容区。
     */
    static int clampScroll(int offset, int totalRows, int pageSize) {
        int max = Math.max(0, totalRows - pageSize);
        return Math.max(0, Math.min(offset, max));
    }

    /** 分页总数：按每页 {@code pageSize} 行将 {@code totalRows} 拆成向上取整的页数。 */
    static int pageCount(int totalRows, int pageSize) {
        if (totalRows <= 0 || pageSize <= 0) {
            return 0;
        }
        return (totalRows + pageSize - 1) / pageSize;
    }

    /**
     * 跑马灯横向偏移（Bug 8）：超宽文本（如过长玩家名）每 {@code speedMs} 毫秒左移 1px，
     * 相位周期循环；返回一个「文本副本<b>左端</b> X」的基准（右缘向左一个副本间距处）。
     * 调用方从返回的基准起，以 {@link #marqueePeriod} 为步长向右循环，绘制所有与可见区
     * {@code [leftEdge, rightEdge]} 相交的副本——任何相位可见区内恒有文字在流动：
     * 一个副本尾部滚出左缘的同一刻，下一个副本头部已从右缘进入（「头追尾」），
     * 全程连续、无大段空白、无相位归零瞬移。纯函数，任意帧率下平滑连续。
     *
     * <p>[CHANGED] 会话 #24c：由「单副本 + gap 空档循环」改为「多副本传送带」。
     * 旧实现文字滚出左缘后要空走 {@code gap} 才从右缘重新出现，且单副本滚到右端时
     * 盖住展开按钮、滚到左端时盖住末影徽标（z 轴穿模）。传送带模式下文字流永远不断，
     * 配合调用方把徽标/按钮后绘（盖住滚过的文字），两端穿模一并消除。</p>
     *
     * @param nowMillis 当前毫秒时间戳
     * @param speedMs   每移动 1px 的毫秒数（越大越慢）
     * @param nameWidth 文本像素宽度（超宽判定由调用方负责）
     * @param gap       相邻副本间距（px，同一时刻两副本间的空隙，避免粘字）
     * @param leftEdge  可见区左边界（scissor 裁剪区外部分；不要求文字完全滚出此线）
     * @param rightEdge 可见区右边界（文字流自此向左流动、自右缘进入）
     */
    static int marqueeX(long nowMillis, int speedMs, int nameWidth, int gap,
                        int leftEdge, int rightEdge) {
        // 传送带节距 = 副本间距；相位按节距循环，基准副本左端定在右缘向左一个节距处，
        // 保证 [leftEdge, rightEdge] 内任意相位恒有副本相交（period ≤ 可见区宽 + nameWidth 时严格成立）。
        int period = Math.max(1, nameWidth + gap);
        long phase = (nowMillis / Math.max(1, speedMs)) % period;
        return rightEdge - period - (int) phase;
    }

    /** 相邻文本副本的间距（px），配合 {@link #marqueeX} 向右循环绘制衔接副本。 */
    static int marqueePeriod(int nameWidth, int gap) {
        return Math.max(1, nameWidth + gap);
    }

    // ===== [CHANGED] 会话 #11：仓储手风琴网格行数（问题 2）与首次查询门（问题 1） =====

    /**
     * 容器容量对应的网格行数（不裁剪到面板高度上限）。
     * <p>仅用于「滚动范围」：滚动条点击跳页、鼠标滚轮、渲染 scroll 钳制。
     * 若此处也裁剪，双箱 54 槽→8 行但可见仅 7 行时 maxOffset=0，第 8 排永远不可达。</p>
     */
    static int accordionContentRows(int slotCount, int cols) {
        if (cols <= 0) {
            return 1;
        }
        return Math.max(1, (slotCount + cols - 1) / cols);
    }

    /**
     * 容器容量对应的可见网格行数（裁剪到面板高度上限 {@code maxRows}）。
     * <p>仅用于「展开面板高度」：单箱 27/7=4 行、双箱 54/7→8 行被裁剪为 7 可见行，
     * 剩余第 8 排经滚动访问。空箱也返回至少 1 行占位骨架。</p>
     */
    static int accordionVisibleRows(int slotCount, int cols, int maxRows) {
        return Math.max(1, Math.min(maxRows, accordionContentRows(slotCount, cols)));
    }

    /**
     * 首次查询门：首个仓储查询回包才消费「自动展开首个仓储」，后续回包一律不再展开。
     * <p>修复问题 1：10 秒自动刷新回包会走到 {@code onQueryResponse}，若用
     * {@code expandedStorages.isEmpty()} 直接判断，玩家把全部仓储收起来后集合为空，
     * 会被后续刷新回包误判为「首次打开」而强制展开。本门让「空集兜底」只在首个回包生效。</p>
     */
    static final class FirstQueryGate {
        private boolean received;

        /** @param visibleNotEmpty 本次回包可见仓储非空
         *  @param expandedEmpty    当前展开集合为空
         *  @return 是否应自动展开首个仓储（仅首个回包可能为 true，且需 visible 非空 + 全收起） */
        boolean onQuery(boolean visibleNotEmpty, boolean expandedEmpty) {
            if (received) {
                return false;
            }
            received = true;
            return visibleNotEmpty && expandedEmpty;
        }
    }

    static String resultKey(Operation operation, int resultCode) {
        TradeResult[] values = TradeResult.values();
        if (resultCode < 0 || resultCode >= values.length) {
            return "poketrade.exchange.result.internal_error";
        }
        String result = values[resultCode].name().toLowerCase(Locale.ROOT);
        if (operation == Operation.NONE) {
            return "poketrade.exchange.result." + result;
        }
        return "poketrade.exchange.result." + operation.name().toLowerCase(Locale.ROOT) + "." + result;
    }

    static String compactAmount(long amount) {
        long absolute = amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount);
        if (absolute < 100_000L) {
            return Long.toString(amount);
        }
        String[] suffixes = {"K", "M", "B", "T", "E"};
        double scaled = absolute;
        int suffix = -1;
        while (scaled >= 1_000.0 && suffix < suffixes.length - 1) {
            scaled /= 1_000.0;
            suffix++;
        }
        String value = scaled >= 100.0
                ? String.format(Locale.ROOT, "%.0f", scaled)
                : String.format(Locale.ROOT, "%.1f", scaled);
        if (value.endsWith(".0")) {
            value = value.substring(0, value.length() - 2);
        }
        return (amount < 0 ? "-" : "") + value + suffixes[suffix];
    }

    /** 完整整数金额（千分位），不使用 K/M 缩写——交易所所有金额显示统一走这里。 */
    static String formatAmount(long amount) {
        return String.format(Locale.US, "%,d", amount);
    }

    /**
     * 钱包余额缩写（会话 #25）：大额用 k/m/b/t 缩写，避免大数撑出钱包显示区域被截断/穿模。
     * 仅钱包余额显示使用；其余金额（价格/小计/总价）保持 {@link #formatAmount} 完整千分位，
     * 悬停查看完整值时调用方用 {@link #formatAmount} 渲染 tooltip。
     * <ul>
     *   <li>&lt; 1_000：原样整数（950）</li>
     *   <li>≥ 1k：一位小数缩写并去尾零（1000→1k、1500→1.5k、12345→12.3k）</li>
     *   <li>≥ 1m / 1b / 1t 同理；负数带 -（-5000→-5k）</li>
     * </ul>
     */
    // [CHANGED] 会话 #27：改为 public，供 com.pokeemc.mixin 包（Pixelmon 钱包缩写注入）调用。
    public static String formatWallet(long amount) {
        if (amount >= 0) {
            if (amount >= 1_000_000_000_000L) return compact(amount, 1_000_000_000_000L) + "t";
            if (amount >= 1_000_000_000L) return compact(amount, 1_000_000_000L) + "b";
            if (amount >= 1_000_000L) return compact(amount, 1_000_000L) + "m";
            if (amount >= 1_000L) return compact(amount, 1_000L) + "k";
            return String.valueOf(amount);
        }
        long abs = -amount;
        if (abs >= 1_000_000_000_000L) return "-" + compact(abs, 1_000_000_000_000L) + "t";
        if (abs >= 1_000_000_000L) return "-" + compact(abs, 1_000_000_000L) + "b";
        if (abs >= 1_000_000L) return "-" + compact(abs, 1_000_000L) + "m";
        if (abs >= 1_000L) return "-" + compact(abs, 1_000L) + "k";
        return String.valueOf(amount);
    }

    /** 单位值一位小数缩写：整值去 .0（1000→"1"、1500→"1.5"）。 */
    private static String compact(long abs, long unit) {
        double v = abs / (double) unit;
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.US, "%.1f", v);
    }

    /**
     * [CHANGED] 会话 #12 问题 A：出售预览单行的「名称 / 价格」两列几何。
     * 价格右对齐到弹窗右缘 -24（千分位多位永不越界），名称整行截断到价格起始前 6px，
     * 两者永不重叠。纯函数便于单元测试。
     *
     * @param modal          预览弹窗矩形（价格右缘 = modal.right() - 24）
     * @param lines          条目列表区矩形（名称左缘 = lines.x()）
     * @param subtotalWidth  价格文本已截断后的实际像素宽度
     * @return priceX（价格绘制 x 局部坐标）、nameMax（名称整行最大像素宽）
     */
    static PreviewRowLayout previewRowLayout(Rect modal, Rect lines, int subtotalWidth) {
        int priceX = modal.right() - 24 - subtotalWidth;
        int nameMax = Math.max(8, priceX - lines.x() - 6);
        return new PreviewRowLayout(priceX, nameMax);
    }

    /** 出售预览单行两列几何（见 {@link #previewRowLayout}）。 */
    record PreviewRowLayout(int priceX, int nameMax) {
    }

    /** 拿起后抑制同一手势立即回存的时间窗（毫秒）。 */
    static final long JUST_PICKED_MS = 200L;

    /**
     * [FIXED] 会话 #15-C：拿起抑制守卫的安全判定。
     *
     * <p>原 ExchangeScreen 用 {@code Long.MIN_VALUE} 作「从未拿起」哨兵，且守卫写成
     * {@code now - pendingPickupAt < JUST_PICKED_MS}——哨兵使减法下溢为负、恒小于窗口，
     * 每次把物品拖入仓储格子的松开事件都被吞掉（仓储无法放置物品）。</p>
     *
     * <p>语义：{@code pickupAtMillis <= 0} 表示「从未拿起」永不抑制；否则仅当耗时落在
     * {@code [0, JUST_PICKED_MS)} 内才抑制（防拿起同一手势松开立即回存）。耗时取非负，
     * 未来时间戳（时钟回拨）同样不抑制。</p>
     *
     * @param pickupAtMillis 上次拿起的时间戳；&lt;=0 视为从未拿起
     * @param nowMillis      当前时间戳
     * @return true 表示应抑制本次松开回存（拿起后同一手势窗口内）
     */
    static boolean immediateRedepositSuppressed(long pickupAtMillis, long nowMillis) {
        if (pickupAtMillis <= 0) {
            return false;
        }
        long elapsed = nowMillis - pickupAtMillis;
        return elapsed >= 0 && elapsed < JUST_PICKED_MS;
    }
}
