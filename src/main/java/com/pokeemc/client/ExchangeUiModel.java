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

final class ExchangeUiModel {
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
        /** 右栏购物车网格：7 列 × 4 行，只渲染 27 格（容量 27，点击与渲染一致） */
        static final int CART_COLS = 7;
        static final int CART_ROWS = 4;
        static final int CART_CELLS = 27;
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
            // 中栏顶行：搜索框 + ‹/› 分页按钮（独立于左栏仓储搜索）
            controls.put("search", new Rect(middleX + leftReserve, 12,
                    Math.max(42, middle.width() - 42 - rightReserve - leftReserve), 14));
            controls.put("pagePrev", new Rect(middle.right() - 36 - rightReserve, 12, 18, 14));
            controls.put("pageNext", new Rect(middle.right() - 18 - rightReserve, 12, 18, 14));
            // 商品网格下方：存入格 + 钱包（整数金额）+ 页码 + 悬停价格
            controls.put("deposit", new Rect(middleX + 2, 122, 18, 18));
            controls.put("wallet", new Rect(middleX + 24, 128, 96, 8));
            controls.put("pageText", new Rect(middleX + 126, 128, 40, 8));
            controls.put("priceHint", new Rect(middleX + 2, 144, 130, 8));
            // 左栏底部操作：刷新 / 清空待售 / 存入（批量出售已移到购物车）
            controls.put("storageRefresh", new Rect(left.x, 228, 28, 12));
            controls.put("storageClear", new Rect(left.x + 30, 228, 30, 12));
            controls.put("storageDeposit", new Rect(left.x + 62, 228, 40, 12));
            // 物品搜索框（过滤展开箱子的槽位）
            controls.put("storageSearch", new Rect(left.x + 2, left.y + 16, 118, 10));
            // 右栏底部操作：清空 / 批量出售 / 批量买入（一键买入紧贴一键出售之下）
            controls.put("cartClear", new Rect(right.x, 154, 40, 12));
            controls.put("cartSell", new Rect(right.x + 42, 154, 88, 12));
            controls.put("cartBuy", new Rect(right.x + 42, 168, 88, 12));
            // 右栏统计行（购物车网格之下、数量控制之上）
            controls.put("cartCapacity", new Rect(right.x, right.y + 76, RIGHT_W, 8));
            controls.put("cartItems", new Rect(right.x, right.y + 84, RIGHT_W, 8));
            controls.put("cartTotal", new Rect(right.x, right.y + 92, RIGHT_W, 8));
            // 购物车数量控制（右栏操作区）：1x / 32x / 64x / 清空 + 自定义数量输入
            controls.put("qtyOne", new Rect(right.x, 130, 26, 12));
            controls.put("qtyHalf", new Rect(right.x + 30, 130, 26, 12));
            controls.put("qtyStack", new Rect(right.x + 60, 130, 26, 12));
            controls.put("qtyClear", new Rect(right.x + 90, 130, 26, 12));
            controls.put("quantityBox", new Rect(right.x + 2, 142, 64, 12));
            controls.put("quantityApply", new Rect(right.x + 68, 142, 30, 12));
            // 范围输入框（默认 16，上限 256）+ 分类循环 + 可售筛选
            controls.put("radiusInput", new Rect(left.x + 34, left.y + 2, 92, 12));
            controls.put("slotCategory", new Rect(left.x + 2, left.y + 28, 78, 12));
            controls.put("filterSell", new Rect(left.x + 82, left.y + 28, 36, 12));
            // 收起按钮：左栏收起时贴窗口左缘（避免压住搜索框），否则贴左栏网格右缘；
            // 右栏收起时贴窗口右缘，否则贴右栏网格右缘
            controls.put("collapseLeft", new Rect(
                    leftCollapsed ? MARGIN_L : left.x + LEFT_W - 18,
                    left.y - 14, 14, 14));
            controls.put("collapseRight", new Rect(
                    rightCollapsed ? windowWidth - 30 : right.x + CART_COLS * 18 - 18,
                    middle.y - 14, 14, 14));
            // 预览模态（居中；窄窗口时收窄以保持在窗口内）。高度需容纳：
            // 标题 + 来源行 + 仓储信息行 + 6 行物品 + 总计/跳过/截断 + 按钮
            int modalW = Math.min(200, windowWidth - 8);
            Rect preview = new Rect((windowWidth - modalW) / 2, 30, modalW, 160);
            controls.put("previewModal", preview);
            controls.put("previewCancel", new Rect(preview.x + 20, preview.y + 132, 58, 14));
            controls.put("previewConfirm", new Rect(preview.x + 84, preview.y + 132, 78, 14));
            return new Layout(windowWidth, HEIGHT, left, middle, right, inventoryX, INVENTORY_Y,
                    HOTBAR_Y, !leftCollapsed, !rightCollapsed, Map.copyOf(controls));
        }

        Rect search() {
            return controls.get("search");
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

    /** 存储浏览半径循环：16 -> 32 -> 64 -> 16（规格 6：16/32/64 + 自定义）；其它输入一律回到 16。 */
    static int nextStorageRadius(int currentRadius) {
        return switch (currentRadius) {
            case 16 -> 32;
            case 32 -> 64;
            case 64 -> 16;
            default -> 16;
        };
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
}
