package com.pokeemc.client;

import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.exchange.ExchangeService;
import com.pokeemc.menu.ExchangeMenu;
import com.pokeemc.network.ExchangeBuyPacket;
import com.pokeemc.network.ExchangeCatalogPacket;
import com.pokeemc.network.ExchangeSellPacket;
import com.pokeemc.network.QueryStoragesPacket;
import com.pokeemc.network.StorageDepositPacket;
import com.pokeemc.network.StorageDepositCarriedPacket;
import com.pokeemc.network.StorageManagePacket;
import com.pokeemc.network.StorageMovePacket;
import com.pokeemc.network.StorageSellPacket;
import com.pokeemc.network.StorageSnapshotPacket;
import com.pokeemc.network.StorageWithdrawCarriedPacket;
import com.pokeemc.storage.StoragePermission;
import com.poketrade.api.TradeResult;
import com.poketrade.api.price.PriceSort;
import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageEndpoint;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageQuery;
import com.poketrade.api.storage.StorageSnapshot;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 宝可梦交易所三栏屏幕：
 * 左栏（可收起）附近仓储（查询/快照/出售，复用 StorageSellPacket 通道）；
 * 中栏商品目录（搜索、类别标签、排序循环、买卖双价、玩家背包）；
 * 右栏（可收起）27 格购物车（数量 1/半组/一组/清空，批量买入）。
 *
 * <p>几何<b>单一来源</b>：所有坐标均读取 {@link ExchangeUiModel.Layout}
 * （由 {@code applyLayout()} 在 init/收起切换时重算），屏幕不保留任何影子常量。</p>
 *
 * <p>坐标约定（与 {@link StorageBrowserScreen} 一致）：{@code renderBg} 用全局坐标，
 * {@code renderLabels} 在 translate(leftPos, topPos) 内用局部坐标，{@code mouseClicked}
 * 用全局坐标。</p>
 */
public class ExchangeScreen extends AbstractContainerScreen<ExchangeMenu>
        implements BrowserHost, ExchangeCatalogHost {

    /** 槽位边长（与 {@code ExchangeUiModel.Layout} 网格推导一致）。 */
    private static final int SLOT = 18;
    /** 出售预览条目上限（与服务端截断一致）。 */
    private static final int MAX_SELL_LINES = 27;
    /** 快照每页格数（7 列 × 2 行，{@code Layout.SNAP_COLS * Layout.SNAP_ROWS}）。 */
    private static final int SNAPSHOT_PAGE_CELLS =
            ExchangeUiModel.Layout.SNAP_COLS * ExchangeUiModel.Layout.SNAP_ROWS;
    /**
     * 手风琴展开网格的<b>最大</b>可见行数（面板高度受限：listTop=72，7 列 × 7 行 = 210px &lt; 226 底部按钮）。
     * 实际可见行数按槽位数量自适应（单箱 4 行、双箱 7 行），双箱 8 行时仅最后一排需滚动。
     * Bug #1：原固定 3 行使大箱子 27-53 格几乎不可达——滚动条不可点击、滚轮必须悬停网格上。
     */
    private static final int MAX_ACCORDION_ROWS = 7;

    private final StorageViewModel storage = new StorageViewModel();
    private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
    private final List<ExchangeCatalogPacket.EntryWire> catalog = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();
    private final List<String> blockedItems = new ArrayList<>();
    private final List<String> allowedItems = new ArrayList<>();
    private boolean allowlistEnabled;
    /** 服务端买入/出售总开关（随目录响应下发）。 */
    private boolean buyEnabled = true;
    private boolean sellEnabled = true;
    private final ExchangeUiModel.Cart cart = new ExchangeUiModel.Cart(27, 1024);
    private final ExchangeUiModel.Workflow workflow = new ExchangeUiModel.Workflow();
    /** 仓储出售区：仓储槽位索引 -> 待出售条目（虚拟视图，真实物品留在仓储）。 */
    private final Map<Integer, PendingSell> sellQueue = new LinkedHashMap<>();

    /** 唯一几何来源：init/收起切换时由 {@link #applyLayout()} 重算并落位。 */
    private ExchangeUiModel.Layout layout = ExchangeUiModel.Layout.expanded();
    /** 由布局派生的缓存（不允许孤立字面量）。 */
    private int gridCols = ExchangeUiModel.Layout.GRID_COLS;
    private int cartCols = ExchangeUiModel.Layout.CART_COLS;
    private int snapshotCols = ExchangeUiModel.Layout.SNAP_COLS;
    private int snapshotRows = ExchangeUiModel.Layout.SNAP_ROWS;

    private PriceSort sort = PriceSort.CATEGORY;
    private String activeCategory = "";
    private int selectedCart = -1;
    private int catalogScroll;
    private String catalogRequestId;
    private int cartScroll;
    /** 出售预览分页（每页 6 行，最多 27 行）。 */
    private int previewPage;
    private boolean leftCollapsed;
    private boolean rightCollapsed;
    /** 自适应缩放：当前帧生效的缩放比例与缩放后界面左上角（GUI 逻辑像素）。 */
    private float uiScale = 1.0f;
    private float scaledOriginX;
    private float scaledOriginY;
    private EditBox searchBox;
    /** 自定义数量输入（右栏购物车下方；选中购物车格时可用）。 */
    private EditBox quantityBox;
    /** 仓储扫描范围（点击切换按钮，见 mouseClicked / renderLeftPanel）；当前档位存于 {@link #storage}。 */
    /** 物品搜索框（过滤展开箱子的槽位）。 */
    private EditBox storageSearchBox;
    /** 物品搜索关键字（本地过滤）。 */
    private String itemSearchText = "";
    private String lastSearchText = "";
    private int lastNonce = -1;
    private TradeResult lastResult;
    private String sellMessage = "";
    private int sellMessageColor = PeStyle.TEXT_WARN;
    private long requireConfirmValue;
    private ExchangeUiModel.SellPreview sellPreview;
    /** 仓储出售预览元数据（来源=仓储时非空，含 SELL 权限门控与 revision）。 */
    private ExchangeUiModel.StorageSellPreview storagePreview;
    private boolean previewConfirmed;
    /** 仓储出售成功且已请求刷新快照：下一次快照返回时才清空出售区/预览。 */
    private boolean awaitingSnapshotClear;
    /** 正在进行“拿起”操作（回执时显示拿起文案）。 */
    private boolean pendingPickup;
    /** 正在把 carried 存入其他仓储（回执时显示转移文案）。 */
    private String pendingCarriedTransferName;
    /** 拖动买：从目录按下的商品（左键）；松开时落到购物车=加入、落到背包=直接买入。 */
    private ExchangeCatalogPacket.EntryWire dragCatalogEntry;
    private int dragCatalogCount;
    private int dragCatalogIndex = -1;
    /** 拖动卖：从玩家背包按下的槽位（左键）；松开到存入格时放回并弹出该物品出售预览。 */
    private int dragFromPlayerSlot = -1;
    /** 分类循环索引（-1 = 全部，其余为 categories 下标）。 */
    private int slotCategoryIndex = -1;
    /** 展开的仓储（按 storageId 字符串）。 */
    private final java.util.Set<String> expandedStorages = new java.util.LinkedHashSet<>();
    /** [CHANGED] 会话 #11：首个仓储查询回包才自动展开首个仓储（修复 10 秒刷新把全收起误判为首次打开）。 */
    private final ExchangeUiModel.FirstQueryGate firstQueryGate = new ExchangeUiModel.FirstQueryGate();
    /** 每个仓储的快照与 revision（展开时拉取）。 */
    private final Map<String, StorageSnapshot> snapshotsByStorage = new java.util.HashMap<>();
    private final Map<String, Long> revisionsByStorage = new java.util.HashMap<>();
    private final Map<String, Integer> storageScrolls = new java.util.HashMap<>();
    /** 手风琴列表滚动（按仓储条目计）。 */
    private int accordionScroll;
    /** 右键菜单（仓储槽位操作）。 */
    private ContextMenu contextMenu;
    /** 一键存入自动溢流队列：按可见顺序待存入的仓储（首个处理完再发下一个）。 */
    private final java.util.ArrayDeque<com.poketrade.api.storage.StorageId> depositOverflowQueue =
            new java.util.ArrayDeque<>();
    /** 自动溢流存入进行中（回执到达时据此判断是否继续发下一个箱子）。 */
    private boolean depositOverflowInFlight;

    // ============ [CHANGED] 会话 #12：屏幕空间文字重画 ============
    /**
     * 文字层级：MAIN 主界面（z=0）、TOP 弹窗/右键菜单（z=400）。
     * 缩放矩阵内字形落在分数像素被线性采样 → 模糊；故几何仍在矩阵内绘制，
     * 全部文字改为记录到 {@link #pendingText}，在 endScaledRender 之后以整数屏幕坐标重放。
     */
    private enum TextLayer { MAIN, TOP }

    private record TextDraw(TextLayer layer, String text, int x, int y, int color, boolean shadow) {
    }

    /** 待重放的文字（每帧 render 开头清空）。 */
    private final java.util.List<TextDraw> pendingText = new java.util.ArrayList<>(64);

    /** 局部坐标 → 屏幕整数坐标（与 beginScaledRender 取整后的原点一致）。 */
    private int screenX(int localX) {
        return Math.round(this.scaledOriginX + localX * this.uiScale);
    }

    private int screenY(int localY) {
        return Math.round(this.scaledOriginY + localY * this.uiScale);
    }

    /** 记录一条待重放文字（带阴影）。 */
    private void recordText(TextLayer layer, String text, int x, int y, int color) {
        recordText(layer, text, x, y, color, true);
    }

    /** 记录一条待重放文字。 */
    private void recordText(TextLayer layer, String text, int x, int y, int color, boolean shadow) {
        pendingText.add(new TextDraw(layer, text, x, y, color, shadow));
    }

    /** 记录按钮文字：排版公式与 PeStyle.button 同源（避免复制 enabled 取色逻辑）。 */
    private void recordButton(TextLayer layer, String label, boolean enabled,
            int x, int y, int w, int h) {
        PeStyle.ButtonText bt = PeStyle.buttonText(this.font, label, enabled, x, y, w, h);
        recordText(layer, bt.label(), bt.textX(), bt.textY(), bt.color());
    }

    /** 记录分段按钮文字：排版公式与 PeStyle.segmented 同源。 */
    private void recordSegmented(TextLayer layer, String label, boolean selected,
            int x, int y, int w, int h) {
        PeStyle.SegmentedText st = PeStyle.segmentedText(this.font, label, selected, x, y, w, h);
        recordText(layer, st.label(), st.textX(), st.textY(), st.color());
    }

    /** 矩阵外重放：以整数屏幕坐标画某一层全部文字。 */
    private void drawPendingText(GuiGraphics g, TextLayer layer) {
        for (TextDraw d : pendingText) {
            if (d.layer() != layer) {
                continue;
            }
            g.drawString(this.font, d.text(), screenX(d.x()), screenY(d.y()), d.color(), d.shadow());
        }
    }

    private record ContextMenu(int x, int y, StorageItemSlot slot, StorageId storageId) {
    }

    private record PendingSell(int slotIndex, String itemId, int count, long fingerprint) {
    }

    public ExchangeScreen(ExchangeMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = this.layout.width();
        this.imageHeight = this.layout.height();
        this.titleLabelX = 8;
        this.titleLabelY = 7;
    }

    // ================= 生命周期 =================

    @Override
    protected void init() {
        super.init();
        applyLayout();
        this.searchBox = new EditBox(this.font, 0, 0, 10, 10,
                Component.translatable("poketrade.exchange.search.hint"));
        this.searchBox.setMaxLength(32);
        // 无边框：背景由 renderBg 的凹槽绘制，避免默认黑底方块
        this.searchBox.setBordered(false);
        this.searchBox.setTextColor(0xFF303030);
        this.searchBox.setTextColorUneditable(0xFF303030);
        // C1：输入变化（中文输入法/粘贴等都会经过 setValue/insertText）即触发目录请求；
        // 比较新值与上次值避免 onCatalogResponse 回填或重复字符造成的循环
        this.searchBox.setResponder(text -> {
            String value = text == null ? "" : text;
            if (!value.equals(this.lastSearchText)) {
                this.lastSearchText = value;
                requestCatalog();
            }
        });
        this.addWidget(this.searchBox);
        // 自定义数量输入（右栏购物车下方，选中购物车格时可用；仅数字 1-64）
        this.quantityBox = new EditBox(this.font, 0, 0, 10, 10,
                Component.translatable("poketrade.exchange.cart.quantity"));
        this.quantityBox.setMaxLength(2);
        this.quantityBox.setFilter(s -> s.chars().allMatch(Character::isDigit));
        this.quantityBox.setBordered(false);
        this.quantityBox.setTextColor(0xFF303030);
        this.quantityBox.setTextColorUneditable(0xFF303030);
        this.quantityBox.setValue("1");
        this.addWidget(this.quantityBox);
        // [CHANGED] 仓储扫描范围：点击切换按钮（档位见 ExchangeUiModel.STORAGE_RADIUS_STEPS，
        // 每击翻倍，最大 648 后重置默认 16）。默认档位 16，与旧输入框行为一致。
        storage.setRadius(16);
        // 物品搜索框（过滤展开箱子的槽位；客户端本地过滤，不重新扫描）
        this.storageSearchBox = new EditBox(this.font, 0, 0, 10, 10,
                Component.translatable("poketrade.gui.search"));
        this.storageSearchBox.setBordered(false);
        this.storageSearchBox.setTextColor(0xFF303030);
        this.storageSearchBox.setTextColorUneditable(0xFF303030);
        this.storageSearchBox.setResponder(s -> {
            itemSearchText = s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
        });
        this.addWidget(this.storageSearchBox);
        syncEditBoxPositions();
        requestCatalog();
        requestStorages();
    }

    private long lastStorageRefreshTime;

    /** 同步 EditBox 的全局位置（MC 标准屏幕坐标）。 */
    private void syncEditBoxPositions() {
        if (searchBox != null) {
            // EditBox 一律使用布局局部坐标：render 阶段在统一缩放矩阵内绘制，
            // 鼠标事件也先反变换到局部坐标再分发给 EditBox。
            searchBox.setX(this.layout.search().x());
            searchBox.setY(this.layout.search().y());
            searchBox.setWidth(this.layout.search().width());
            searchBox.setHeight(Math.max(10, this.layout.search().height()));
        }
        if (quantityBox != null) {
            quantityBox.setX(this.layout.quantityBox().x());
            quantityBox.setY(this.layout.quantityBox().y());
            quantityBox.setWidth(Math.max(12, this.layout.quantityBox().width()));
            quantityBox.setHeight(Math.max(10, this.layout.quantityBox().height()));
        }
        if (storageSearchBox != null) {
            storageSearchBox.setX(this.layout.storageSearch().x());
            storageSearchBox.setY(this.layout.storageSearch().y());
            storageSearchBox.setWidth(this.layout.storageSearch().width());
            storageSearchBox.setHeight(Math.max(10, this.layout.storageSearch().height()));
        }
    }

    /** 屏幕坐标 -> 布局局部坐标（与当前缩放一致；1.0 时等价于减 leftPos/topPos）。 */
    private int toLocalX(double mouseX) {
        return (int) Math.floor((mouseX - scaledOriginX) / uiScale);
    }

    private int toLocalY(double mouseY) {
        return (int) Math.floor((mouseY - scaledOriginY) / uiScale);
    }

    /**
     * 进入自适应缩放渲染：把整块界面按 {@link UiScaling} 选择的比例
     * 等比缩小并居中；渲染期间 leftPos/topPos 置 0，配合 super.render
     * 内部的 translate 与槽位命中逻辑（局部坐标）保持一致。
     */
    private void beginScaledRender(GuiGraphics g) {
        int windowWidth = this.width;
        int windowHeight = this.height;
        int imageWidth = this.layout.width();
        int imageHeight = this.layout.height();
        this.uiScale = UiScaling.fitScale(windowWidth, windowHeight, imageWidth, imageHeight);
        // [CHANGED] 会话 #12：origin 取整，避免奇数窗口尺寸时 .5 像素平移导致字形落在
        // 分数像素被线性采样而模糊；与 screenX/screenY 共用同一整数值，1.0 档下逐像素对齐。
        this.scaledOriginX = Math.round((windowWidth - imageWidth * this.uiScale) / 2f);
        this.scaledOriginY = Math.round((windowHeight - imageHeight * this.uiScale) / 2f);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(this.scaledOriginX, this.scaledOriginY, 0);
        pose.scale(this.uiScale, this.uiScale, 1);
        this.leftPos = 0;
        this.topPos = 0;
    }

    private void endScaledRender(GuiGraphics g) {
        g.pose().popPose();
    }

    /** 从唯一几何来源 {@link ExchangeUiModel.Layout} 落位所有坐标（收起切换后重算）。 */
    private void applyLayout() {
        this.layout = ExchangeUiModel.Layout.layoutFor(leftCollapsed, rightCollapsed);
        this.imageWidth = this.layout.width();
        this.imageHeight = this.layout.height();
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;
        // 标题居中：宝可梦交易所
        this.titleLabelX = Math.max(0, (this.layout.width() - this.font.width(this.title.getString())) / 2);
        this.titleLabelY = 4;
        this.inventoryLabelX = this.layout.inventoryX();
        this.inventoryLabelY = this.layout.inventoryY() - 12;
        this.gridCols = this.layout.catalogGrid().width() / SLOT;
        this.cartCols = this.layout.cartGrid().width() / SLOT;
        this.snapshotCols = ExchangeUiModel.Layout.SNAP_COLS;
        this.snapshotRows = ExchangeUiModel.Layout.SNAP_ROWS;
        syncEditBoxPositions();
        this.menu.relayoutPlayerSlots(this.layout.inventoryX(), this.layout.inventoryY(), this.layout.hotbarY());
        this.catalogScroll = ExchangeUiModel.clampScroll(this.catalogScroll,
                ExchangeUiModel.pageCount(visibleCatalog().size(), this.gridCols * ExchangeUiModel.Layout.GRID_ROWS), 1);
        this.cartScroll = ExchangeUiModel.clampScroll(this.cartScroll,
                ExchangeUiModel.pageCount(this.cart.size(), ExchangeUiModel.Layout.CART_CELLS), 1);
    }

    // ================= 目录 =================

    private void requestCatalog() {
        this.catalogRequestId = UUID.randomUUID().toString();
        // C1：搜索字符串以 EditBox 的值为准（responder 用 setResponder 输入变化触发时同步更新）。
        // 传 lastSearchText（已被 setResponder 正常化）而非 searchBox.getValue()，保证：
        // - 输入变化触发的请求包含最新值；- onCatalogResponse 回填的 lastSearchText 不引起重复请求。
        PacketDistributor.sendToServer(new ExchangeCatalogPacket.Request(
                this.catalogRequestId,
                this.lastSearchText == null ? "" : this.lastSearchText,
                this.activeCategory, this.sort));
    }

    @Override
    public void onCatalogResponse(ExchangeCatalogPacket.Response packet) {
        if (!ExchangeUiModel.isCurrentCatalogResponse(this.catalogRequestId, packet.sessionId())) {
            return;
        }
        this.catalog.clear();
        this.catalog.addAll(packet.entries());
        this.categories.clear();
        this.categories.addAll(packet.categories());
        this.requireConfirmValue = packet.requireConfirmValue();
        this.blockedItems.clear();
        this.blockedItems.addAll(packet.blockedItems());
        this.allowedItems.clear();
        this.allowedItems.addAll(packet.allowedItems());
        this.allowlistEnabled = packet.allowlistEnabled();
        this.buyEnabled = packet.buyEnabled();
        this.sellEnabled = packet.sellEnabled();
        this.catalogScroll = 0;
        // C1：与 responder 的 lastSearchText 对齐，杜绝任何回填/重复字符造成的循环请求
        this.lastSearchText = this.searchBox == null ? "" : this.searchBox.getValue();
    }

    private ItemStack stackOf(ExchangeCatalogPacket.EntryWire e) {
        ResourceLocation rl = ResourceLocation.tryParse(e.itemId());
        return rl == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(rl));
    }

    /** 本地化显示名解析器（C2/C6：目录与出售预览共用；解析失败返回空串）。 */
    private String displayNameOf(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) {
            return "";
        }
        try {
            var item = BuiltInRegistries.ITEM.get(rl);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                return "";
            }
            return item.getDefaultInstance().getHoverName().getString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * C2：客户端本地化二次过滤——服务端已按英文名预筛并截断 500 条，这里用本地化显示名
     * 再过滤一次；空查询原样返回，绝不放大服务端截断结果。
     */
    private List<ExchangeCatalogPacket.EntryWire> visibleCatalog() {
        String query = this.searchBox == null ? "" : this.searchBox.getValue();
        return ExchangeUiModel.filterCatalogEntries(this.catalog, query, this::displayNameOf);
    }

    private void addToCart(ExchangeCatalogPacket.EntryWire entry, int count) {
        if (entry.buyPrice() <= 0) {
            // 仅可出售（PKM 兜底回收价等）的条目不能买入：不进购物车并给出明确提示
            sellMessage = t("poketrade.exchange.buy.unavailable");
            sellMessageColor = PeStyle.TEXT_DIM;
            return;
        }
        cart.add(entry.itemId(), count);
    }

    /** 拖动买：目录商品拖到玩家背包 = 立即买入该组并放入背包。 */
    private void buyNow(ExchangeCatalogPacket.EntryWire entry, int count) {
        if (entry.buyPrice() <= 0) {
            sellMessage = t("poketrade.exchange.buy.unavailable");
            sellMessageColor = PeStyle.TEXT_DIM;
            return;
        }
        if (!buyEnabled) {
            sellMessage = t("poketrade.exchange.buy.disabled");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (!workflow.begin(ExchangeUiModel.Operation.BUY, menu.getResultNonce())) {
            return;
        }
        PacketDistributor.sendToServer(new ExchangeBuyPacket(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                List.of(new ExchangeBuyPacket.CartLineWire(entry.itemId(), Math.max(1, count)))));
    }

    /** 一键买入：把购物车内全部可买入条目批量发给服务端（服务端重新报价，全成或全败）。 */
    private void buyCart() {
        if (cart.isEmpty() || workflow.pending()) {
            return;
        }
        if (!buyEnabled) {
            sellMessage = t("poketrade.exchange.buy.disabled");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        Map<String, Long> buyPrices = new LinkedHashMap<>();
        for (ExchangeCatalogPacket.EntryWire entry : catalog) {
            buyPrices.put(entry.itemId(), entry.buyPrice());
        }
        List<ExchangeBuyPacket.CartLineWire> lines = new ArrayList<>();
        for (int i = 0; i < cart.size(); i++) {
            ExchangeUiModel.CartLine line = cart.get(i);
            if (buyPrices.getOrDefault(line.itemId(), 0L) <= 0L) {
                continue; // 仅可出售条目防御性跳过（addToCart 已拦截，理论不出现）
            }
            lines.add(new ExchangeBuyPacket.CartLineWire(line.itemId(), line.count()));
        }
        if (lines.isEmpty()) {
            sellMessage = t("poketrade.exchange.buy.unavailable");
            sellMessageColor = PeStyle.TEXT_DIM;
            return;
        }
        if (!workflow.begin(ExchangeUiModel.Operation.BUY, menu.getResultNonce())) {
            return;
        }
        PacketDistributor.sendToServer(new ExchangeBuyPacket(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), lines));
        sellMessage = t("poketrade.exchange.buy.sent");
        sellMessageColor = PeStyle.TEXT_DIM;
    }

    /** 拖动卖：弹出单一物品（已放回背包原槽）的出售预览。 */
    private void openSingleSellPreview(ItemStack stack) {
        if (!sellEnabled) {
            sellMessage = t("poketrade.exchange.sell.disabled");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return;
        }
        List<ExchangeUiModel.SourceLine> source = List.of(
                new ExchangeUiModel.SourceLine(id.toString(),
                        stack.getHoverName().getString(), stack.getCount()));
        sellPreview = ExchangeUiModel.SellPreview.scan(source, sellPrices(), MAX_SELL_LINES,
                requireConfirmValue, ExchangeUiModel.SellSource.INVENTORY,
                Set.copyOf(blockedItems), Set.copyOf(allowedItems), allowlistEnabled);
        storagePreview = null;
        previewPage = 0;
        previewConfirmed = !sellPreview.requiresConfirmation();
        if (sellPreview.lines().isEmpty()) {
            sellPreview = null;
            sellMessage = t("poketrade.exchange.sell.nothing");
            sellMessageColor = PeStyle.TEXT_DIM;
        }
    }

    /** 中栏单个卖出：直接出售该物品，不弹预览确认。 */
    private void sellSingleDirect(ItemStack stack) {
        if (!sellEnabled) {
            sellMessage = t("poketrade.exchange.sell.disabled");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (stack.isEmpty() || workflow.pending()) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return;
        }
        // 无回收价的物品直接拦截，避免服务端回“未知物品”造成困惑
        if (sellPrices().getOrDefault(id.toString(), 0L) <= 0L) {
            sellMessage = t("poketrade.exchange.sell.no_price");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        List<ExchangeSellPacket.LineWire> lines = List.of(
                new ExchangeSellPacket.LineWire(id.toString(), stack.getCount()));
        if (lines.isEmpty()) {
            return;
        }
        if (!workflow.begin(ExchangeUiModel.Operation.INVENTORY_SELL, menu.getResultNonce())) {
            return;
        }
        PacketDistributor.sendToServer(new ExchangeSellPacket(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), lines));
        sellMessage = t("poketrade.exchange.sell.sent");
        sellMessageColor = PeStyle.TEXT_DIM;
    }

    private void setCartCount(int index, int count) {
        if (index < 0 || index >= cart.size()) {
            return;
        }
        cart.setCount(index, count);
        if (count <= 0) {
            if (selectedCart == index) {
                selectedCart = -1;
            } else if (selectedCart > index) {
                selectedCart--;
            }
        }
    }

    /** 解析数量输入框：非法/空/0 返回 -1，合法返回 1-1024。 */
    private int parseQuantity() {
        String v = quantityBox.getValue().trim();
        if (v.isEmpty()) {
            return -1;
        }
        int qty;
        try {
            qty = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
        return qty > 0 ? Math.min(qty, 1024) : -1;
    }

    private long cartTotalCost() {
        Map<String, Long> prices = new LinkedHashMap<>();
        for (ExchangeCatalogPacket.EntryWire entry : catalog) {
            prices.put(entry.itemId(), entry.buyPrice());
        }
        return cart.total(prices);
    }

    /**
     * C7：出售扫描包含玩家背包全部 36 格（含手持快捷栏槽位）与副手槽位；
     * 预览同时统计每条跳过原因（数量/无价/黑名单/白名单）供渲染明细。
     */
    private void sellInventory() {
        if (!sellEnabled) {
            sellMessage = t("poketrade.exchange.sell.disabled");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        sellPreview = ExchangeUiModel.SellPreview.scan(inventorySource(), sellPrices(), MAX_SELL_LINES,
                requireConfirmValue, ExchangeUiModel.SellSource.INVENTORY,
                Set.copyOf(blockedItems), Set.copyOf(allowedItems), allowlistEnabled);
        storagePreview = null;
        previewPage = 0;
        previewConfirmed = !sellPreview.requiresConfirmation();
        if (sellPreview.lines().isEmpty()) {
            sellPreview = null;
            sellMessage = t("poketrade.exchange.sell.nothing");
            sellMessageColor = PeStyle.TEXT_DIM;
        }
    }

    /**
     * [CHANGED] 会话 #10：扫描背包出售源（主 36 格 + 副手槽 40 号），与 {@link #sellInventory}
     * 原扫描语义一致；供 Shift 贩卖卖整组时聚合同 ID 数量复用。
     */
    private List<ExchangeUiModel.SourceLine> inventorySource() {
        List<ExchangeUiModel.SourceLine> source = new ArrayList<>();
        Inventory inv = this.minecraft.player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                source.add(new ExchangeUiModel.SourceLine(id.toString(), stack.getHoverName().getString(), stack.getCount()));
            }
        }
        // 副手槽（40 号虚拟槽位在背包渲染区之外，单独补扫）
        ItemStack offhand = inv.offhand.isEmpty() ? ItemStack.EMPTY : inv.offhand.get(0);
        if (!offhand.isEmpty()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(offhand.getItem());
            if (id != null) {
                source.add(new ExchangeUiModel.SourceLine(id.toString(), offhand.getHoverName().getString(), offhand.getCount()));
            }
        }
        return source;
    }

    // ================= Shift 直接贩卖（会话 #10） =================

    /**
     * [CHANGED] 会话 #10：当前配置的贩卖 Shift 键是否按下（左右 Shift 可区分；OFF=永不触发）。
     * {@link Screen#hasShiftDown()} 无法区分左右，故用 GLFW 键位实时查询。
     */
    private boolean shiftSellActive() {
        return switch (PokeTradeConfig.shiftSellHand()) {
            case LEFT -> InputConstants.isKeyDown(minecraft.getWindow().getWindow(),
                    GLFW.GLFW_KEY_LEFT_SHIFT);
            case RIGHT -> InputConstants.isKeyDown(minecraft.getWindow().getWindow(),
                    GLFW.GLFW_KEY_RIGHT_SHIFT);
            case OFF -> false;
        };
    }

    /**
     * [CHANGED] 会话 #10：仓储取出使用的 Shift 键 = 非贩卖键的那只（OFF=任意 Shift），
     * 与 Shift 贩卖实现键位隔离，避免「左 Shift 点背包=卖、点仓储却取出」的混淆。
     */
    private boolean storageWithdrawShift() {
        return switch (PokeTradeConfig.shiftSellHand()) {
            case LEFT -> InputConstants.isKeyDown(minecraft.getWindow().getWindow(),
                    GLFW.GLFW_KEY_RIGHT_SHIFT);
            case RIGHT -> InputConstants.isKeyDown(minecraft.getWindow().getWindow(),
                    GLFW.GLFW_KEY_LEFT_SHIFT);
            case OFF -> hasShiftDown();
        };
    }

    /** [CHANGED] 会话 #10：Shift+左键点击背包物品 = 卖该格整叠。 */
    private void shiftSellStack(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return;
        }
        shiftSell(new ExchangeUiModel.SourceLine(
                id.toString(), stack.getHoverName().getString(), stack.getCount()));
    }

    /** [CHANGED] 会话 #10：Shift+右键点击背包物品 = 卖背包+副手全部同 ID 物品（整组）。 */
    private void shiftSellGroup(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return;
        }
        long total = ExchangeUiModel.groupCount(inventorySource(), id.toString());
        if (total <= 0) {
            return;
        }
        shiftSell(new ExchangeUiModel.SourceLine(
                id.toString(), stack.getHoverName().getString(), (int) total));
    }

    /**
     * [CHANGED] 会话 #10：Shift 贩卖统一门控（整叠/整组共用）。经 {@code SellPreview.scan} 过滤
     * 黑白名单/无价后：不可售 → 本地清晰提示（M1，避免服务端报「未知物品」的困惑）；未超
     * 二次确认阈值 → 直接发包；超阈值 → 弹出售预览 modal 二次确认（复用现有交互）。
     */
    private void shiftSell(ExchangeUiModel.SourceLine line) {
        if (!sellEnabled) {
            sellMessage = t("poketrade.exchange.sell.disabled");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (workflow.pending()) {
            return;
        }
        ExchangeUiModel.SellPreview preview = ExchangeUiModel.SellPreview.scan(
                List.of(line), sellPrices(), MAX_SELL_LINES, requireConfirmValue,
                ExchangeUiModel.SellSource.INVENTORY,
                Set.copyOf(blockedItems), Set.copyOf(allowedItems), allowlistEnabled);
        if (preview.lines().isEmpty()) {
            showShiftSellBlocked(preview);
            return;
        }
        if (!preview.requiresConfirmation()) {
            // 未超阈值：直接发聚合行，不弹 modal
            sendInventorySell(preview.lines().stream()
                    .map(l -> new ExchangeSellPacket.LineWire(l.itemId(), l.count()))
                    .toList());
            return;
        }
        // 超阈值：弹 modal，第一击确认按钮置位、第二击才发包（复用 confirmPreview 语义）
        sellPreview = preview;
        storagePreview = null;
        previewPage = 0;
        previewConfirmed = false;
    }

    /** [CHANGED] 会话 #10：Shift 贩卖被拦截（无价/黑名单/白名单）时的本地提示，按跳过原因区分文案。 */
    private void showShiftSellBlocked(ExchangeUiModel.SellPreview preview) {
        String key = "poketrade.exchange.sell.nothing";
        for (ExchangeUiModel.SkipReason reason : preview.skipReasons()) {
            switch (reason) {
                case NO_PRICE -> key = "poketrade.exchange.sell.no_price";
                case BLACKLISTED, NOT_ALLOWED -> key = "poketrade.exchange.sell.blocked";
                default -> { /* ZERO_COUNT 维持默认文案 */ }
            }
        }
        sellMessage = t(key);
        sellMessageColor = PeStyle.TEXT_ERROR;
    }

    /** [CHANGED] 会话 #10：背包出售直接发包（单行/聚合行；与 {@link #sellSingleDirect} 平行，
     *  {@code workflow.begin} 防重复提交）。 */
    private void sendInventorySell(List<ExchangeSellPacket.LineWire> lines) {
        if (!sellEnabled || lines.isEmpty() || workflow.pending()) {
            return;
        }
        if (!workflow.begin(ExchangeUiModel.Operation.INVENTORY_SELL, menu.getResultNonce())) {
            return;
        }
        PacketDistributor.sendToServer(new ExchangeSellPacket(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), lines));
        sellMessage = t("poketrade.exchange.sell.sent");
        sellMessageColor = PeStyle.TEXT_DIM;
    }

    private Map<String, Long> sellPrices() {
        Map<String, Long> prices = new LinkedHashMap<>();
        for (ExchangeCatalogPacket.EntryWire entry : catalog) {
            prices.put(entry.itemId(), entry.sellPrice());
        }
        return prices;
    }

    private void confirmPreview() {
        if (sellPreview == null || workflow.pending()) {
            return;
        }
        // C6：仓储出售必须持有 SELL 权限且预览非空才允许确认
        if (sellPreview.source() == ExchangeUiModel.SellSource.STORAGE
                && (storagePreview == null || !storagePreview.canConfirm())) {
            return;
        }
        if (!previewConfirmed) {
            previewConfirmed = true;
            return;
        }
        if (sellPreview.source() == ExchangeUiModel.SellSource.INVENTORY) {
            if (!workflow.begin(ExchangeUiModel.Operation.INVENTORY_SELL, menu.getResultNonce())) {
                return;
            }
            List<ExchangeSellPacket.LineWire> lines = sellPreview.lines().stream()
                    .map(line -> new ExchangeSellPacket.LineWire(line.itemId(), line.count()))
                    .toList();
            PacketDistributor.sendToServer(new ExchangeSellPacket(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), lines));
        } else {
            sendStorageSell();
        }
    }

    /** 出售预览里点击单条：只出售该物品（背包来源；仓储来源请用“确认出售”全部结算）。 */
    private void sellSingleLine(ExchangeUiModel.PreviewLine line) {
        if (sellPreview == null || sellPreview.source() != ExchangeUiModel.SellSource.INVENTORY
                || workflow.pending()) {
            return;
        }
        if (!workflow.begin(ExchangeUiModel.Operation.INVENTORY_SELL, menu.getResultNonce())) {
            return;
        }
        List<ExchangeSellPacket.LineWire> lines = List.of(
                new ExchangeSellPacket.LineWire(line.itemId(), line.count()));
        PacketDistributor.sendToServer(new ExchangeSellPacket(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), lines));
    }

    private void cancelPreview() {
        if (!workflow.pending()) {
            sellPreview = null;
            storagePreview = null;
            previewConfirmed = false;
            previewPage = 0;
        }
    }

    // ================= 仓储（BrowserHost） =================

    private void requestStorages() {
        PacketDistributor.sendToServer(new QueryStoragesPacket(
                sessionId, storage.getRadius(), storage.getSearchText(),
                StorageQuery.Sort.DISTANCE, StorageQuery.Filter.VIEWABLE, 200));
    }

    /** [CHANGED] 点击切换仓储扫描半径档位（翻倍，最大 648 后重置 16），并重新发起扫描。 */
    private void cycleStorageRadius() {
        storage.setRadius(ExchangeUiModel.nextStorageRadius(storage.getRadius()));
        requestStorages();
    }

    @Override
    public void onQueryResponse(QueryStoragesPacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        storage.setStorages(response.storages());
        storage.setPermissionsByStorage(response.permissions());
        storage.setScanComplete(response.storages().stream().allMatch(StorageDescriptor::scanComplete));
        // 清理已消失仓储的快照/展开/滚动状态
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (StorageDescriptor d : response.storages()) {
            ids.add(d.storageId().asString());
        }
        snapshotsByStorage.keySet().retainAll(ids);
        revisionsByStorage.keySet().retainAll(ids);
        expandedStorages.retainAll(ids);
        storageScrolls.keySet().retainAll(ids);
        List<StorageDescriptor> visible = storage.visibleStorages();
        if (!visible.isEmpty()) {
            StorageId selected = storage.getSelectedStorageId();
            if (selected == null || !ids.contains(selected.asString())) {
                selectStorage(visible.get(0));
            }
            // [CHANGED] 会话 #11：首次查询门——只有首个回包且当前全收起时才自动展开首个仓储。
            // 之前用 expandedStorages.isEmpty() 直接判断，玩家收起全部仓储后集合为空，
            // 10 秒自动刷新回包会把它误判为「首次打开」而强制展开（问题 1）。
            if (firstQueryGate.onQuery(!visible.isEmpty(), expandedStorages.isEmpty())) {
                expandedStorages.add(visible.get(0).storageId().asString());
                requestStorageSnapshot(visible.get(0).storageId());
                accordionScroll = 0; // 仅首次回包重置手风琴滚动到顶
            } else {
                // [CHANGED] 会话 #11：后续回包（自动刷新/手动刷新/换半径）保留手风琴滚动位置，
                // 只钳制到有效范围，不再无条件清零导致列表每 10 秒弹回顶部。
                accordionScroll = ExchangeUiModel.clampScroll(
                        accordionScroll, storage.visibleStorages().size(), 1);
            }
            // 为已展开但尚无快照的仓储请求快照
            for (StorageDescriptor d : visible) {
                if (expandedStorages.contains(d.storageId().asString())
                        && !snapshotsByStorage.containsKey(d.storageId().asString())) {
                    requestStorageSnapshot(d.storageId());
                }
            }
        }
    }

    @Override
    public void onSnapshotResponse(StorageSnapshotPacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        Map<Integer, StorageItemSlot> slots = response.viewAllowed()
                ? response.slots() : Map.of();
        StorageSnapshot snapshot = new StorageSnapshot(
                response.storageId(), response.revision(), slots);
        String key = response.storageId().asString();
        snapshotsByStorage.put(key, snapshot);
        revisionsByStorage.put(key, response.revision());
        StorageId selectedId = storage.getSelectedStorageId();
        if (selectedId != null && key.equals(selectedId.asString())) {
            storage.applySnapshot(snapshot);
            if (sellPreview != null && sellPreview.source() == ExchangeUiModel.SellSource.STORAGE
                    && awaitingSnapshotClear) {
                // C6：仓储出售成功后清空待售清单与预览
                sellQueue.clear();
                sellPreview = null;
                storagePreview = null;
                previewConfirmed = false;
                awaitingSnapshotClear = false;
                previewPage = 0;
            }
            menu.setBrowsedStorage(response.storageId(),
                    storage.getSelectedDescriptor(),
                    storage.getSelectedSnapshot(),
                    response.revision());
            menu.markSnapshotStale(false);
        }
    }

    @Override
    public void onManageResponse(StorageManagePacket.Response response) {
        // 交易所左栏不展示管理详情，忽略
    }

    private void selectStorage(StorageDescriptor descriptor) {
        storage.selectStorage(descriptor.storageId(), descriptor);
        StorageSnapshot snap = snapshotsByStorage.get(descriptor.storageId().asString());
        menu.setBrowsedStorage(descriptor.storageId(), descriptor, snap,
                snap == null ? -1L : revisionsByStorage.getOrDefault(
                        descriptor.storageId().asString(), -1L));
        menu.markSnapshotStale(snap == null);
        sellQueue.clear();
        sellPreview = null;
        storagePreview = null;
        previewConfirmed = false;
        sellMessage = "";
        previewPage = 0;
        if (snap != null) {
            storage.applySnapshot(snap);
        }
        requestStorageSnapshot(descriptor.storageId());
    }

    private void requestStorageSnapshot(StorageId id) {
        PacketDistributor.sendToServer(new StorageSnapshotPacket(sessionId, id));
    }

    /** 按 id 选中仓储（手风琴点击时使用；有缓存快照直接套用）。 */
    private void selectStorageById(StorageId id) {
        for (StorageDescriptor d : storage.visibleStorages()) {
            if (d.storageId().equals(id)) {
                selectStorage(d);
                return;
            }
        }
    }

    /** 分类循环：全部 → 目录分类依次 → 全部。 */
    private void cycleSlotCategory() {
        if (categories.isEmpty()) {
            slotCategoryIndex = -1;
            return;
        }
        slotCategoryIndex++;
        if (slotCategoryIndex >= categories.size()) {
            slotCategoryIndex = -1;
        }
    }

    /**
     * C6：构建仓储出售预览——来源=仓储、仓储名称/ID、SELL 权限、revision；
     * 物品显示名经 {@link #displayNameOf} 从注册表取本地化名。
     */
    private void submitStorageSell() {
        if (!sellEnabled) {
            sellMessage = t("poketrade.exchange.sell.disabled");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (sellQueue.isEmpty()) {
            return;
        }
        List<ExchangeUiModel.SourceLine> source = sellQueue.values().stream()
                .map(line -> new ExchangeUiModel.SourceLine(
                        line.itemId(), displayNameOf(line.itemId()), line.count()))
                .toList();
        sellPreview = ExchangeUiModel.SellPreview.scan(source, sellPrices(), MAX_SELL_LINES,
                requireConfirmValue, ExchangeUiModel.SellSource.STORAGE,
                Set.copyOf(blockedItems), Set.copyOf(allowedItems), allowlistEnabled);
        StorageId selected = storage.getSelectedStorageId();
        StorageDescriptor descriptor = storage.getSelectedDescriptor();
        storagePreview = new ExchangeUiModel.StorageSellPreview(
                sellPreview,
                descriptor == null ? "" : descriptor.displayName(),
                selected == null ? "" : selected.asString(),
                storage.hasPermission(StoragePermission.SELL),
                storage.getSelectedSnapshotRevision());
        previewPage = 0;
        previewConfirmed = !sellPreview.requiresConfirmation() && storagePreview.canConfirm();
        if (sellPreview.lines().isEmpty()) {
            sellPreview = null;
            storagePreview = null;
            sellMessage = t("poketrade.exchange.sell.storage.nothing");
            sellMessageColor = PeStyle.TEXT_DIM;
        }
    }

    private void sendStorageSell() {
        StorageId selected = storage.getSelectedStorageId();
        if (selected == null || storagePreview == null || !storagePreview.canConfirm()
                || !workflow.begin(ExchangeUiModel.Operation.STORAGE_SELL, menu.getResultNonce())) {
            return;
        }
        List<ExchangeService.SellEntry> entries = new ArrayList<>();
        Map<StorageId, Long> revisions = new LinkedHashMap<>();
        for (PendingSell sell : sellQueue.values()) {
            entries.add(new ExchangeService.SellEntry(
                    selected, sell.slotIndex(), sell.count(), sell.fingerprint()));
            revisions.put(selected, storage.getSelectedSnapshotRevision());
        }
        PacketDistributor.sendToServer(new StorageSellPacket(
                sessionId, UUID.randomUUID().toString(), entries, revisions));
    }

    // ================= 交互 =================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 先按当前缩放把屏幕坐标反变换为布局局部坐标，绘制/命中/分发全程使用同一坐标系
        int localX = toLocalX(mouseX);
        int localY = toLocalY(mouseY);
        if (contextMenu != null) {
            ExchangeUiModel.Rect rect = contextMenuRect();
            if (rect.contains(localX, localY)) {
                int option = (localY - rect.y()) / 12;
                ContextMenu menu = contextMenu;
                contextMenu = null;
                runContextOption(menu, option);
            } else {
                contextMenu = null;
            }
            return true;
        }
        if (searchBox != null && layout.search().contains(localX, localY)) {
            // 显式命中 + 聚焦：不依赖 EditBox 内部命中判定，保证缩放后点击必定生效
            searchBox.setFocused(true);
            searchBox.onClick(localX, localY);
            return true;
        }
        if (storageSearchBox != null && layout.storageSearch().contains(localX, localY)) {
            storageSearchBox.setFocused(true);
            storageSearchBox.onClick(localX, localY);
            return true;
        }
        // [CHANGED] 仓储扫描范围：点击切换档位（翻倍，最大 648 后重置 16）
        if (button == 0 && layout.radiusInput().contains(localX, localY)) {
            cycleStorageRadius();
            return true;
        }
        if (quantityBox != null && !rightCollapsed
                && quantityBox.mouseClicked(localX, localY, button)) {
            return true;
        }
        int x = localX, y = localY;
        if (sellPreview != null) {
            if (button == 0 && layout.previewCancel().contains(x, y)) {
                cancelPreview();
            } else if (button == 0 && layout.previewConfirm().contains(x, y)) {
                confirmPreview();
            } else if (button == 0) {
                // 点击预览条目 = 只卖这一条（背包来源）
                ExchangeUiModel.Rect linesRect = layout.previewLines();
                if (x >= linesRect.x() && x < linesRect.right()
                        && y >= linesRect.y() && y < linesRect.bottom()) {
                    int row = (y - linesRect.y()) / 11;
                    int idx = previewPage * ExchangeUiModel.Layout.PREVIEW_ROWS + row;
                    if (idx < sellPreview.lines().size()) {
                        sellSingleLine(sellPreview.lines().get(idx));
                    }
                }
            }
            return true;
        }
        if (button == 0 && handleButtons(x, y)) {
            return true;
        }
        // 中栏：目录物品（左键=拖动买/点击加入，右键=快捷加入 16）
        if (layout.catalogGrid().contains(x, y)) {
            int idx = (x - layout.catalogGrid().x()) / SLOT
                    + ((y - layout.catalogGrid().y()) / SLOT) * gridCols
                    + catalogScroll * gridCols;
            List<ExchangeCatalogPacket.EntryWire> visibleCatalog = visibleCatalog();
            if (idx < visibleCatalog.size()) {
                ExchangeCatalogPacket.EntryWire e = visibleCatalog.get(idx);
                if (button == 1) {
                    addToCart(e, 16);
                } else if (button == 0) {
                    dragCatalogEntry = e;
                    dragCatalogCount = hasShiftDown() ? 64 : 1;
                    dragCatalogIndex = idx;
                }
                return true;
            }
        }
        // 右栏：购物车格选中（C8c：只认前 27 格；idx 超出容量即无内容可点）
        if (!rightCollapsed && layout.cartGrid().contains(x, y)) {
            int idx = (x - layout.cartGrid().x()) / SLOT
                    + ((y - layout.cartGrid().y()) / SLOT) * cartCols
                    + cartScroll * cartCols;
            if (idx >= 0 && idx < cart.size()) {
                selectedCart = idx;
                quantityBox.setFocused(false); // 点击购物车格时移焦，避免输入串格
                quantityBox.setValue(String.valueOf(cart.get(idx).count()));
                return true;
            }
        }
        // 左栏：仓储列表行 / 快照槽位 / 出售区
        if (!leftCollapsed && layout.left().contains(x, y)) {
            handleLeftClick(x, y, button);
            return true;
        }
        // [CHANGED] 会话 #10：Shift 直接贩卖（背包区）。左键=卖整叠、右键=卖同ID整组。
        // 条件要点：!workflow.pending() 防事务中吞点击；(button==0||1) 防中键被吞；
        // carried 空防持物误卖；此时 modal(872-890)/右键菜单(839-850)/左栏已 return，天然隔离。
        // 短路 return true 使原版 QUICK_MOVE / 取一半不执行。
        if (shiftSellActive() && (button == 0 || button == 1)
                && !workflow.pending() && menu.getCarried().isEmpty()
                && layout.inventoryRect().contains(x, y)) {
            int idx = (x - layout.inventoryRect().x()) / SLOT
                    + ((y - layout.inventoryRect().y()) / SLOT) * 9;
            if (idx >= 0 && idx < 36) {
                ItemStack clicked = menu.slots.get(idx).getItem();
                if (!clicked.isEmpty()) {
                    if (button == 0) {
                        shiftSellStack(clicked);
                    } else {
                        shiftSellGroup(clicked);
                    }
                    return true;
                }
            }
        }
        // 记录从玩家背包按下的槽位（拖动卖用；super 会执行原版拿起）
        if (button == 0 && menu.getCarried().isEmpty() && layout.inventoryRect().contains(x, y)) {
            int idx = (x - layout.inventoryRect().x()) / SLOT
                    + ((y - layout.inventoryRect().y()) / SLOT) * 9;
            if (idx >= 0 && idx < 36) {
                dragFromPlayerSlot = menu.slots.size() - 36 + idx;
            }
        }
        return super.mouseClicked(localX, localY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        int lx = toLocalX(mouseX);
        int ly = toLocalY(mouseY);
        ItemStack carried = menu.getCarried();
        // 中栏单个卖出：背包物品拖到存入格 → 放回原槽并直接出售（不弹预览）
        if (button == 0 && dragFromPlayerSlot >= 0 && !carried.isEmpty()
                && layout.deposit().contains(lx, ly)) {
            int slotIndex = dragFromPlayerSlot;
            dragFromPlayerSlot = -1;
            ItemStack toSell = carried.copy();
            menu.clicked(slotIndex, 0, net.minecraft.world.inventory.ClickType.PICKUP,
                    minecraft.player);
            sellSingleDirect(toSell);
            return true;
        }
        // 拖动买：目录商品松开时按落点处理（购物车=加入批量，背包=直接买入，原地=点击买入）
        if (button == 0 && dragCatalogEntry != null) {
            ExchangeCatalogPacket.EntryWire entry = dragCatalogEntry;
            int count = dragCatalogCount;
            int pressIndex = dragCatalogIndex;
            dragCatalogEntry = null;
            dragCatalogCount = 0;
            dragCatalogIndex = -1;
            if (!rightCollapsed && layout.cartGrid().contains(lx, ly)) {
                addToCart(entry, count);
                return true;
            }
            if (layout.inventoryRect().contains(lx, ly)) {
                buyNow(entry, count);
                return true;
            }
            if (layout.catalogGrid().contains(lx, ly)) {
                int releaseIndex = (lx - layout.catalogGrid().x()) / SLOT
                        + ((ly - layout.catalogGrid().y()) / SLOT) * gridCols
                        + catalogScroll * gridCols;
                if (releaseIndex == pressIndex) {
                    // 转化桌 = 有偿的生存创造栏：点击直接买入到背包
                    buyNow(entry, count);
                    return true;
                }
            }
            return true;
        }
        if (button == 0 && dragFromPlayerSlot >= 0) {
            dragFromPlayerSlot = -1;
        }
        if (button == 0 && !carried.isEmpty() && !leftCollapsed) {
            AccordionEntry entry = accordionEntryAt(lx, ly);
            if (entry != null) {
                // 具体格子：仅当该格为空或可合并同物品时按格精确存入；
                // 空格子（无 StorageItemSlot）/不同物品格/表头一律走服务端自动找槽位，
                // 否则空格无法触发存入，出现“只能取、不能放”的现象
                StorageItemSlot target = entry.grid() != null ? accordionSlotAt(entry, lx, ly) : null;
                if (target != null && canDepositInto(target, carried)) {
                    depositCarriedTo(entry.descriptor().storageId(), target.slotIndex(), null);
                    return true;
                }
                depositCarriedTo(entry.descriptor().storageId(), -1,
                        entry.descriptor().displayName());
                return true;
            }
        }
        return super.mouseReleased(lx, ly, button);
    }

    /** 该格子能否直接放入携带物品：空槽，或同物品且剩余容量足够（可合并）。 */
    private boolean canDepositInto(StorageItemSlot target, ItemStack carried) {
        String targetId = target.itemId();
        if (targetId == null || targetId.isEmpty()) {
            return true;
        }
        ResourceLocation carriedId = BuiltInRegistries.ITEM.getKey(carried.getItem());
        if (carriedId == null) {
            return false;
        }
        if (!targetId.equals(carriedId.toString())) {
            return false;
        }
        return target.count() + carried.getCount() <= carried.getMaxStackSize();
    }

    /** 把鼠标上的物品存入指定仓储（slotIndex=-1 表示服务端自动找槽位）。 */
    private void depositCarriedTo(StorageId storageId, int targetSlot, String transferName) {
        if (!storage.allowsOn(storageId, StoragePermission.DEPOSIT)) {
            sellMessage = t("poketrade.exchange.deposit.denied");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        pendingCarriedTransferName = transferName;
        sellMessage = t(transferName == null
                ? "poketrade.exchange.deposit.pending"
                : "poketrade.exchange.transfer.pending");
        sellMessageColor = PeStyle.TEXT_DIM;
        PacketDistributor.sendToServer(new StorageDepositCarriedPacket(
                sessionId, UUID.randomUUID().toString(), storageId, targetSlot,
                -1L, menu.getCarried().getCount()));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        return super.mouseDragged(toLocalX(mouseX), toLocalY(mouseY), button, dragX, dragY);
    }

    /** 左栏按钮与中/右栏工具按钮（仅左键）。 */
    private boolean handleButtons(int x, int y) {
        // 中栏商品分页：‹ / ›
        if (layout.pagePrev().contains(x, y)) {
            catalogScroll = Math.max(0, catalogScroll - 1);
            return true;
        }
        if (layout.pageNext().contains(x, y)) {
            int pages = ExchangeUiModel.pageCount(visibleCatalog().size(),
                    gridCols * ExchangeUiModel.Layout.GRID_ROWS);
            catalogScroll = Math.min(Math.max(0, pages - 1), catalogScroll + 1);
            return true;
        }
        // 中栏存入格：单个直接出售（拖入/拿起物品后点击都直接卖，不弹预览）
        if (layout.deposit().contains(x, y) && !workflow.pending()) {
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                sellMessage = t("poketrade.exchange.sell.direct.hint");
                sellMessageColor = PeStyle.TEXT_DIM;
            } else {
                ItemStack toSell = carried.copy();
                int origin = dragFromPlayerSlot;
                dragFromPlayerSlot = -1;
                if (origin >= 0) {
                    menu.clicked(origin, 0, net.minecraft.world.inventory.ClickType.PICKUP,
                            minecraft.player);
                }
                sellSingleDirect(toSell);
            }
            return true;
        }
        if (layout.cartClear().contains(x, y) && !cart.isEmpty() && !workflow.pending()) {
            cart.clear();
            selectedCart = -1;
            return true;
        }
        // 购物车批量出售：待售队列非空则结算仓储待售，否则预览背包出售
        if (layout.cartSell().contains(x, y) && !workflow.pending()) {
            if (!sellQueue.isEmpty()) {
                submitStorageSell();
            } else if (sellEnabled) {
                sellInventory();
            }
            return true;
        }
        // 一键买入：把购物车内全部条目批量买入（全成或全败，服务端重新报价）
        if (layout.cartBuy().contains(x, y) && !cart.isEmpty() && !workflow.pending()) {
            buyCart();
            return true;
        }
        if (!leftCollapsed && layout.storageRefresh().contains(x, y) && !workflow.pending()) {
            requestStorages();
            StorageId selected = storage.getSelectedStorageId();
            if (selected != null) {
                PacketDistributor.sendToServer(new StorageSnapshotPacket(sessionId, selected));
            }
            return true;
        }
        // 购物车数量控制（右栏操作区，选中格时）：1x / 32x / 64x / 清空
        if (selectedCart >= 0 && selectedCart < cart.size() && !rightCollapsed
                && inQtyButtons(x, y)) {
            if (layout.qtyOne().contains(x, y)) {
                setCartCount(selectedCart, 1);
            } else if (layout.qtyHalf().contains(x, y)) {
                setCartCount(selectedCart, 32);
            } else if (layout.qtyStack().contains(x, y)) {
                setCartCount(selectedCart, 64);
            } else {
                setCartCount(selectedCart, 0);
            }
            return true;
        }
        if (selectedCart >= 0 && selectedCart < cart.size() && !rightCollapsed
                && layout.quantityApply().contains(x, y)) {
            int qty = parseQuantity();
            if (qty > 0) {
                setCartCount(selectedCart, qty);
            }
            quantityBox.setFocused(false);
            return true;
        }
        // 左栏：物品分类循环（全部 / 目录分类）
        if (!leftCollapsed && layout.slotCategory().contains(x, y)) {
            cycleSlotCategory();
            return true;
        }
        if (!leftCollapsed && layout.filterSell().contains(x, y)) {
            StorageViewModel.FilterMode next = storage.getFilterMode() == StorageViewModel.FilterMode.SELL
                    ? StorageViewModel.FilterMode.ALL : StorageViewModel.FilterMode.SELL;
            storage.setFilterMode(next);
            requestStorages();
            return true;
        }
        // 左栏收起/展开
        if (layout.collapseLeft().contains(x, y)) {
            leftCollapsed = !leftCollapsed;
            applyLayout();
            return true;
        }
        // 右栏收起/展开（收起时隐藏数量输入框）
        if (layout.collapseRight().contains(x, y)) {
            rightCollapsed = !rightCollapsed;
            if (rightCollapsed) {
                quantityBox.setFocused(false);
                quantityBox.setVisible(false);
            }
            applyLayout();
            return true;
        }
        if (!leftCollapsed && layout.storageClear().contains(x, y) && !sellQueue.isEmpty()) {
            sellQueue.clear();
            sellPreview = null;
            storagePreview = null;
            previewConfirmed = false;
            sellMessage = "";
            return true;
        }
        if (!leftCollapsed && layout.storageDeposit().contains(x, y) && !workflow.pending()) {
            depositAllToStorage();
            return true;
        }
        return false;
    }

    /** 一键存入：把主背包全部物品存入选中的附近仓储（服务端自动找槽位）。 */
    private void depositAllToStorage() {
        Inventory inv = this.minecraft.player.getInventory();
        List<StorageDepositPacket.DepositLine> lines = new ArrayList<>();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                lines.add(new StorageDepositPacket.DepositLine(i, stack.getCount()));
            }
        }
        if (lines.isEmpty()) {
            sellMessage = t("poketrade.exchange.deposit.none");
            sellMessageColor = PeStyle.TEXT_DIM;
            return;
        }
        // 收集有存入权限的可见仓储，按列表顺序（第 1 个优先），构造自动溢流队列
        depositOverflowQueue.clear();
        for (com.poketrade.api.storage.StorageDescriptor d : storage.visibleStorages()) {
            if (d.capabilities().contains(com.poketrade.api.storage.StorageCapability.INSERT)) {
                depositOverflowQueue.add(d.storageId());
            }
        }
        if (depositOverflowQueue.isEmpty()) {
            sellMessage = t("poketrade.exchange.deposit.denied");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        // 串行处理：先发第 1 个箱子，收到回执后再发下一个（见 onDepositResponse）
        sellMessage = t("poketrade.exchange.deposit.pending");
        sellMessageColor = PeStyle.TEXT_DIM;
        depositOverflowInFlight = true;
        sendNextDeposit();
    }

    /** 发送自动溢流队列中的下一个仓储的存入包（若队列为空则结束）。 */
    private void sendNextDeposit() {
        StorageId next = depositOverflowQueue.poll();
        if (next == null) {
            depositOverflowInFlight = false;
            sellMessage = t("poketrade.exchange.deposit.done.all");
            sellMessageColor = PeStyle.TEXT_OK;
            return;
        }
        // 队列收集时已按 DEPOSIT 能力过滤，无需再次权限检查
        Inventory inv = this.minecraft.player.getInventory();
        List<StorageDepositPacket.DepositLine> lines = new ArrayList<>();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                lines.add(new StorageDepositPacket.DepositLine(i, stack.getCount()));
            }
        }
        if (lines.isEmpty()) {
            depositOverflowQueue.clear();
            depositOverflowInFlight = false;
            sellMessage = t("poketrade.exchange.deposit.done.all");
            sellMessageColor = PeStyle.TEXT_OK;
            return;
        }
        PacketDistributor.sendToServer(new StorageDepositPacket(
                sessionId, UUID.randomUUID().toString(), next,
                -1L, lines));
    }

    /** 服务端存入回执：刷新快照；若为自动溢流存入则继续发下一个仓储。 */
    public void onDepositResponse(StorageDepositPacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        StorageId selected = storage.getSelectedStorageId();
        if (selected != null) {
            PacketDistributor.sendToServer(new StorageSnapshotPacket(sessionId, selected));
        }
        // 自动溢流存入：处理完本箱后继续发下一个（装满则溢出），直到队列清空
        if (depositOverflowInFlight) {
            if (!depositOverflowQueue.isEmpty()) {
                sendNextDeposit();
            } else {
                depositOverflowInFlight = false;
                sellMessage = t("poketrade.exchange.deposit.done.all");
                sellMessageColor = PeStyle.TEXT_OK;
            }
            return;
        }
        if (pendingCarriedTransferName != null) {
            String name = pendingCarriedTransferName;
            pendingCarriedTransferName = null;
            sellMessage = t(response.success()
                    ? "poketrade.exchange.transfer.done"
                    : "poketrade.exchange.transfer.failed", name);
            sellMessageColor = response.success() ? PeStyle.TEXT_OK : PeStyle.TEXT_ERROR;
            return;
        }
        if (response.success()) {
            sellMessage = t("poketrade.exchange.deposit.done",
                    response.movedLines(), response.totalLines());
            sellMessageColor = PeStyle.TEXT_OK;
        } else {
            sellMessage = t("poketrade.exchange.deposit.failed");
            sellMessageColor = PeStyle.TEXT_ERROR;
        }
    }

    /** 四个数量快捷按钮的命中合并区域（含 y 行命中）。 */
    private boolean inQtyButtons(int x, int y) {
        ExchangeUiModel.Rect one = layout.qtyOne();
        return y >= one.y() && y < one.bottom()
                && x >= one.x() && x < layout.qtyClear().right();
    }

    /** 左栏内部点击：仓储表头展开/收起 + 槽位（左键拿起、右键菜单、Shift 取出）。 */
    private void handleLeftClick(int x, int y, int button) {
        AccordionEntry entry = accordionEntryAt(x, y);
        if (entry == null) {
            return;
        }
        StorageId id = entry.descriptor().storageId();
        // 表头：选中 + 展开/收起
        if (entry.header().contains(x, y)) {
            selectStorageById(id);
            String key = id.asString();
            if (expandedStorages.contains(key)) {
                expandedStorages.remove(key);
            } else {
                expandedStorages.add(key);
                if (!snapshotsByStorage.containsKey(key)) {
                    requestStorageSnapshot(id);
                }
            }
            return;
        }
        // 网格滚动条点击：按 y 位置跳页（Bug #1：双箱超限行数时唯一明确的翻页手段）
        if (entry.grid() != null) {
            int sbX = entry.grid().right() + 1;
            if (x >= sbX && x <= sbX + 2 && y >= entry.grid().y() && y < entry.grid().bottom()) {
                String key = id.asString();
                // [CHANGED] 会话 #11：滚动范围按容器容量（未裁剪行数），否则双箱 8 行→可见 7 行时
                // maxOffset=0，滚动条永远点不动，第 8 排不可达（问题 2）。
                int totalRows = ExchangeUiModel.accordionContentRows(
                        entry.descriptor().slotCount(), snapshotCols);
                int visibleRows = Math.max(1, entry.grid().height() / SLOT);
                int maxOffset = Math.max(0, totalRows - visibleRows);
                if (maxOffset > 0) {
                    int relY = y - entry.grid().y();
                    int target = Math.round((float) relY / entry.grid().height() * maxOffset);
                    storageScrolls.put(key, Math.max(0, Math.min(target, maxOffset)));
                    return;
                }
            }
        }
        // 网格槽位：先确保该仓储被选中，再执行操作
        StorageId selected = storage.getSelectedStorageId();
        if (selected == null || !selected.equals(id)) {
            selectStorageById(id);
        }
        StorageItemSlot slot = accordionSlotAt(entry, x, y);
        if (slot == null) {
            return;
        }
        // [CHANGED] 会话 #10：仓储取出用「非贩卖键」的 Shift（默认右 Shift），与背包 Shift 贩卖隔离。
        if (storageWithdrawShift()) {
            withdrawFromStorage(slot);
            return;
        }
        if (button == 1) {
            contextMenu = new ContextMenu(x, y, slot, id);
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            return; // 松开时执行存入
        }
        pickUpFromStorage(slot);
    }

    private record AccordionEntry(StorageDescriptor descriptor, ExchangeUiModel.Rect header,
                                  ExchangeUiModel.Rect grid, int gridScroll) {
        boolean expanded() {
            return grid != null;
        }
    }

    /** 手风琴条目几何（按滚动偏移跳过前面的仓储）。 */
    private List<AccordionEntry> accordionEntries() {
        List<AccordionEntry> out = new ArrayList<>();
        List<StorageDescriptor> visible = storage.visibleStorages();
        int y = layout.listTop();
        int bottomLimit = 226;
        int start = Math.max(0, accordionScroll);
        for (int i = start; i < visible.size() && y < bottomLimit; i++) {
            StorageDescriptor d = visible.get(i);
            int headerH = 12;
            ExchangeUiModel.Rect header = new ExchangeUiModel.Rect(
                    layout.left().x(), y, layout.left().width(), headerH);
            ExchangeUiModel.Rect grid = null;
            int scroll = 0;
            if (expandedStorages.contains(d.storageId().asString())) {
                // [CHANGED] 会话 #11：网格高度按容器容量 slotCount 计算（单箱 4 行、双箱 7 可见行）。
                // 之前按快照「已占用槽数」算，空/半空箱子只显示 1 行（问题 2）。
                int rows = accordionGridRows(d);
                scroll = storageScrolls.getOrDefault(d.storageId().asString(), 0);
                grid = new ExchangeUiModel.Rect(layout.left().x() + 2, y + headerH,
                        snapshotCols * SLOT, rows * SLOT);
            }
            out.add(new AccordionEntry(d, header, grid, scroll));
            y += headerH + (grid == null ? 0 : grid.height());
        }
        return out;
    }

    /** 展开网格可见行数：按容器容量 slotCount 计算并裁剪到面板高度上限（问题 2 修复）。 */
    private int accordionGridRows(StorageDescriptor d) {
        return ExchangeUiModel.accordionVisibleRows(
                d.slotCount(), snapshotCols, MAX_ACCORDION_ROWS);
    }

    private AccordionEntry accordionEntryAt(int x, int y) {
        for (AccordionEntry entry : accordionEntries()) {
            if (entry.header().contains(x, y)
                    || (entry.grid() != null && entry.grid().contains(x, y))) {
                return entry;
            }
        }
        return null;
    }

    /** 展开网格点击位置对应的槽位（含滚动偏移与物品过滤）。 */
    private StorageItemSlot accordionSlotAt(AccordionEntry entry, int x, int y) {
        if (entry.grid() == null || !entry.grid().contains(x, y)) {
            return null;
        }
        int col = (x - entry.grid().x()) / SLOT;
        int row = (y - entry.grid().y()) / SLOT;
        int visibleRows = entry.grid().height() / SLOT;
        if (col < 0 || col >= snapshotCols || row < 0 || row >= visibleRows) {
            return null;
        }
        int index = entry.gridScroll() * snapshotCols + row * snapshotCols + col;
        List<StorageItemSlot> slots = filteredSlots(
                snapshotsByStorage.get(entry.descriptor().storageId().asString()));
        return index >= 0 && index < slots.size() ? slots.get(index) : null;
    }

    /** 物品搜索 + 分类过滤后的槽位列表。 */
    private List<StorageItemSlot> filteredSlots(StorageSnapshot snap) {
        if (snap == null) {
            return List.of();
        }
        List<StorageItemSlot> out = new ArrayList<>();
        for (StorageItemSlot slot : snap.slots().values()) {
            if (matchesItemFilter(slot)) {
                out.add(slot);
            }
        }
        return out;
    }

    private boolean matchesItemFilter(StorageItemSlot slot) {
        if (!itemSearchText.isEmpty()) {
            String name = displayNameOf(slot.itemId()).toLowerCase(java.util.Locale.ROOT);
            String id = slot.itemId().toLowerCase(java.util.Locale.ROOT);
            if (!name.contains(itemSearchText) && !id.contains(itemSearchText)) {
                return false;
            }
        }
        if (slotCategoryIndex >= 0 && slotCategoryIndex < categories.size()) {
            String wanted = categories.get(slotCategoryIndex);
            for (ExchangeCatalogPacket.EntryWire e : catalog) {
                if (e.itemId().equals(slot.itemId())) {
                    String cat = e.category().isEmpty() ? "unknown" : e.category();
                    return wanted.equals(cat);
                }
            }
            return false;
        }
        return true;
    }

    /** 左键点击仓储格：把物品拿起放到鼠标上。 */
    private void pickUpFromStorage(StorageItemSlot slot) {
        StorageId selected = storage.getSelectedStorageId();
        if (selected == null || !storage.hasPermission(StoragePermission.WITHDRAW)) {
            sellMessage = t("poketrade.exchange.withdraw.denied");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (storage.getSelectedSnapshotRevision() < 0) {
            sellMessage = t("poketrade.exchange.sell.storage.loading");
            sellMessageColor = PeStyle.TEXT_WARN;
            return;
        }
        pendingPickup = true;
        sellMessage = t("poketrade.exchange.pickup.pending");
        sellMessageColor = PeStyle.TEXT_DIM;
        PacketDistributor.sendToServer(new StorageWithdrawCarriedPacket(
                sessionId, UUID.randomUUID().toString(), selected, slot.slotIndex(),
                slot.fingerprint(), -1L));
    }

    /** Shift+点击仓储槽位：取出到玩家背包（自动找同物品合并/空位）。 */
    private void withdrawFromStorage(StorageItemSlot slot) {
        StorageId selected = storage.getSelectedStorageId();
        if (selected == null || !storage.hasPermission(StoragePermission.WITHDRAW)) {
            sellMessage = t("poketrade.exchange.withdraw.denied");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (storage.getSelectedSnapshotRevision() < 0) {
            sellMessage = t("poketrade.exchange.sell.storage.loading");
            sellMessageColor = PeStyle.TEXT_WARN;
            return;
        }
        Item item = null;
        try {
            item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(slot.itemId()));
        } catch (RuntimeException ignored) {
            // 物品 id 无法解析时按不可取出处理
        }
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            sellMessage = t("poketrade.exchange.withdraw.invalid");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        Inventory inv = this.minecraft.player.getInventory();
        int maxStack = Math.max(1, item.getDefaultMaxStackSize());
        int target = -1;
        int count = Math.min(slot.count(), maxStack);
        ItemStack sample = new ItemStack(item);
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, sample)
                    && s.getCount() < maxStack) {
                target = i;
                count = Math.min(slot.count(), maxStack - s.getCount());
                break;
            }
        }
        if (target < 0) {
            for (int i = 0; i < inv.items.size(); i++) {
                if (inv.getItem(i).isEmpty()) {
                    target = i;
                    break;
                }
            }
        }
        if (target < 0) {
            sellMessage = t("poketrade.exchange.withdraw.full");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        sellMessage = t("poketrade.exchange.withdraw.pending");
        sellMessageColor = PeStyle.TEXT_DIM;
        PacketDistributor.sendToServer(new StorageMovePacket(
                sessionId, UUID.randomUUID().toString(), -1L,
                StorageEndpoint.storage(selected, slot.slotIndex()),
                StorageEndpoint.inventory(target),
                count,
                slot.fingerprint(), 0L,
                Map.of()));
    }

    /** 服务端移动（取出等）回执：刷新快照并展示结果。 */
    public void onMoveResponse(StorageMovePacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        StorageId selected = storage.getSelectedStorageId();
        if (selected != null) {
            PacketDistributor.sendToServer(new StorageSnapshotPacket(sessionId, selected));
        }
        if (pendingPickup) {
            pendingPickup = false;
            sellMessage = t(response.success()
                    ? "poketrade.exchange.pickup.done"
                    : "poketrade.exchange.pickup.failed");
            sellMessageColor = response.success() ? PeStyle.TEXT_OK : PeStyle.TEXT_ERROR;
            return;
        }
        if (response.success()) {
            sellMessage = t("poketrade.exchange.withdraw.done");
            sellMessageColor = PeStyle.TEXT_OK;
        } else {
            sellMessage = t("poketrade.exchange.withdraw.failed");
            sellMessageColor = PeStyle.TEXT_ERROR;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int x = toLocalX(mouseX), y = toLocalY(mouseY);
        // C8d：目录/购物车/列表/快照/预览滚动统一钳制到页边界
        if (sellPreview != null && layout.previewModal().contains(x, y)) {
            int totalLines = sellPreview.lines().size();
            previewPage = ExchangeUiModel.clampScroll(
                    previewPage + (deltaY > 0 ? -1 : 1),
                    ExchangeUiModel.pageCount(totalLines,
                            ExchangeUiModel.Layout.PREVIEW_ROWS), 1);
            return true;
        }
        if (layout.catalogGrid().contains(x, y)) {
            catalogScroll = ExchangeUiModel.clampScroll(
                    catalogScroll - (int) deltaY,
                    ExchangeUiModel.pageCount(visibleCatalog().size(),
                            this.gridCols * ExchangeUiModel.Layout.GRID_ROWS), 1);
            return true;
        }
        if (!rightCollapsed && layout.cartGrid().contains(x, y)) {
            cartScroll = ExchangeUiModel.clampScroll(
                    cartScroll - (int) deltaY,
                    ExchangeUiModel.pageCount(this.cart.size(), ExchangeUiModel.Layout.CART_CELLS), 1);
            return true;
        }
        if (!leftCollapsed) {
            AccordionEntry over = accordionEntryAt(x, y);
            if (over != null && over.grid() != null && over.grid().contains(x, y)) {
                // 展开网格滚动（滚动范围按容器容量，双箱超限时最后几排可滚）
                String key = over.descriptor().storageId().asString();
                // [CHANGED] 会话 #11：滚动范围按容器容量（未裁剪行数），问题 2 修复。
                int totalRows = ExchangeUiModel.accordionContentRows(
                        over.descriptor().slotCount(), snapshotCols);
                storageScrolls.put(key, ExchangeUiModel.clampScroll(
                        over.gridScroll() + (deltaY > 0 ? -1 : 1), totalRows,
                        over.grid().height() / SLOT));
                return true;
            }
            if (over != null) {
                // 手风琴条目滚动
                accordionScroll = ExchangeUiModel.clampScroll(
                        accordionScroll + (deltaY > 0 ? -1 : 1),
                        storage.visibleStorages().size(), 1);
                return true;
            }
        }
        return super.mouseScrolled(x, y, deltaX, deltaY);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchBox != null && searchBox.isFocused()
                && searchBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (storageSearchBox != null && storageSearchBox.isFocused()
                && storageSearchBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        if (quantityBox != null && !rightCollapsed && quantityBox.isFocused()
                && quantityBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && contextMenu != null) {
            contextMenu = null;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && searchBox != null) {
            searchBox.setFocused(true);
            return true;
        }
        if (searchBox != null && searchBox.isFocused()
                && searchBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (quantityBox != null && !rightCollapsed && quantityBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                int qty = parseQuantity(); // 回车应用自定义数量
                if (qty > 0 && selectedCart >= 0 && selectedCart < cart.size()) {
                    setCartCount(selectedCart, qty);
                }
                quantityBox.setFocused(false);
                return true;
            }
            if (quantityBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        if (storageSearchBox != null && storageSearchBox.isFocused()
                && storageSearchBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static boolean inRect(int x, int y, int rx, int ry, int w, int h) {
        return x >= rx && x < rx + w && y >= ry && y < ry + h;
    }

    private static PriceSort nextSort(PriceSort s) {
        return PriceSort.values()[(s.ordinal() + 1) % PriceSort.values().length];
    }

    /** 分类循环：全部 -> cat1 -> cat2 -> ... -> 全部（空串表示不过滤）。 */
    private void cycleCategory() {
        if (categories.isEmpty()) {
            activeCategory = "";
            return;
        }
        int idx = categories.indexOf(activeCategory);
        activeCategory = idx < 0 || idx >= categories.size() - 1
                ? "" : categories.get(idx + 1);
    }

    /** lang 翻译快捷方式。 */
    private static String t(String key) {
        return Component.translatable(key).getString();
    }

    /** lang 翻译快捷方式（带参数）。 */
    private static String t(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private void updateTradeResult() {
        int nonce = menu.getResultNonce();
        if (nonce == lastNonce) {
            return;
        }
        lastNonce = nonce;
        int code = menu.getResultCode();
        lastResult = code >= 0 && code < TradeResult.values().length ? TradeResult.values()[code] : null;
        ExchangeUiModel.Operation operation = switch (menu.getResultOperation()) {
            case BUY -> ExchangeUiModel.Operation.BUY;
            case INVENTORY_SELL -> ExchangeUiModel.Operation.INVENTORY_SELL;
            case STORAGE_SELL -> ExchangeUiModel.Operation.STORAGE_SELL;
            case NONE -> ExchangeUiModel.Operation.NONE;
        };
        // C9：严格 nonce 匹配由 Workflow.complete 保证；clearTradeResult 不递增 nonce，
        // 因此中途清理不会让进行中的请求结果被误判过期（菜单侧无需修改）
        ExchangeUiModel.ResultAction action = workflow.complete(nonce, operation, lastResult);
        if (action == ExchangeUiModel.ResultAction.CLEAR_CART) {
            cart.clear();
            selectedCart = -1;
            awaitingSnapshotClear = false;
        } else if (action == ExchangeUiModel.ResultAction.CLEAR_PREVIEW) {
            sellPreview = null;
            storagePreview = null;
            previewConfirmed = false;
            awaitingSnapshotClear = false;
            previewPage = 0;
        } else if (action == ExchangeUiModel.ResultAction.REFRESH_STORAGE) {
            awaitingSnapshotClear = true;
            StorageId selected = storage.getSelectedStorageId();
            if (selected != null) {
                PacketDistributor.sendToServer(new StorageSnapshotPacket(sessionId, selected));
            }
        } else if (action == ExchangeUiModel.ResultAction.KEEP_DRAFT) {
            awaitingSnapshotClear = false;
        }
        if (operation == ExchangeUiModel.Operation.STORAGE_SELL && lastResult != TradeResult.SUCCESS) {
            sellMessage = t(storageReasonKey(menu.getResultReason()));
            sellMessageColor = PeStyle.TEXT_ERROR;
        }
    }

    private static String storageReasonKey(ExchangeMenu.FailureReason reason) {
        return switch (reason) {
            case PERMISSION_DENIED -> "poketrade.exchange.result.storage_sell.permission_denied";
            case REVISION_CONFLICT -> "poketrade.exchange.result.storage_sell.revision_conflict";
            case CONTENT_CHANGED -> "poketrade.exchange.result.storage_sell.content_changed";
            case UNAVAILABLE -> "poketrade.exchange.result.storage_sell.unavailable";
            case WALLET_REJECTED -> "poketrade.exchange.result.storage_sell.wallet_rejected";
            case INVALID_REQUEST -> "poketrade.exchange.result.storage_sell.invalid_request";
            case NONE, INTERNAL_ERROR -> "poketrade.exchange.result.storage_sell.internal_error";
        };
    }

    static String compactAmount(long amount) {
        return ExchangeUiModel.compactAmount(amount);
    }

    // ================= 渲染 =================

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        syncEditBoxPositions();
        pendingText.clear(); // [CHANGED] 会话 #12：每帧重置文字重放队列
        long now = System.currentTimeMillis();
        if (now - lastStorageRefreshTime > 10000) {
            lastStorageRefreshTime = now;
            requestStorages();
            if (storage.getSelectedStorageId() != null) {
                PacketDistributor.sendToServer(new StorageSnapshotPacket(
                        sessionId, storage.getSelectedStorageId()));
            }
        }
        // 全屏半透明背景在缩放矩阵外绘制，避免缩放后只压暗界面区域
        renderTransparentBackground(g);
        beginScaledRender(g);
        int lmx = toLocalX(mx), lmy = toLocalY(my);
        super.render(g, lmx, lmy, partialTick);
        if (this.searchBox != null) {
            this.searchBox.render(g, lmx, lmy, partialTick);
        }
        if (this.quantityBox != null && !rightCollapsed && this.quantityBox.isVisible()) {
            this.quantityBox.render(g, lmx, lmy, partialTick);
        }
        if (this.storageSearchBox != null) {
            this.storageSearchBox.render(g, lmx, lmy, partialTick);
        }
        // 预览弹窗绘制在最顶层。真实根因（穿模）：GUI 主投影为
        // setOrtho(0.., 1000, 21000) + modelview translate(0,0,-11000)，深度随 z 单调递增——
        // 物品图标在 GuiGraphics.renderItem 内部 translate 到 z=150（深度≈0.49），比默认 z=0
        // （深度≈0.5）更近；而 RenderType.gui() 自带 LEQUAL 深度测试，绘制时其 RenderStateShard
        // 会覆盖这里的 disableDepthTest，导致弹窗背景在 z=0 被物品深度剔除、只有无深度测试的
        // 文字悬浮在下层内容上（表现为“图层优先级低、弹窗被交易列表/钱包/页数穿透”）。
        // 修复：把弹窗整体提升到 z=400（深度≈0.48，近于一切下层元素），LEQUAL 通过即盖住。
        // [CHANGED] 会话 #12：背景几何仍在矩阵内 z=400 绘制，文字改 recordText(TOP)，
        // 在 endScaledRender 后以屏幕空间整数坐标 + 同款 z=400 提升重放。
        if (sellPreview != null) {
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F); // [CHANGED] 近于物品 z=150，修复弹窗被穿透
            renderSellPreviewModal(g);
            renderSellPreviewLabels(g, lmx, lmy);
            g.pose().popPose();
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        }
        // 右键菜单在最顶层（同穿模根因：z 提升到 400，近于物品 z=150，背景不被深度剔除）
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 400.0F); // [CHANGED] 修复右键菜单被物品穿透
        renderContextMenu(g, lmx, lmy);
        g.pose().popPose();
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        endScaledRender(g);

        // [CHANGED] 会话 #12：矩阵外屏幕空间文字重画（整数坐标，根治缩放模糊）。
        // MAIN 文字在 z=0 重放：在弹窗区域（z=400 深度≈0.48）被 LEQUAL cull，
        // 主界面文字不透出弹窗，与矩阵内行为一致。
        drawPendingText(g, TextLayer.MAIN);
        // TOP 文字（弹窗/右键菜单）以 z=400 + disableDepthTest 重放，晚画覆盖弹窗背景。
        if (sellPreview != null || contextMenu != null) {
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F);
            drawPendingText(g, TextLayer.TOP);
            g.pose().popPose();
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        }
        // [CHANGED] Bug D 修复：基类 AbstractContainerScreen.render 不调用 renderTooltip，
        // 容器子类必须显式调用，否则背包/仓储物品悬停提示不显示。
        // [CHANGED] 会话 #12：移到 endScaledRender 之后并传屏幕坐标 mx/my——
        // tooltip 在矩阵外以整数坐标清晰绘制；方法内以 toLocalX/Y 换算命中（结果与现状恒等）。
        this.renderTooltip(g, mx, my);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
        // 透明背景已由 render() 在矩阵外统一绘制，这里只画界面底
        this.renderBg(g, partialTick, mx, my);
    }

    /** renderBg（原版 super.render 调用）：在 translate(leftPos, topPos) 内绘制所有面板背景与网格。 */
    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        PeStyle.runeBackground(g, 0, 0, layout.width(), layout.height());
        PeStyle.windowFrame(g, 0, 0, layout.width(), layout.height());
        // 搜索框凹槽（无边框 EditBox 的背景）
        PeStyle.inset(g, layout.search().x() - 1, layout.search().y() - 1,
                layout.search().width() + 2, layout.search().height() + 2, 0xFF9E9E9E);
        // 仓储名称搜索框凹槽
        PeStyle.inset(g, layout.storageSearch().x() - 1, layout.storageSearch().y() - 1,
                layout.storageSearch().width() + 2, layout.storageSearch().height() + 2, 0xFF9E9E9E);
        if (!leftCollapsed) {
            PeStyle.inset(g, layout.left().x(), layout.left().y(),
                    layout.left().width(), layout.left().height(), 0xFFA8A8A8);
        }
        PeStyle.inset(g, layout.catalogGrid().x(), layout.catalogGrid().y(),
                layout.catalogGrid().width(), layout.catalogGrid().height() + 2, 0xFFA8A8A8);
        // 中栏存入格（卖出/学习）
        PeStyle.slot(g, layout.deposit().x(), layout.deposit().y());
        g.fill(layout.deposit().x() - 1, layout.deposit().y() - 1,
                layout.deposit().right() + 1, layout.deposit().bottom() + 1, 0x22000000);
        List<ExchangeCatalogPacket.EntryWire> visibleCatalog = visibleCatalog();
        for (int i = 0; i < gridCols * ExchangeUiModel.Layout.GRID_ROWS; i++) {
            int idx = i + catalogScroll * gridCols;
            int gx = layout.catalogGrid().x() + (i % gridCols) * SLOT;
            int gy = layout.catalogGrid().y() + (i / gridCols) * SLOT;
            PeStyle.slot(g, gx, gy);
            if (idx < visibleCatalog.size()) {
                ExchangeCatalogPacket.EntryWire entry = visibleCatalog.get(idx);
                ItemStack s = stackOf(entry);
                if (!s.isEmpty()) g.renderItem(s, gx + 1, gy + 1);
            }
        }
        if (visibleCatalog.isEmpty()) {
            // 目录为空（搜索/分类过滤后无结果）：在网格中央提示
            String none = this.font.plainSubstrByWidth(
                    t("poketrade.exchange.search.none"),
                    Math.max(16, layout.catalogGrid().width() - 4));
            recordText(TextLayer.MAIN, none,
                    layout.catalogGrid().x()
                            + (layout.catalogGrid().width() - this.font.width(none)) / 2,
                    layout.catalogGrid().y() + layout.catalogGrid().height() / 2 - 4,
                    PeStyle.TEXT_DIM);
        }
        // 目录分页指示（存入格右侧）
        int catalogPages = ExchangeUiModel.pageCount(
                visibleCatalog.size(), gridCols * ExchangeUiModel.Layout.GRID_ROWS);
        String pageText = t("poketrade.exchange.catalog.page",
                catalogScroll + 1, Math.max(1, catalogPages));
        recordText(TextLayer.MAIN, pageText,
                layout.pageText().x(), layout.pageText().y(), PeStyle.TEXT_DIM);
        if (!rightCollapsed) {
            PeStyle.inset(g, layout.cartGrid().x(), layout.cartGrid().y(),
                    layout.cartGrid().width(), layout.cartGrid().height() + 2, 0xFFA8A8A8);
            for (int i = 0; i < ExchangeUiModel.Layout.CART_CELLS; i++) {
                int idx = i + cartScroll * cartCols;
                int gx = layout.cartGrid().x() + (i % cartCols) * SLOT;
                int gy = layout.cartGrid().y() + (i / cartCols) * SLOT;
                PeStyle.slot(g, gx, gy);
                if (idx >= 0 && idx < cart.size()) {
                    if (idx == selectedCart) {
                        g.fill(gx - 1, gy - 1, gx + 17, gy + 17, 0x338B6B1B);
                    }
                    ResourceLocation rl = ResourceLocation.tryParse(cart.get(idx).itemId());
                    if (rl != null) {
                        ItemStack s = new ItemStack(BuiltInRegistries.ITEM.get(rl));
                        g.renderItem(s, gx + 1, gy + 1);
                        g.renderItemDecorations(this.font, s, gx + 1, gy + 1);
                    }
                }
            }
        }
        PeStyle.playerInventory(g, layout.inventoryX(), layout.inventoryY(), layout.hotbarY());
    }

    /** PriceSort → 中文短名映射（sort 按钮显示"排序: XXX"）。 */
    private static String zhPriceSort(PriceSort s) {
        return switch (s) {
            case CATEGORY -> "类别";
            case PRICE_ASC -> "价↑";
            case PRICE_DESC -> "价↓";
            case NAME -> "名称";
            case MOD -> "模组";
        };
    }

    /** [CHANGED] Bug F：目录分类现为可翻译键（itemGroup.* / 模组 tab 键），按当前语言本地化；
     *  非翻译键（模组 literal 名）translatable 无语言键时 fallback 显示原样；unknown 保持原样。 */
    private static Component categoryLabel(String category) {
        if (category == null || category.isEmpty() || "unknown".equals(category)) {
            return Component.literal(category == null ? "unknown" : category);
        }
        return Component.translatable(category);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        int x = mx - leftPos, y = my - topPos;
        boolean hovered;
        // 搜索框空且未聚焦时显示提示文字
        // [CHANGED] 会话 #12：hint 与 EditBox 内部文字同尺寸，随矩阵缩放（取舍见开发日志）
        if (searchBox != null && searchBox.getValue().isEmpty() && !searchBox.isFocused()) {
            g.drawString(this.font, this.font.plainSubstrByWidth(
                            t("poketrade.exchange.search.hint"),
                            Math.max(16, layout.search().width() - 4)),
                    layout.search().x() + 3, layout.search().y() + 3, PeStyle.TEXT_DIM);
        }
        // 中栏商品分页：‹ / ›
        PeStyle.buttonBg(g, layout.pagePrev().x(), layout.pagePrev().y(),
                layout.pagePrev().width(), layout.pagePrev().height(),
                true, false, layout.pagePrev().contains(x, y), mx, my);
        recordButton(TextLayer.MAIN, "‹", true,
                layout.pagePrev().x(), layout.pagePrev().y(),
                layout.pagePrev().width(), layout.pagePrev().height());
        PeStyle.buttonBg(g, layout.pageNext().x(), layout.pageNext().y(),
                layout.pageNext().width(), layout.pageNext().height(),
                true, false, layout.pageNext().contains(x, y), mx, my);
        recordButton(TextLayer.MAIN, "›", true,
                layout.pageNext().x(), layout.pageNext().y(),
                layout.pageNext().width(), layout.pageNext().height());
        // 中栏钱包（完整整数金额，位于存入格右侧）
        long bal = menu.getBalance();
        String balStr = t("poketrade.exchange.balance") + " " + ExchangeUiModel.formatAmount(bal);
        recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(balStr,
                        Math.max(16, layout.wallet().width() - 2)),
                layout.wallet().x(), layout.wallet().y(),
                bal > 0 ? PeStyle.TEXT_OK : PeStyle.TEXT_DIM);
        // 悬停商品价格提示行（完整整数）
        ExchangeCatalogPacket.EntryWire hoveredEntry = null;
        if (layout.catalogGrid().contains(x, y)) {
            int idx = (x - layout.catalogGrid().x()) / SLOT
                    + ((y - layout.catalogGrid().y()) / SLOT) * gridCols
                    + catalogScroll * gridCols;
            List<ExchangeCatalogPacket.EntryWire> vis = visibleCatalog();
            if (idx >= 0 && idx < vis.size()) {
                hoveredEntry = vis.get(idx);
            }
        }
        if (hoveredEntry != null) {
            String priceText = displayNameOf(hoveredEntry.itemId()) + " 买 "
                    + ExchangeUiModel.formatAmount(hoveredEntry.buyPrice()) + " 卖 "
                    + ExchangeUiModel.formatAmount(hoveredEntry.sellPrice());
            recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(priceText,
                            Math.max(16, layout.priceHint().width() - 2)),
                    layout.priceHint().x(), layout.priceHint().y(), PeStyle.TEXT_TITLE);
        }
        if (!rightCollapsed) {
            int ccw = layout.cartClear().width();
            String clearLabel = this.font.plainSubstrByWidth(t("poketrade.exchange.cart.clear_all"),
                    Math.max(8, ccw - 2));
            hovered = layout.cartClear().contains(x, y);
            PeStyle.buttonBg(g, layout.cartClear().x(), layout.cartClear().y(),
                    ccw, layout.cartClear().height(),
                    !cart.isEmpty() && !workflow.pending(), false, hovered, mx, my);
            recordButton(TextLayer.MAIN, clearLabel,
                    !cart.isEmpty() && !workflow.pending(),
                    layout.cartClear().x(), layout.cartClear().y(), ccw, layout.cartClear().height());
            int csw = layout.cartSell().width();
            String cartSellLabel = this.font.plainSubstrByWidth(
                    t("poketrade.exchange.sell.cart"), Math.max(8, csw - 2));
            hovered = layout.cartSell().contains(x, y);
            PeStyle.buttonBg(g, layout.cartSell().x(), layout.cartSell().y(),
                    csw, layout.cartSell().height(),
                    (!sellQueue.isEmpty() || sellEnabled) && !workflow.pending(), false, hovered, mx, my);
            recordButton(TextLayer.MAIN, cartSellLabel,
                    (!sellQueue.isEmpty() || sellEnabled) && !workflow.pending(),
                    layout.cartSell().x(), layout.cartSell().y(), csw, layout.cartSell().height());
            // 一键买入：紧贴一键出售下方，购物车非空且买入未停用时可用
            int cbw = layout.cartBuy().width();
            String cartBuyLabel = this.font.plainSubstrByWidth(
                    t("poketrade.exchange.buy.cart"), Math.max(8, cbw - 2));
            hovered = layout.cartBuy().contains(x, y);
            PeStyle.buttonBg(g, layout.cartBuy().x(), layout.cartBuy().y(),
                    cbw, layout.cartBuy().height(),
                    !cart.isEmpty() && buyEnabled && !workflow.pending(), false, hovered, mx, my);
            recordButton(TextLayer.MAIN, cartBuyLabel,
                    !cart.isEmpty() && buyEnabled && !workflow.pending(),
                    layout.cartBuy().x(), layout.cartBuy().y(), cbw, layout.cartBuy().height());
        }
        // 购物车数量控制（右栏操作区，选中格时）
        boolean qtyActive = !rightCollapsed && selectedCart >= 0 && selectedCart < cart.size();
        if (qtyActive) {
            String[] labels = {
                    t("poketrade.exchange.qty.one"),
                    t("poketrade.exchange.qty.half"),
                    t("poketrade.exchange.qty.stack"),
                    t("poketrade.exchange.qty.clear")};
            ExchangeUiModel.Rect[] rects = {
                    layout.qtyOne(), layout.qtyHalf(), layout.qtyStack(), layout.qtyClear()};
            for (int i = 0; i < 4; i++) {
                ExchangeUiModel.Rect r = rects[i];
                PeStyle.buttonBg(g, r.x(), r.y(), r.width(), r.height(),
                        true, false, r.contains(x, y), mx, my);
                recordButton(TextLayer.MAIN, labels[i], true,
                        r.x(), r.y(), r.width(), r.height());
            }
            PeStyle.buttonBg(g, layout.quantityApply().x(), layout.quantityApply().y(),
                    layout.quantityApply().width(), layout.quantityApply().height(),
                    true, false, layout.quantityApply().contains(x, y), mx, my);
            recordButton(TextLayer.MAIN, t("poketrade.exchange.cart.apply"), true,
                    layout.quantityApply().x(), layout.quantityApply().y(),
                    layout.quantityApply().width(), layout.quantityApply().height());
        } else if (!rightCollapsed) {
            String hint = this.font.plainSubstrByWidth(t("poketrade.exchange.cart.hint"),
                    Math.max(16, 132 - 4));
            recordText(TextLayer.MAIN, hint, layout.qtyOne().x(),
                    layout.qtyOne().y() + 2, PeStyle.TEXT_DIM);
        }
        if (!rightCollapsed) {
            int rw = Math.max(16, layout.cartCapacity().width() - 2);
            String capStr = t("poketrade.exchange.cart.capacity", cart.size(), 27);
            recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(capStr, rw),
                    layout.cartCapacity().x(), layout.cartCapacity().y(), PeStyle.TEXT_DIM);
            String itemsStr = t("poketrade.exchange.cart.items", cart.totalItems());
            recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(itemsStr, rw),
                    layout.cartItems().x(), layout.cartItems().y(), PeStyle.TEXT_DIM);
            String totalStr = t("poketrade.exchange.cart.total") + " "
                    + ExchangeUiModel.formatAmount(cartTotalCost());
            recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(totalStr, rw),
                    layout.cartTotal().x(), layout.cartTotal().y(), PeStyle.TEXT_TITLE);
        }
        quantityBox.setVisible(qtyActive);
        if (qtyActive && !quantityBox.isFocused()) {
            quantityBox.setValue(String.valueOf(cart.get(selectedCart).count()));
        }
        updateTradeResult();
        if (!workflow.messageKey().isEmpty()) {
            int w = Math.max(24, layout.middle().width() - 4);
            String msg = this.font.plainSubstrByWidth(t(workflow.messageKey()), w);
            recordText(TextLayer.MAIN, msg, layout.middle().x(), layout.height() - 8,
                    lastResult == TradeResult.SUCCESS ? PeStyle.TEXT_OK : PeStyle.TEXT_ERROR);
        }
        // 左栏：仓储列表 + 快照 + 半径/筛选 + 出售按钮
        if (!leftCollapsed) {
            renderLeftPanel(g, x, y);
        }
        // 出售提示消息（左栏下方，左栏收起时回落到中栏左缘作锚，始终在窗口内）
        if (!sellMessage.isEmpty()) {
            // 左栏已贯穿窗口高度：提示消息放在窗口最底行，避免盖住仓储面板
            int msgX = leftCollapsed ? layout.middle().x() : layout.left().x();
            int msgY = layout.height() - 8;
            int maxW = Math.max(24, layout.middle().x() - msgX);
            String msg = this.font.plainSubstrByWidth(sellMessage, maxW);
            recordText(TextLayer.MAIN, msg, msgX, msgY, sellMessageColor);
        }
        // 收起/展开按钮
        String leftBtn = leftCollapsed ? "▸" : "◂";
        PeStyle.buttonBg(g, layout.collapseLeft().x(), layout.collapseLeft().y(),
                layout.collapseLeft().width(), layout.collapseLeft().height(),
                true, false, layout.collapseLeft().contains(x, y), mx, my);
        recordButton(TextLayer.MAIN, leftBtn, true,
                layout.collapseLeft().x(), layout.collapseLeft().y(),
                layout.collapseLeft().width(), layout.collapseLeft().height());
        String rightBtn = rightCollapsed ? "◂" : "▸";
        PeStyle.buttonBg(g, layout.collapseRight().x(), layout.collapseRight().y(),
                layout.collapseRight().width(), layout.collapseRight().height(),
                true, false, layout.collapseRight().contains(x, y), mx, my);
        recordButton(TextLayer.MAIN, rightBtn, true,
                layout.collapseRight().x(), layout.collapseRight().y(),
                layout.collapseRight().width(), layout.collapseRight().height());
        // 原版容器标题：左栏展开时位于左栏顶部(8,7)；左栏收起时搜索框占满顶行，隐藏
        if (!leftCollapsed) {
            String tLabel = this.font.plainSubstrByWidth(this.title.getString(),
                    Math.max(16, 132 - 10));
            recordText(TextLayer.MAIN, tLabel,
                    this.titleLabelX, this.titleLabelY, 0x404040, false);
        }
        // 不绘制“物品栏”标签：其位置（y=156）与底部按钮行（y=154..166）重叠，
        // 会与“分类：全部”按钮文字叠成“物品分类：全部”的观感；背包网格本身已足够直观。
    }

    /** 出售预览模态背景——在 renderBg 内绘制，位于所有面板之上。 */
    private void renderSellPreviewModal(GuiGraphics g) {
        ExchangeUiModel.Rect modal = layout.previewModal();
        // 不透明背景，避免下层按钮/文字透过模态造成“文字重叠”的错觉
        g.fill(modal.x(), modal.y(), modal.right(), modal.bottom(), 0xFFF1E5C8);
        PeStyle.windowFrame(g, modal.x(), modal.y(), modal.width(), modal.height());
    }

    /**
     * 出售预览模态标签/按钮——[CHANGED] 会话 #12：背景几何在矩阵内 z=400 绘制，
     * 文字全部改 recordText(TOP)，在 endScaledRender 后以屏幕空间整数坐标 + z=400 重放。
     */
    private void renderSellPreviewLabels(GuiGraphics g, int mouseX, int mouseY) {
        ExchangeUiModel.Rect modal = layout.previewModal();
        ExchangeUiModel.Rect lines = layout.previewLines();
        int x = mouseX - leftPos, y = mouseY - topPos;
        int w = modal.width();
        int textWidth = Math.max(32, w - 20);
        String title = this.font.plainSubstrByWidth(t("poketrade.exchange.sell.preview.title"), textWidth);
        recordText(TextLayer.TOP, title, modal.x() + 6, modal.y() + 6, PeStyle.TEXT_TITLE);
        // 来源行
        String sourceKey = sellPreview.source() == ExchangeUiModel.SellSource.INVENTORY
                ? "poketrade.exchange.sell.preview.source.inventory"
                : "poketrade.exchange.sell.preview.source.storage";
        recordText(TextLayer.TOP, this.font.plainSubstrByWidth(t(sourceKey), textWidth),
                modal.x() + 6, modal.y() + 16, PeStyle.TEXT_DIM);
        if (sellPreview.source() == ExchangeUiModel.SellSource.INVENTORY) {
            recordText(TextLayer.TOP, this.font.plainSubstrByWidth(
                            t("poketrade.exchange.sell.preview.single"), textWidth),
                    modal.x() + 6, modal.y() + 26, PeStyle.TEXT_DIM);
        }
        // 仓储信息行
        if (sellPreview.source() == ExchangeUiModel.SellSource.STORAGE && storagePreview != null) {
            String perm = storagePreview.permissionAllowed()
                    ? t("poketrade.exchange.sell.preview.permission.ok")
                    : t("poketrade.exchange.sell.preview.permission.denied");
            String revision = storagePreview.revision() < 0 ? "-"
                    : Long.toString(storagePreview.revision());
            String info = t("poketrade.exchange.sell.preview.storage_info",
                    storagePreview.storageName(), storagePreview.storageId(), perm, revision);
            recordText(TextLayer.TOP, this.font.plainSubstrByWidth(info, textWidth),
                    modal.x() + 6, modal.y() + 26, PeStyle.TEXT_DIM);
        }
        // [CHANGED] 会话 #12 问题 A：可滚动条目列表——价格右对齐到弹窗右缘，名称整行动态截断。
        // 旧实现价格左对齐固定 x=modal.right()-24，千分位多位时溢出右边界；名称截断又不含
        // ×数量 后缀。新实现：subtotal 完整千分位（仅超可用宽度才截断兜底），priceX 右锚定
        // （几何来自 ExchangeUiModel.previewRowLayout 纯函数，便于测试）；名称整行
        // （名称 ×数量）截断到 priceX-6，两者永不重叠，短价格时名称可延展更宽。
        int pageStart = previewPage * ExchangeUiModel.Layout.PREVIEW_ROWS;
        for (int i = 0; i < ExchangeUiModel.Layout.PREVIEW_ROWS; i++) {
            int idx = pageStart + i;
            if (idx >= sellPreview.lines().size()) break;
            ExchangeUiModel.PreviewLine line = sellPreview.lines().get(idx);
            String subtotal = this.font.plainSubstrByWidth(
                    ExchangeUiModel.formatAmount(line.subtotal()),
                    Math.max(24, modal.right() - 24 - lines.x()));
            ExchangeUiModel.PreviewRowLayout rowLayout =
                    ExchangeUiModel.previewRowLayout(modal, lines, this.font.width(subtotal));
            String lineText = this.font.plainSubstrByWidth(
                    t("poketrade.exchange.sell.preview.line", line.displayName(), line.count()),
                    rowLayout.nameMax());
            recordText(TextLayer.TOP, lineText,
                    lines.x(), lines.y() + i * 11, PeStyle.TEXT_DIM);
            recordText(TextLayer.TOP, subtotal,
                    rowLayout.priceX(), lines.y() + i * 11, PeStyle.TEXT_TITLE);
        }
        // 总计
        String total = t("poketrade.exchange.sell.preview.total") + " "
                + ExchangeUiModel.formatAmount(sellPreview.total());
        recordText(TextLayer.TOP, this.font.plainSubstrByWidth(total, textWidth),
                modal.x() + 6, modal.y() + 112, PeStyle.TEXT_TITLE);
        // 跳过原因
        StringBuilder skipNote = new StringBuilder();
        if (sellPreview.skipped() > 0) {
            skipNote.append(t("poketrade.exchange.sell.preview.skipped", sellPreview.skipped()));
            skipNote.append(' ').append(skipReasonDetail());
        }
        if (sellPreview.truncated()) {
            if (skipNote.length() > 0) skipNote.append(' ');
            skipNote.append(t("poketrade.exchange.sell.truncated"));
        }
        if (skipNote.length() > 0) {
            recordText(TextLayer.TOP, this.font.plainSubstrByWidth(skipNote.toString(), textWidth),
                    modal.x() + 6, modal.y() + 121, PeStyle.TEXT_DIM);
        }
        // 取消 / 确认按钮（背景几何 + TOP 文字）
        boolean cancelHover = layout.previewCancel().contains(x, y);
        PeStyle.buttonBg(g, layout.previewCancel().x(), layout.previewCancel().y(),
                layout.previewCancel().width(), layout.previewCancel().height(),
                !workflow.pending(), false, cancelHover, mouseX, mouseY);
        recordButton(TextLayer.TOP, t("poketrade.exchange.cancel"), !workflow.pending(),
                layout.previewCancel().x(), layout.previewCancel().y(),
                layout.previewCancel().width(), layout.previewCancel().height());
        boolean confirmEnabled = !workflow.pending()
                && (sellPreview.source() == ExchangeUiModel.SellSource.INVENTORY
                || storagePreview == null || storagePreview.canConfirm());
        String confirmKey = sellPreview.requiresConfirmation() && !previewConfirmed
                ? "poketrade.exchange.confirm.valuable" : "poketrade.exchange.confirm";
        String confirmLabel = this.font.plainSubstrByWidth(t(confirmKey),
                Math.max(12, layout.previewConfirm().width() - 2));
        boolean confirmHover = layout.previewConfirm().contains(x, y);
        PeStyle.buttonBg(g, layout.previewConfirm().x(), layout.previewConfirm().y(),
                layout.previewConfirm().width(), layout.previewConfirm().height(),
                confirmEnabled, false, confirmHover, mouseX, mouseY);
        recordButton(TextLayer.TOP, confirmLabel, confirmEnabled,
                layout.previewConfirm().x(), layout.previewConfirm().y(),
                layout.previewConfirm().width(), layout.previewConfirm().height());
    }

    private ExchangeUiModel.Rect contextMenuRect() {
        int w = 92;
        int h = 36; // 3 项 × 12px
        int x = Math.max(2, Math.min(contextMenu.x(), layout.width() - w - 2));
        int y = Math.max(2, Math.min(contextMenu.y(), layout.height() - h - 2));
        return new ExchangeUiModel.Rect(x, y, w, h);
    }

    /** 右键菜单：仓储槽位操作（拿起 / 取出到背包 / 待售）。 */
    private void renderContextMenu(GuiGraphics g, int mx, int my) {
        if (contextMenu == null) {
            return;
        }
        ExchangeUiModel.Rect rect = contextMenuRect();
        g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), 0xF0E8E0C8);
        PeStyle.windowFrame(g, rect.x(), rect.y(), rect.width(), rect.height());
        String[] labels = {
                t("poketrade.exchange.pickup"),
                t("poketrade.exchange.withdraw.to_inventory"),
                t("poketrade.exchange.sell.toggle")
        };
        for (int i = 0; i < 3; i++) {
            int rowY = rect.y() + i * 12;
            boolean hovered = rect.contains(mx, rowY + 6);
            if (hovered) {
                g.fill(rect.x() + 1, rowY, rect.right() - 1, rowY + 12, 0x408B6B1B);
            }
            recordText(TextLayer.TOP, labels[i], rect.x() + 4, rowY + 2, PeStyle.TEXT);
        }
    }

    private void runContextOption(ContextMenu menu, int option) {
        selectStorageById(menu.storageId());
        switch (option) {
            case 0 -> pickUpFromStorage(menu.slot());
            case 1 -> withdrawFromStorage(menu.slot());
            case 2 -> {
                if (storage.hasPermission(StoragePermission.SELL)) {
                    if (sellQueue.containsKey(menu.slot().slotIndex())) {
                        sellQueue.remove(menu.slot().slotIndex());
                    } else {
                        sellQueue.put(menu.slot().slotIndex(), new PendingSell(
                                menu.slot().slotIndex(), menu.slot().itemId(),
                                menu.slot().count(), menu.slot().fingerprint()));
                    }
                }
            }
            default -> {
            }
        }
    }

    /** 按原因聚合的跳过明细文案："无卖价×1 禁止交易×1"（无跳过时返回空串）。 */
    private String skipReasonDetail() {
        if (sellPreview == null || sellPreview.skipReasons().isEmpty()) {
            return "";
        }
        Map<ExchangeUiModel.SkipReason, Integer> counts = new LinkedHashMap<>();
        for (ExchangeUiModel.SkipReason reason : sellPreview.skipReasons()) {
            counts.merge(reason, 1, Integer::sum);
        }
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<ExchangeUiModel.SkipReason, Integer> entry : counts.entrySet()) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append(t(skipReasonKey(entry.getKey()))).append('×').append(entry.getValue());
        }
        return detail.toString();
    }

    private static String skipReasonKey(ExchangeUiModel.SkipReason reason) {
        return switch (reason) {
            case ZERO_COUNT -> "poketrade.exchange.sell.preview.skip.zero";
            case NO_PRICE -> "poketrade.exchange.sell.preview.skip.noprice";
            case BLACKLISTED -> "poketrade.exchange.sell.preview.skip.blacklisted";
            case NOT_ALLOWED -> "poketrade.exchange.sell.preview.skip.not_allowed";
        };
    }

    /** 指针物品 tooltip：名称/类别/来源模组/买价/卖价。 */
    @Override
    protected void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        // [CHANGED] 会话 #12：矩阵外传入屏幕坐标 mx/my，命中换算回局部坐标
        // （toLocalX(mx) 与原 lmx 恒等，结果与现状一致）；g.renderTooltip 在矩阵外
        // 以屏幕坐标绘制 → tooltip 文字像素级清晰。
        int x = toLocalX(mouseX), y = toLocalY(mouseY);
        if (sellPreview != null) {
            // 弹窗打开时禁止下层目录/购物车/仓储的悬停提示，避免提示浮在弹窗之上
            return;
        }
        // 左栏：手风琴表头信息 / 展开网格槽位信息
        if (!leftCollapsed) {
            AccordionEntry entry = accordionEntryAt(x, y);
            if (entry != null) {
                if (entry.header().contains(x, y)) {
                    StorageDescriptor d = entry.descriptor();
                    List<Component> lines = new ArrayList<>();
                    lines.add(Component.literal(d.displayName()));
                    lines.add(Component.translatable(
                            "poketrade.storage.type." + d.storageId().adapterType()));
                    lines.add(Component.translatable(d.claimed()
                                    ? "poketrade.storage.distance" : "poketrade.gui.unclaimed",
                            d.distance()));
                    String owner = d.ownerId() == null ? "-"
                            : d.ownerId().toString().substring(0, 8) + "…";
                    lines.add(Component.translatable("poketrade.storage.owner", owner));
                    if (d.scanComplete() && d.slotCount() > 0) {
                        lines.add(Component.translatable("poketrade.storage.capacity",
                                d.usedSlots(), d.slotCount()));
                    }
                    StringBuilder perms = new StringBuilder();
                    for (StoragePermission p : StoragePermission.values()) {
                        if (storage.allowsOn(d.storageId(), p)) {
                            if (perms.length() > 0) {
                                perms.append(' ');
                            }
                            perms.append(t(permissionKey(p)));
                        }
                    }
                    if (perms.length() > 0) {
                        // [CHANGED] Bug 修复：Component.translatable 的 args 必须是
                        // Component/Number/Boolean/String 单值，StringBuilder 会触发
                        // TranslatableContents 参数校验异常（此前 renderTooltip 从未被调用
                        // 而掩盖，Bug D 修复后悬停仓储表头即崩溃）。转 String 传入。
                        lines.add(Component.translatable(
                                "poketrade.storage.permissions", perms.toString()));
                    }
                    g.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
                    return;
                }
                StorageItemSlot slot = accordionSlotAt(entry, x, y);
                if (slot != null) {
                    ItemStack s = toStack(slot);
                    List<Component> lines = new ArrayList<>();
                    lines.add(s.isEmpty() ? Component.literal(slot.itemId()) : s.getHoverName());
                    lines.add(Component.translatable("poketrade.exchange.snapshot.hint"));
                    // [CHANGED] Bug G：仓储槽位 tooltip 同样带物品图标
                    g.renderTooltip(this.font, lines, java.util.Optional.empty(), s, mouseX, mouseY);
                    return;
                }
            }
        }
        // 存入格提示：单件直接出售
        if (layout.deposit().contains(x, y)) {
            g.renderTooltip(this.font, List.of(
                            Component.translatable("poketrade.exchange.sell.direct.hint")),
                    java.util.Optional.empty(), mouseX, mouseY);
            return;
        }
        if (layout.catalogGrid().contains(x, y)) {
            int idx = (x - layout.catalogGrid().x()) / SLOT
                    + ((y - layout.catalogGrid().y()) / SLOT) * gridCols
                    + catalogScroll * gridCols;
            List<ExchangeCatalogPacket.EntryWire> visibleCatalog = visibleCatalog();
            if (idx < visibleCatalog.size()) {
                ExchangeCatalogPacket.EntryWire e = visibleCatalog.get(idx);
                ItemStack s = stackOf(e);
                List<Component> lines = List.of(
                        s.isEmpty() ? Component.literal(e.itemId()) : s.getHoverName(),
                        // [CHANGED] Bug F：分类此前是服务端固化的英文名（Building Blocks 等），
                        // 现在是可翻译键（itemGroup.buildingBlocks），经 categoryLabel 本地化后显示中文。
                        Component.translatable("poketrade.exchange.tooltip.source",
                                categoryLabel(e.category()), e.modId()),
                        Component.translatable("poketrade.exchange.buy")
                                .append(Component.literal(": " + ExchangeUiModel.formatAmount(e.buyPrice()))),
                        Component.translatable("poketrade.exchange.sell")
                                .append(Component.literal(": " + ExchangeUiModel.formatAmount(e.sellPrice()))));
                if (e.buyPrice() <= 0) {
                    lines = new java.util.ArrayList<>(lines);
                    lines.add(Component.translatable("poketrade.exchange.buy.unavailable"));
                }
                // [CHANGED] Bug G：自定义 tooltip 此前不带 ItemStack，无物品图标，与原版背包
                // tooltip 样式不一致；传入 s 使 tooltip 左上角渲染物品图标，与基类 hoveredSlot
                // tooltip（super.renderTooltip）观感一致。
                g.renderTooltip(this.font, lines, java.util.Optional.empty(), s, mouseX, mouseY);
                return;
            }
        }
        // 购物车格 tooltip：名称 + 数量 + 小计
        if (!rightCollapsed && layout.cartGrid().contains(x, y)) {
            int idx = (x - layout.cartGrid().x()) / SLOT
                    + ((y - layout.cartGrid().y()) / SLOT) * cartCols
                    + cartScroll * cartCols;
            if (idx >= 0 && idx < cart.size()) {
                ExchangeUiModel.CartLine line = cart.get(idx);
                ResourceLocation rl = ResourceLocation.tryParse(line.itemId());
                ItemStack s = rl == null ? ItemStack.EMPTY
                        : new ItemStack(BuiltInRegistries.ITEM.get(rl));
                long unit = 0;
                for (ExchangeCatalogPacket.EntryWire entry : catalog) {
                    if (entry.itemId().equals(line.itemId())) {
                        unit = entry.buyPrice();
                        break;
                    }
                }
                long subtotal;
                try {
                    subtotal = Math.multiplyExact(unit, line.count());
                } catch (ArithmeticException ignored) {
                    subtotal = Long.MAX_VALUE;
                }
                List<Component> lines = new ArrayList<>();
                lines.add(s.isEmpty() ? Component.literal(line.itemId()) : s.getHoverName());
                lines.add(Component.translatable("poketrade.exchange.cart.tooltip",
                        line.count(), ExchangeUiModel.formatAmount(subtotal)));
                // [CHANGED] Bug G：购物车格 tooltip 带物品图标
                g.renderTooltip(this.font, lines, java.util.Optional.empty(), s, mouseX, mouseY);
                return;
            }
        }
        super.renderTooltip(g, mouseX, mouseY);
    }

    private static String permissionKey(StoragePermission p) {
        return switch (p) {
            case VIEW -> "poketrade.permission.view";
            case DEPOSIT -> "poketrade.permission.deposit";
            case WITHDRAW -> "poketrade.permission.withdraw";
            case SELL -> "poketrade.permission.sell";
            case BREAK -> "poketrade.permission.break";
            case MANAGE -> "poketrade.permission.manage";
        };
    }

    /** 左栏内容：仓储列表 + 选中仓储快照槽位（渲染在 translate 内，用相对坐标）。 */
    private void renderLeftPanel(GuiGraphics g, int x, int y) {
        // 范围行：标签 + 点击切换按钮（显示当前档位；selected 高亮表示当前生效值）
        recordText(TextLayer.MAIN, t("poketrade.gui.range"),
                layout.left().x() + 2, layout.radiusInput().y() + 2, PeStyle.TEXT);
        ExchangeUiModel.Rect radiusCtrl = layout.radiusInput();
        PeStyle.segmentedBg(g, radiusCtrl.x(), radiusCtrl.y(),
                radiusCtrl.width(), radiusCtrl.height(),
                true, false, 0, 0);
        recordSegmented(TextLayer.MAIN, String.valueOf(storage.getRadius()), true,
                radiusCtrl.x(), radiusCtrl.y(), radiusCtrl.width(), radiusCtrl.height());
        // 物品搜索提示
        // [CHANGED] 会话 #12：hint 与 storageSearchBox 内部文字同尺寸，随矩阵缩放（取舍见开发日志）
        if (storageSearchBox != null && storageSearchBox.getValue().isEmpty()
                && !storageSearchBox.isFocused()) {
            g.drawString(this.font, this.font.plainSubstrByWidth(
                            t("poketrade.exchange.storage.search.hint"),
                            Math.max(16, layout.storageSearch().width() - 4)),
                    layout.storageSearch().x() + 3, layout.storageSearch().y() + 2,
                    PeStyle.TEXT_DIM);
        }
        // 分类循环 + 可售筛选
        String catLabel = "分类：" + (slotCategoryIndex < 0 || slotCategoryIndex >= categories.size()
                ? t("poketrade.exchange.category.all")
                : categoryLabel(categories.get(slotCategoryIndex)).getString());
        catLabel = this.font.plainSubstrByWidth(catLabel,
                Math.max(8, layout.slotCategory().width() - 2));
        PeStyle.buttonBg(g, layout.slotCategory().x(), layout.slotCategory().y(),
                layout.slotCategory().width(), layout.slotCategory().height(),
                true, false, layout.slotCategory().contains(x, y),
                this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN, catLabel, true,
                layout.slotCategory().x(), layout.slotCategory().y(),
                layout.slotCategory().width(), layout.slotCategory().height());
        boolean sellFiltered = storage.getFilterMode() == StorageViewModel.FilterMode.SELL;
        PeStyle.buttonBg(g, layout.filterSell().x(), layout.filterSell().y(),
                layout.filterSell().width(), layout.filterSell().height(),
                true, sellFiltered, layout.filterSell().contains(x, y),
                this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN,
                t(sellFiltered ? "poketrade.exchange.filter.sellable" : "poketrade.exchange.filter.all"),
                true,
                layout.filterSell().x(), layout.filterSell().y(),
                layout.filterSell().width(), layout.filterSell().height());
        // 仓储手风琴：每个箱子一行，展开显示全部格子（按面板宽度 7 列，超 3 行滚动）
        for (AccordionEntry entry : accordionEntries()) {
            renderAccordionEntry(g, entry, x, y);
        }
        // 出售区按钮（刷新 / 清空待售 / 存入；批量出售已移到购物车）
        PeStyle.buttonBg(g, layout.storageRefresh().x(), layout.storageRefresh().y(),
                layout.storageRefresh().width(), layout.storageRefresh().height(),
                !workflow.pending(), false,
                layout.storageRefresh().contains(x, y), this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN, t("poketrade.exchange.refresh"), !workflow.pending(),
                layout.storageRefresh().x(), layout.storageRefresh().y(),
                layout.storageRefresh().width(), layout.storageRefresh().height());
        PeStyle.buttonBg(g, layout.storageClear().x(), layout.storageClear().y(),
                layout.storageClear().width(), layout.storageClear().height(),
                !sellQueue.isEmpty(), false,
                layout.storageClear().contains(x, y), this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN, t("poketrade.exchange.cart.clear"), !sellQueue.isEmpty(),
                layout.storageClear().x(), layout.storageClear().y(),
                layout.storageClear().width(), layout.storageClear().height());
        boolean depositEnabled = storage.getSelectedStorageId() != null
                && storage.hasPermission(StoragePermission.DEPOSIT) && !workflow.pending();
        PeStyle.buttonBg(g, layout.storageDeposit().x(), layout.storageDeposit().y(),
                layout.storageDeposit().width(), layout.storageDeposit().height(),
                depositEnabled, false,
                layout.storageDeposit().contains(x, y), this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN, t("poketrade.exchange.deposit"), depositEnabled,
                layout.storageDeposit().x(), layout.storageDeposit().y(),
                layout.storageDeposit().width(), layout.storageDeposit().height());
    }

    private void renderAccordionEntry(GuiGraphics g, AccordionEntry entry, int x, int y) {
        StorageDescriptor d = entry.descriptor();
        ExchangeUiModel.Rect header = entry.header();
        boolean selected = d.storageId().equals(storage.getSelectedStorageId());
        if (selected) {
            g.fill(header.x() + 1, header.y(), header.right() - 1, header.bottom(), 0x408B6B1B);
        }
        // 只显示容器名称；末影箱带紫色「末」徽标
        String name = d.displayName();
        boolean ender = "vanilla_ender_chest".equals(d.storageId().adapterType());
        int tx = header.x() + 2;
        if (ender) {
            g.fill(tx, header.y() + 2, tx + 8, header.y() + 10, 0xFF1A1A24);
            recordText(TextLayer.MAIN, "末", tx + 1, header.y() + 2, 0xFFA98BD6);
            tx += 10;
        }
        String rowText = this.font.plainSubstrByWidth(name,
                Math.max(16, header.width() - 20 - (tx - header.x())));
        recordText(TextLayer.MAIN, rowText, tx, header.y() + 2, PeStyle.TEXT_TITLE);
        // 展开/收起按钮（表头右侧）
        String arrow = entry.expanded() ? "▾" : "▸";
        PeStyle.buttonBg(g, header.right() - 15, header.y() + 1, 13, 10,
                true, false, header.contains(x, y) && x >= header.right() - 15,
                this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN, arrow, true,
                header.right() - 15, header.y() + 1, 13, 10);
        if (!entry.expanded()) {
            return;
        }
        String key = d.storageId().asString();
        List<StorageItemSlot> slots = filteredSlots(snapshotsByStorage.get(key));
        ExchangeUiModel.Rect grid = entry.grid();
        int cols = snapshotCols;
        int visibleRows = Math.max(1, grid.height() / SLOT);
        // [CHANGED] 会话 #11：滚动钳制范围按容器容量（未裁剪行数），否则双箱第 8 排索引不可达（问题 2）。
        // slots 仍用于画物品与空态文案，与网格行数无关。
        int totalRows = ExchangeUiModel.accordionContentRows(d.slotCount(), cols);
        int scroll = Math.max(0, Math.min(entry.gridScroll(),
                Math.max(0, totalRows - visibleRows)));
        int start = scroll * cols;
        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < cols; col++) {
                int index = start + row * cols + col;
                int sx = grid.x() + col * SLOT;
                int sy = grid.y() + row * SLOT;
                PeStyle.slot(g, sx, sy);
                if (index < slots.size()) {
                    StorageItemSlot slot = slots.get(index);
                    PendingSell pending = sellQueue.get(slot.slotIndex());
                    if (pending != null && pending.itemId().equals(slot.itemId())) {
                        g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0x338B6B1B);
                    }
                    ItemStack stack = toStack(slot);
                    if (!stack.isEmpty()) {
                        g.renderItem(stack, sx + 1, sy + 1);
                        g.renderItemDecorations(this.font, stack, sx + 1, sy + 1);
                    }
                }
            }
        }
        PeStyle.scrollbar(g, grid.right() + 1, grid.y(), grid.height(),
                totalRows, visibleRows, scroll);
        if (slots.isEmpty()) {
            recordText(TextLayer.MAIN, t("poketrade.gui.empty"),
                    grid.x() + 2, grid.y() + 8, PeStyle.TEXT_DIM);
        }
    }

    private ItemStack toStack(StorageItemSlot slot) {
        ItemStack stack = ItemStack.EMPTY;
        try {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(slot.itemId()));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                stack = new ItemStack(item);
                stack.setCount(slot.count());
            }
        } catch (RuntimeException ignored) {
            // 物品 id 无法解析时显示空
        }
        return stack;
    }
}
