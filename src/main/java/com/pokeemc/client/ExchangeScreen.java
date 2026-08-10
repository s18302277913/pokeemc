package com.pokeemc.client;

import com.pokeemc.PokeEMC;
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
import com.pokeemc.network.StorageBatchPacket;
import com.pokeemc.network.StorageSellPacket;
import com.pokeemc.network.StorageSnapshotPacket;
import com.pokeemc.network.StorageWithdrawCarriedPacket;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.adapter.PokeballIdentity;
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
import java.util.LinkedHashSet;
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
    // [REMOVED] 会话 #21-F Bug 2：原 MAX_ACCORDION_ROWS=7 上限随每格小滑条一并移除——
    // 展开箱现按容器全部行渲染（单箱 4、双箱 8），越界行由手风琴大滑条滚动访问。

    /**
     * 左栏手风琴列表的底部裁剪线（布局坐标）：列表/展开网格不得越过此线，
     * 防止压住底部按钮（storageRefresh 等位于 y=228）。与 {@code accordionEntries()}
     * 原字面量 226 一致；整体滚动条高度与 scissor 裁剪都以此为界。
     */
    private static final int ACCORDION_BOTTOM_LIMIT = 226;

    private final StorageViewModel storage = new StorageViewModel();
    private final String sessionId = UUID.randomUUID().toString().substring(0, 8);
    private final List<ExchangeCatalogPacket.EntryWire> catalog = new ArrayList<>();
    /** [NEW] 会话 #21-H 修订：服务端下发的全量出售价表（itemId → sellPrice>0），与浏览目录解耦——
     *  学习模式目录只含「卖过」的物品，但出售预览必须覆盖全部有卖价的物品（修复学习模式卖不了）。 */
    private Map<String, Long> sellPriceMap = Map.of();
    private final List<String> categories = new ArrayList<>();
    private final List<String> blockedItems = new ArrayList<>();
    private final List<String> allowedItems = new ArrayList<>();
    private boolean allowlistEnabled;
    /** 服务端买入/出售总开关（随目录响应下发）。 */
    private boolean buyEnabled = true;
    private boolean sellEnabled = true;
    // [CHANGED] Bug 6：购物车容量 27 → 54（双箱）
    private final ExchangeUiModel.Cart cart = new ExchangeUiModel.Cart(ExchangeUiModel.Layout.CART_CAPACITY, 1024);
    private final ExchangeUiModel.Workflow workflow = new ExchangeUiModel.Workflow();
    /** [CHANGED] 会话 #21-B：操作说明帮助面板开关（helpButton 点击切换，点面板外关闭）。 */
    private boolean showHelp;
    /** [CHANGED] 会话 #21-C：一键出售模式选择弹窗开关（点击 sellWhole 显示）。 */
    private boolean showSellWholePopup;
    /** [CHANGED] 会话 #21-C：弹窗内当前作用域（初始取配置默认值，弹窗选择后写回配置）。 */
    private PokeTradeConfig.SellWholeMode sellWholeMode = PokeTradeConfig.sellWholeMode();
    /** [CHANGED] 会话 #21-C：弹窗内「不再提示」勾选态（弹窗打开时从配置读取）。 */
    private boolean sellWholeDontAsk;
    /** [NEW] 会话 #21-H 修订：仓储分类选择弹窗开关（点击 slotCategory 显示）。 */
    private boolean showCategoryModal;
    /** [NEW] 会话 #21-H 修订：分类弹窗滚动偏移（0 起；第 0 项为「全部」）。 */
    private int categoryScroll;
    /** 仓储出售区：仓储槽位索引 -> 待出售条目（虚拟视图，真实物品留在仓储）。
     *  [CHANGED] 会话 #19：支持多箱子（一键出售所有展开箱子），key = storageId#slotIndex。 */
    private final Map<String, PendingSell> sellQueue = new LinkedHashMap<>();

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
    /** 最近一次目录响应的服务端 catalogVersion（会话 #16：目录变更推送据此判断是否重拉）。 */
    private long lastCatalogVersion = -1;
    /** [NEW] 会话 #21-H：服务端目录模式（"LEARNING"/"FULL"），仅作 UI 指示器（学习过滤在服务端）。 */
    private String catalogMode = "";
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
    /**
     * [CHANGED] 会话 #21-F Bug 1：非选中箱槽位操作挂起——点击未选中箱的格子时，
     * 先选中该箱（异步请求新快照），但缓存快照可能过期（指纹/版本与服务端不符），
     * 立即发操作会被服务端以 content_changed/revision_conflict 拒绝（反馈：列表
     * 第 3 个箱子无法取出/贩卖/放置，「状态已变化」）。改为挂起到该箱新快照到达，
     * 由 {@link #replayPendingSlotAction(StorageId)} 用新数据重放。选中变化即取消。
     */
    private PendingSlotAction pendingSlotAction;
    private enum PendingSlotOp { PICKUP, WITHDRAW, CONTEXT_MENU }
    private record PendingSlotAction(StorageId storageId, int slotIndex,
                                     PendingSlotOp op, int x, int y) {
    }
    /**
     * 上次拿起的时间戳（会话 #15-B：拿起后短暂窗口内抑制同一手势的立即回存）。
     * 时间戳比 {@code pendingPickup} 布尔更可靠：即使回执提前清了布尔，仍记录"刚拿起"。
     * [FIXED] 会话 #15-C：哨兵用 0（默认值）而非 Long.MIN_VALUE——后者使
     * {@code now - at} 下溢为负、守卫恒真，拖入仓储格子的松开事件被全部吞掉。
     * 判定收敛到 {@link ExchangeUiModel#immediateRedepositSuppressed}。
     */
    private long pendingPickupAt;
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
    /** [CHANGED] 会话 #21-G：上次会话保存的展开/收起状态（storageId 列表，init 时从客户端
     *  配置加载），首个查询回包据此恢复玩家选择——与范围档位持久化行为一致。 */
    private final java.util.Set<String> savedExpandedStorages = new java.util.LinkedHashSet<>();
    /** [CHANGED] 会话 #11：首个仓储查询回包才自动展开首个仓储（修复 10 秒刷新把全收起误判为首次打开）。 */
    private final ExchangeUiModel.FirstQueryGate firstQueryGate = new ExchangeUiModel.FirstQueryGate();
    /** 每个仓储的快照与 revision（展开时拉取）。 */
    private final Map<String, StorageSnapshot> snapshotsByStorage = new java.util.HashMap<>();
    private final Map<String, Long> revisionsByStorage = new java.util.HashMap<>();
    // [REMOVED] 会话 #21-F Bug 2：移除每格小滑条（storageScrolls）——展开箱显示全部行，
    // 由手风琴大滑条导航，不再嵌套滚动条。
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
            // [CHANGED] 会话 #15-D：改回布局局部坐标，随 beginScaledRender 矩阵缩放
            // （与 StorageBrowserScreen 现有做法一致；0.75/0.5 档轻微模糊为已接受取舍）。
            if (layer == TextLayer.MAIN) {
                // [CHANGED] Bug 7：MAIN 层文字 z 上移到 160（> 物品图标 renderItem z=150），
                // 否则与物品重叠处的标签（标题/统计/槽位文字）被渲染物品的深度测试 LEQUAL 剔除吞掉。
                // 仍低于弹窗/右键菜单 z=400，弹窗盖住主界面文字的行为不回归。
                g.pose().pushPose();
                g.pose().translate(0, 0, 160);
                g.drawString(this.font, d.text(), d.x(), d.y(), d.color(), d.shadow());
                g.pose().popPose();
            } else {
                g.drawString(this.font, d.text(), d.x(), d.y(), d.color(), d.shadow());
            }
        }
    }

    private record ContextMenu(int x, int y, StorageItemSlot slot, StorageId storageId) {
    }

    /** [CHANGED] 会话 #19：加 storageId 支持跨箱子待售（一键出售所有展开箱子）。 */
    private record PendingSell(StorageId storageId, int slotIndex, String itemId, int count, long fingerprint) {
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
        // 每击翻倍，最大 648 后重置默认 16）。[CHANGED] 会话 #21-D：初始档位读取客户端配置
        // （storageRadius），玩家上次切换的档位持久化、重进交易所恢复。
        storage.setRadius(PokeTradeConfig.storageRadius());
        // [CHANGED] 会话 #21-G：加载上次保存的仓储展开/收起状态（重进交易所恢复；
        // 空=首次使用，onQueryResponse 首个回包默认全部展开）。
        savedExpandedStorages.clear();
        savedExpandedStorages.addAll(PokeTradeConfig.expandedStorages());
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
        // [CHANGED] 会话 #16：开屏先消费缓存的最近一次目录（无屏在途响应），
        // 再发新鲜请求覆盖——避免冷启动/切屏时列表空白。
        ExchangeCatalogPacket.Response cached = ClientCatalogCache.latest;
        if (cached != null && cached.catalogVersion() != this.lastCatalogVersion) {
            applyCatalog(cached);
        }
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
        // 标题居中：宝可梦交易所。// [CHANGED] Bug 4：标题从顶行 y=4 下移到钱包行下方
        // （wallet.y()=128 → 137），左栏收起时也照常显示（见 renderLabels）。
        // [CHANGED] 会话 #21-B：X 改为<b>跟随中栏居中</b>（middle.x + (middle.width-titleW)/2，
        // 而非整窗口居中——整窗宽随左右栏收起变化会令标题漂移，玩家指出这是逻辑谬误）；
        // Y 从 wallet.y()+9=137 再往下挪到 +18=146（与 priceHint 对调让位）。
        int titleW = this.font.width(this.title.getString());
        this.titleLabelX = Math.max(0,
                this.layout.middle().x() + (this.layout.middle().width() - titleW) / 2);
        this.titleLabelY = this.layout.wallet().y() + 18;
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
        // [CHANGED] Bug 6：cartScroll 改行式钳制（totalRows = ceil(种类数/列数)，可见 4 行）。
        this.cartScroll = ExchangeUiModel.clampScroll(this.cartScroll,
                ExchangeUiModel.accordionContentRows(this.cart.size(), this.cartCols),
                ExchangeUiModel.Layout.CART_ROWS);
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

    /**
     * [NEW] 会话 #21-H 修订：学习模式出售成功后自动刷新目录——刚卖掉的物品立即进入
     * 可买回列表。仅学习模式触发（全高亮目录不含出售历史维度，刷新无意义）。
     * NeoForge 网络包按连接顺序处理，本方法紧接出售包发送，目录请求必在出售包之后
     * 到达服务端，出售历史已记录，新条目可见。
     */
    private void refreshCatalogIfLearning() {
        if ("LEARNING".equals(this.catalogMode)) {
            requestCatalog();
        }
    }

    @Override
    public void onCatalogResponse(ExchangeCatalogPacket.Response packet) {
        if (!ExchangeUiModel.isCurrentCatalogResponse(this.catalogRequestId, packet.sessionId())) {
            return;
        }
        applyCatalog(packet);
    }

    /**
     * 应用目录响应（覆盖现有目录/分类/规则并记录服务端版本）。会话 #16：
     * 抽取为公共路径——响应投递（{@link #onCatalogResponse}）与开屏时消费
     * {@link com.pokeemc.client.ClientCatalogCache}（无屏在途响应）共用。
     */
    private void applyCatalog(ExchangeCatalogPacket.Response packet) {
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
        // [CHANGED] 会话 #16：记录服务端目录版本，目录变更推送（CatalogChangedPacket）
        // 携同一版本时不再重复拉取。
        this.lastCatalogVersion = packet.catalogVersion();
        this.catalogScroll = 0;
        // C1：与 responder 的 lastSearchText 对齐，杜绝任何回填/重复字符造成的循环请求
        this.lastSearchText = this.searchBox == null ? "" : this.searchBox.getValue();
        // [NEW] 会话 #21-H：记录服务端目录模式（学习/全高亮）供 UI 指示器；防御：当前选中分类
        // 在模式切换后可能整体消失（如全高亮下的仅可买分类切回学习），复位为空回到"全部"。
        this.catalogMode = packet.mode() == null ? "" : packet.mode();
        // [NEW] 会话 #21-H 修订：保存全量出售价表（出售预览用，不受学习模式目录过滤影响）
        this.sellPriceMap = packet.sellPrices() == null ? Map.of() : packet.sellPrices();
        if (!this.catalogMode.isEmpty() && !this.categories.contains(this.activeCategory)) {
            this.activeCategory = "";
        }
    }

    /**
     * 服务端目录已变更（CatalogChangedPacket，会话 #16）：版本与本地不同时重拉目录，
     * 修复「有价但列表没有该物品 + 数据包重载后开着的屏幕列表不刷新」。
     */
    @Override
    public void onCatalogChanged(long catalogVersion) {
        if (catalogVersion != this.lastCatalogVersion) {
            requestCatalog();
        }
    }

    /** 仓储列表已变化（StorageChangedPacket，会话 #29）：以当前条件重查。 */
    @Override
    public void onStorageListChanged() {
        requestStorages();
    }

    /**
     * 目录条目 → 渲染用 ItemStack（含组件）。
     * [CHANGED] 会话 #14：球类 itemId 含 '#'（pixelmon:poke_ball#master_ball），
     * ResourceLocation.tryParse 返回 null → 球类图标渲染为空气。改经
     * PokeballIdentity.decode 还原带 POKE_BALL 组件的栈（大师球图标/颜色正确）。
     */
    private ItemStack stackOf(ExchangeCatalogPacket.EntryWire e) {
        ItemStack s = PokeballIdentity.decode(e.itemId(), 1);
        return (s == null || s.isEmpty()) ? ItemStack.EMPTY : s;
    }

    /** 本地化显示名解析器（C2/C6：目录与出售预览共用；解析失败返回空串）。 */
    private String displayNameOf(String itemId) {
        // [CHANGED] Bug 1：球类 itemId（pixelmon:poke_ball#master_ball）先经身份
        // 解码还原球种，否则默认实例 hoverName 恒为「精灵球」。
        try {
            String ballName = PokeballIdentity.displayName(itemId);
            if (ballName != null) {
                return ballName;
            }
        } catch (RuntimeException ignored) {
            // 回退到注册表解析
        }
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
        // [CHANGED] 会话 #19：背包出售 itemId 必须经 PokeballIdentity.encode 编码球种——
        // 注册表键只给 pixelmon:poke_ball，服务端 countInInventory 按 #球种 精确匹配，
        // 坍缩键永不相等 → 报「数量无效」（玩家复反馈 bug）。非球物品 encode 仍为注册表键。
        String id = PokeballIdentity.encode(stack);
        if (id == null) {
            return;
        }
        List<ExchangeUiModel.SourceLine> source = List.of(
                new ExchangeUiModel.SourceLine(id,
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
        // [CHANGED] 会话 #19：同 inventorySource —— 球类须 encode 球种，注册表键会坍缩
        // 成 pixelmon:poke_ball 导致服务端匹配不到、报「数量无效」。
        String id = PokeballIdentity.encode(stack);
        if (id == null) {
            return;
        }
        // 无回收价的物品直接拦截，避免服务端回“未知物品”造成困惑
        if (sellPrices().getOrDefault(id, 0L) <= 0L) {
            sellMessage = t("poketrade.exchange.sell.no_price");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        List<ExchangeSellPacket.LineWire> lines = List.of(
                new ExchangeSellPacket.LineWire(id, stack.getCount()));
        if (lines.isEmpty()) {
            return;
        }
        if (!workflow.begin(ExchangeUiModel.Operation.INVENTORY_SELL, menu.getResultNonce())) {
            return;
        }
        PacketDistributor.sendToServer(new ExchangeSellPacket(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), lines));
        // [NEW] 会话 #21-H 修订：学习模式卖完刷新目录（新卖过物品立即可买回）
        refreshCatalogIfLearning();
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
            // [CHANGED] 会话 #19：球类必须 encode 球种，注册表键全球坍缩成 pixelmon:poke_ball——
            // 既让批量出售把所有球种混为一行，又与服务端 #球种 匹配不上报「数量无效」。
            String id = PokeballIdentity.encode(stack);
            if (id != null) {
                source.add(new ExchangeUiModel.SourceLine(id, stack.getHoverName().getString(), stack.getCount()));
            }
        }
        // 副手槽（40 号虚拟槽位在背包渲染区之外，单独补扫）
        ItemStack offhand = inv.offhand.isEmpty() ? ItemStack.EMPTY : inv.offhand.get(0);
        if (!offhand.isEmpty()) {
            String id = PokeballIdentity.encode(offhand);
            if (id != null) {
                source.add(new ExchangeUiModel.SourceLine(id, offhand.getHoverName().getString(), offhand.getCount()));
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
    /**
     * [CHANGED] 会话 #19：仓储 Shift 快捷取出 = 任一 Shift 键。
     * 此前按「非贩卖键」隔离（LEFT 配置下仅右 Shift 触发），玩家按左 Shift 点
     * 仓储格子会落成「拿起」而非取出，反馈「Shift 快捷取出失效」。背包区 Shift
     * 贩卖仍按配置键（{@link #shiftSellActive}），仓储区与背包区位置天然分离，
     * 左右 Shift 点击仓储槽位均可取出，无键位混淆。
     */
    private boolean storageWithdrawShift() {
        return hasShiftDown();
    }

    /** [CHANGED] 会话 #10：Shift+左键点击背包物品 = 卖该格整叠。 */
    private void shiftSellStack(ItemStack stack) {
        // [CHANGED] 会话 #19：球类须 encode 球种（对齐服务端 countInInventory 匹配键）。
        String id = PokeballIdentity.encode(stack);
        if (id == null) {
            return;
        }
        shiftSell(new ExchangeUiModel.SourceLine(
                id, stack.getHoverName().getString(), stack.getCount()));
    }

    /** [CHANGED] 会话 #10：Shift+右键点击背包物品 = 卖背包+副手全部同 ID 物品（整组）。 */
    private void shiftSellGroup(ItemStack stack) {
        // [CHANGED] 会话 #19：整组出售按球种聚合——encode 球种键，避免所有球混为一组。
        String id = PokeballIdentity.encode(stack);
        if (id == null) {
            return;
        }
        long total = ExchangeUiModel.groupCount(inventorySource(), id);
        if (total <= 0) {
            return;
        }
        shiftSell(new ExchangeUiModel.SourceLine(
                id, stack.getHoverName().getString(), (int) total));
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
        // [NEW] 会话 #21-H 修订：学习模式卖完刷新目录（新卖过物品立即可买回）
        refreshCatalogIfLearning();
        sellMessage = t("poketrade.exchange.sell.sent");
        sellMessageColor = PeStyle.TEXT_DIM;
    }

    private Map<String, Long> sellPrices() {
        // [CHANGED] 会话 #21-H 修订：优先用服务端下发的全量出售价表——浏览目录在学习模式下
        // 只含「卖过」的物品，若用它查价则新物品无卖价 → 出售预览丢弃所有行 → 卖不了。
        // 价表为空（旧服务端/异常）时回退目录构建，保证行为不退化。
        if (!sellPriceMap.isEmpty()) {
            return sellPriceMap;
        }
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
            // [NEW] 会话 #21-H 修订：学习模式卖完刷新目录（新卖过物品立即可买回）
            refreshCatalogIfLearning();
        } else {
            sendStorageSell();
        }
    }

    /**
     * 出售预览里点击单条：只出售该物品。
     * [CHANGED] 会话 #21-D：背包来源发背包出售包；仓储来源改走 {@link #sellSingleStorageLine}
     * 只卖该行匹配的全部待售槽位（此前仓储预览点击单条无任何反应）。
     */
    private void sellSingleLine(ExchangeUiModel.PreviewLine line) {
        if (sellPreview == null || workflow.pending()) {
            return;
        }
        if (sellPreview.source() == ExchangeUiModel.SellSource.INVENTORY) {
            if (!workflow.begin(ExchangeUiModel.Operation.INVENTORY_SELL, menu.getResultNonce())) {
                return;
            }
            List<ExchangeSellPacket.LineWire> lines = List.of(
                    new ExchangeSellPacket.LineWire(line.itemId(), line.count()));
            PacketDistributor.sendToServer(new ExchangeSellPacket(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), lines));
            // [NEW] 会话 #21-H 修订：学习模式卖完刷新目录（新卖过物品立即可买回）
            refreshCatalogIfLearning();
            return;
        }
        sellSingleStorageLine(line.itemId());
    }

    /**
     * [CHANGED] 会话 #21-D：仓储预览点击单条 → 只出售该行（itemId 匹配的全部待售槽位）。
     * 从 sellQueue 收集匹配 PendingSell 构造子集 StorageSellPacket 发送（revisions 逐箱取），
     * 成功后从队列移除并重建预览；队列空则关闭预览并提示。与「确认出售」走同一条服务端链路。
     */
    private void sellSingleStorageLine(String itemId) {
        if (storagePreview == null || !storagePreview.canConfirm()) {
            return;
        }
        if (!workflow.begin(ExchangeUiModel.Operation.STORAGE_SELL, menu.getResultNonce())) {
            return;
        }
        List<PendingSell> matching = sellQueue.values().stream()
                .filter(p -> itemId.equals(p.itemId())).toList();
        if (matching.isEmpty()) {
            return;
        }
        List<ExchangeService.SellEntry> entries = new ArrayList<>();
        Map<StorageId, Long> revisions = new LinkedHashMap<>();
        for (PendingSell p : matching) {
            entries.add(new ExchangeService.SellEntry(
                    p.storageId(), p.slotIndex(), p.count(), p.fingerprint()));
            revisions.put(p.storageId(), revisionsByStorage.getOrDefault(
                    p.storageId().asString(), -1L));
        }
        PacketDistributor.sendToServer(new StorageSellPacket(
                sessionId, UUID.randomUUID().toString(), entries, revisions));
        // [NEW] 会话 #21-H 修订：学习模式卖完刷新目录（新卖过物品立即可买回）
        refreshCatalogIfLearning();
        // 从待售队列移除已售条目；剩余条目重建预览，空则关闭预览
        sellQueue.entrySet().removeIf(e -> itemId.equals(e.getValue().itemId()));
        if (sellQueue.isEmpty()) {
            sellPreview = null;
            storagePreview = null;
            previewConfirmed = false;
            previewPage = 0;
            sellMessage = t("poketrade.exchange.sell.sent");
            sellMessageColor = PeStyle.TEXT_OK;
        } else {
            submitStorageSell();
        }
    }

    private void cancelPreview() {
        if (!workflow.pending()) {
            sellPreview = null;
            storagePreview = null;
            previewConfirmed = false;
            previewPage = 0;
            // [CHANGED] 会话 #21-D：取消预览同时清空待售队列——仓储预览的条目即 sellQueue
            // 的聚合，取消即放弃本次待售；此前残留 sellQueue 会在后续批量操作中被误检为
            // 待售物品（玩家反馈「一键出售不出售后再点批量出售会检测仓储」的根因一环）。
            sellQueue.clear();
        }
    }

    // ================= 仓储（BrowserHost） =================

    private void requestStorages() {
        PacketDistributor.sendToServer(new QueryStoragesPacket(
                sessionId, storage.getRadius(), storage.getSearchText(),
                StorageQuery.Sort.DISTANCE, StorageQuery.Filter.VIEWABLE, 200));
    }

    /** [CHANGED] 点击切换仓储扫描半径档位（翻倍，最大 648 后重置 16），并重新发起扫描。
     *  [CHANGED] 会话 #21-D：切换后写回客户端配置，重进交易所恢复上次档位（玩家反馈
     *  「范围不会自动应用保存」——此前只改内存不落盘）。 */
    private void cycleStorageRadius() {
        int next = ExchangeUiModel.nextStorageRadius(storage.getRadius());
        storage.setRadius(next);
        PokeTradeConfig.setStorageRadius(next);
        requestStorages();
    }

    /** [NEW] 会话 #21-H 修订：分类选择弹窗可视行数与行高（每行 12px，标题下方最多 9 行）。 */
    private static final int CATEGORY_VISIBLE_ROWS = 9;
    private static final int CATEGORY_ROW_H = 12;

    /** [CHANGED] 会话 #21-E：仓储列表排序档循环（距离 → 放置时间升/降 → 标记正/倒序）。
     *  [NEW] 会话 #21-H 修订：追加物品总价值正/倒序档。 */
    private static final StorageViewModel.SortMode[] STORAGE_SORT_CYCLE = {
            StorageViewModel.SortMode.DISTANCE,
            StorageViewModel.SortMode.CREATED_ASC,
            StorageViewModel.SortMode.CREATED_DESC,
            StorageViewModel.SortMode.MARKER_ASC,
            StorageViewModel.SortMode.MARKER_DESC,
            StorageViewModel.SortMode.VALUE_ASC,
            StorageViewModel.SortMode.VALUE_DESC,
    };

    /**
     * [CHANGED] 会话 #21-F Bug 3：一键展开/一键收起切换。全部可见箱子已展开 → 全部收起；
     * 否则展开全部（新展开者若无快照则请求拉取）。
     */
    private void toggleAllExpanded() {
        List<StorageDescriptor> visible = storage.visibleStorages();
        if (visible.isEmpty()) {
            return;
        }
        boolean allExpanded = true;
        for (StorageDescriptor d : visible) {
            if (!expandedStorages.contains(d.storageId().asString())) {
                allExpanded = false;
                break;
            }
        }
        if (allExpanded) {
            for (StorageDescriptor d : visible) {
                expandedStorages.remove(d.storageId().asString());
            }
            persistExpandedStorages();
            return;
        }
        for (StorageDescriptor d : visible) {
            String key = d.storageId().asString();
            if (expandedStorages.add(key) && !snapshotsByStorage.containsKey(key)) {
                requestStorageSnapshot(d.storageId());
            }
        }
        persistExpandedStorages();
    }

    /** [CHANGED] 会话 #21-G：把当前展开/收起状态写回客户端配置（重进交易所恢复，
     *  与范围档位 setStorageRadius 的 set()+save() 一致）。展开/收起切换后调用。 */
    private void persistExpandedStorages() {
        PokeTradeConfig.setExpandedStorages(expandedStorages);
    }

    /** [CHANGED] 会话 #21-F Bug 3：一键展开/收起按钮标签（全展开时显示「一键收起」）。 */
    private String expandCollapseLabel() {
        List<StorageDescriptor> visible = storage.visibleStorages();
        if (!visible.isEmpty()) {
            for (StorageDescriptor d : visible) {
                if (!expandedStorages.contains(d.storageId().asString())) {
                    return t("poketrade.exchange.expand.all");
                }
            }
        }
        return t("poketrade.exchange.collapse.all");
    }

    /** [CHANGED] 会话 #21-E：循环切换仓储列表排序档。标记档使用放置时间基准的标记表
     *  （由 onQueryResponse 分配，无需重算），距离/放置时间档由 StorageViewModel 本地重排。 */
    private void cycleStorageSort() {
        StorageViewModel.SortMode cur = storage.getSortMode();
        int idx = -1;
        for (int i = 0; i < STORAGE_SORT_CYCLE.length; i++) {
            if (STORAGE_SORT_CYCLE[i] == cur) {
                idx = i;
                break;
            }
        }
        storage.setSortMode(STORAGE_SORT_CYCLE[(idx + 1) % STORAGE_SORT_CYCLE.length]);
    }

    /** [CHANGED] 会话 #21-E：当前排序档的按钮标签。
     *  [NEW] 会话 #21-H 修订：物品总价值正/倒序档。 */
    private String sortModeLabel() {
        return switch (storage.getSortMode()) {
            case CREATED_ASC -> t("poketrade.exchange.sort.created.asc");
            case CREATED_DESC -> t("poketrade.exchange.sort.created.desc");
            case MARKER_ASC -> t("poketrade.exchange.sort.marker.asc");
            case MARKER_DESC -> t("poketrade.exchange.sort.marker.desc");
            case VALUE_ASC -> t("poketrade.exchange.sort.value.asc");
            case VALUE_DESC -> t("poketrade.exchange.sort.value.desc");
            default -> t("poketrade.exchange.sort.distance");
        };
    }

    /** [CHANGED] 会话 #21-E：当前排序档的 tooltip 说明。
     *  [NEW] 会话 #21-H 修订：物品总价值正/倒序档。 */
    private String sortModeTip() {
        return switch (storage.getSortMode()) {
            case CREATED_ASC -> t("poketrade.exchange.sort.tip.created.asc");
            case CREATED_DESC -> t("poketrade.exchange.sort.tip.created.desc");
            case MARKER_ASC -> t("poketrade.exchange.sort.tip.marker.asc");
            case MARKER_DESC -> t("poketrade.exchange.sort.tip.marker.desc");
            case VALUE_ASC -> t("poketrade.exchange.sort.tip.value.asc");
            case VALUE_DESC -> t("poketrade.exchange.sort.tip.value.desc");
            default -> t("poketrade.exchange.sort.tip.distance");
        };
    }

    @Override
    public void onQueryResponse(QueryStoragesPacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        storage.setStorages(response.storages());
        storage.setPermissionsByStorage(response.permissions());
        storage.setScanComplete(response.storages().stream().allMatch(StorageDescriptor::scanComplete));
        // [CHANGED] 会话 #21-E：以放置时间升序为基准重新分配同类型序号标记（末影箱排除）。
        // 基准固定，标记在任意排序模式下稳定，玩家可凭标记号跨排序辨认同一箱子。
        storage.recomputeMarkers(StorageViewModel.byCreatedAsc(response.storages()));
        // 清理已消失仓储的快照/展开/滚动状态
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (StorageDescriptor d : response.storages()) {
            ids.add(d.storageId().asString());
        }
        snapshotsByStorage.keySet().retainAll(ids);
        revisionsByStorage.keySet().retainAll(ids);
        expandedStorages.retainAll(ids);
        List<StorageDescriptor> visible = storage.visibleStorages();
        if (!visible.isEmpty()) {
            StorageId selected = storage.getSelectedStorageId();
            if (selected == null || !ids.contains(selected.asString())) {
                selectStorage(visible.get(0));
            }
            // [CHANGED] 会话 #11：首次查询门——只有首个回包且当前全收起时才自动展开。
            // 之前用 expandedStorages.isEmpty() 直接判断，玩家收起全部仓储后集合为空，
            // 10 秒自动刷新回包会把它误判为「首次打开」而强制展开（问题 1）。
            // [CHANGED] 会话 #21-F Bug 4：默认展开<b>全部</b>可见箱子（而非仅首个），
            // 满足「仓储列表默认打开」；快照由下方循环对无快照者逐一拉取。
            if (firstQueryGate.onQuery(!visible.isEmpty(), expandedStorages.isEmpty())) {
                if (savedExpandedStorages.isEmpty()) {
                    // 首次使用（从未保存过展开状态）：默认全部展开，随后以本次选择为基线写回。
                    for (StorageDescriptor d : visible) {
                        expandedStorages.add(d.storageId().asString());
                    }
                } else {
                    // [CHANGED] 会话 #21-G：继承上次保存的展开/收起状态（只对仍可见的箱子生效）。
                    for (StorageDescriptor d : visible) {
                        if (savedExpandedStorages.contains(d.storageId().asString())) {
                            expandedStorages.add(d.storageId().asString());
                        }
                    }
                }
                accordionScroll = 0; // 仅首次回包重置手风琴滚动到顶
                persistExpandedStorages();
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
        // [NEW] 会话 #21-H 修订：快照更新后重算全部箱子价值并注入排序模型
        refreshStorageValues();
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
            // [CHANGED] 会话 #21-F Bug 1：快照已套用（指纹/版本最新）→ 重放挂起的槽位操作
            replayPendingSlotAction(response.storageId());
        }
    }

    /**
     * [NEW] 会话 #21-H 修订：以现有快照 + 全量出售价表重算每个仓储的物品总价值并注入
     * StorageViewModel，供 VALUE_ASC/VALUE_DESC 排序使用。
     * 价值 = Σ 槽位物品数量 × 该物品出售单价（无价/未知物品按 0）。
     */
    private void refreshStorageValues() {
        Map<String, Long> values = new java.util.HashMap<>();
        for (Map.Entry<String, StorageSnapshot> e : snapshotsByStorage.entrySet()) {
            long total = 0L;
            for (StorageItemSlot slot : e.getValue().slots().values()) {
                Long unit = sellPriceMap.get(slot.itemId());
                if (unit != null && unit > 0L) {
                    total += (long) slot.count() * unit;
                }
            }
            values.put(e.getKey(), total);
        }
        storage.setValueByStorage(values);
    }

    @Override
    public void onManageResponse(StorageManagePacket.Response response) {
        // 交易所左栏不展示管理详情，忽略
    }

    private void selectStorage(StorageDescriptor descriptor) {
        // [CHANGED] 会话 #21-F Bug 1：选中变化即取消挂起的槽位操作（用户已改主意）。
        pendingSlotAction = null;
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

    /**
     * [CHANGED] 会话 #21-E：刷新选中箱 + 全部展开箱快照。此前仅刷新选中箱，
     * 非选中展开箱（含末影箱）快照永不更新 → 出售/存入后残留旧物品（末影箱刷新不及时）。
     * 服务端按 storageId 逐箱读取最新槽位；仅对展开箱发送，数量有限开销可控。
     */
    private void refreshExpandedSnapshots() {
        StorageId selected = storage.getSelectedStorageId();
        java.util.Set<String> sent = new java.util.LinkedHashSet<>();
        if (selected != null) {
            PacketDistributor.sendToServer(new StorageSnapshotPacket(sessionId, selected));
            sent.add(selected.asString());
        }
        for (StorageDescriptor d : storage.visibleStorages()) {
            String key = d.storageId().asString();
            if (expandedStorages.contains(key) && sent.add(key)) {
                PacketDistributor.sendToServer(new StorageSnapshotPacket(sessionId, d.storageId()));
            }
        }
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

    // [REMOVED] 会话 #21-H 修订：cycleSlotCategory 循环切换被分类选择弹窗取代（点击 slotCategory
    // 现在打开弹窗直接选中，无需逐次循环）。

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

    /**
     * [CHANGED] 会话 #21-E：构建预览物品逐条来源表（itemId → 可读箱名列表，含标记号，
     * 去重保持首次出现顺序）。由 {@code sellQueue}（含 storageId）派生，渲染时按需构建，
     * 免去预览清空路径的字段同步。跨箱子待售（末影箱+箱子）时精确标注每个物品来源。
     */
    private Map<String, List<String>> buildPreviewItemSources() {
        Map<String, java.util.Set<String>> byItem = new LinkedHashMap<>();
        for (PendingSell sell : sellQueue.values()) {
            byItem.computeIfAbsent(sell.itemId(), k -> new LinkedHashSet<>())
                    .add(storageDisplayName(sell.storageId()));
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.Set<String>> e : byItem.entrySet()) {
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return out;
    }

    /**
     * [CHANGED] 会话 #21-E：仓储可读名（显示名 + 同类型标记号，如 "Dev的箱子①"）。
     * 用于预览来源行/逐条 tooltip/列表标记，替代原始 storageId 键值。
     */
    private String storageDisplayName(StorageId id) {
        if (id == null) {
            return "";
        }
        for (StorageDescriptor d : storage.visibleStorages()) {
            if (d.storageId().equals(id)) {
                return displayNameWithMarker(d);
            }
        }
        return id.adapterType();
    }

    /** [CHANGED] 会话 #21-E：仓储显示名 + 同类型序号标记（末影箱无标记号，保持原名）。 */
    private String displayNameWithMarker(StorageDescriptor d) {
        Integer m = storage.getMarkers().get(d.storageId().asString());
        return d.displayName() + (m == null ? "" : StorageViewModel.markerLabel(m));
    }

    private void sendStorageSell() {
        StorageId selected = storage.getSelectedStorageId();
        if (selected == null || storagePreview == null || !storagePreview.canConfirm()
                || !workflow.begin(ExchangeUiModel.Operation.STORAGE_SELL, menu.getResultNonce())) {
            return;
        }
        List<ExchangeService.SellEntry> entries = new ArrayList<>();
        Map<StorageId, Long> revisions = new LinkedHashMap<>();
        // [CHANGED] 会话 #19：逐箱携带 storageId + 各自 revision（一键出售所有展开箱子）。
        for (PendingSell sell : sellQueue.values()) {
            entries.add(new ExchangeService.SellEntry(
                    sell.storageId(), sell.slotIndex(), sell.count(), sell.fingerprint()));
            revisions.put(sell.storageId(), revisionsByStorage.getOrDefault(
                    sell.storageId().asString(), -1L));
        }
        PacketDistributor.sendToServer(new StorageSellPacket(
                sessionId, UUID.randomUUID().toString(), entries, revisions));
        // [NEW] 会话 #21-H 修订：学习模式卖完刷新目录（新卖过物品立即可买回）
        refreshCatalogIfLearning();
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
        // [CHANGED] 会话 #21-C：一键出售模式选择弹窗（模态，z=400）。点击选项即按该模式
        // 出售并写回配置；点「不再提示」勾选；点面板外关闭。打开时吃掉一切点击。
        if (showSellWholePopup) {
            if (button == 0 && layout.sellWholeAll().contains(localX, localY)) {
                showSellWholePopup = false;
                PokeTradeConfig.setSellWholeMode(PokeTradeConfig.SellWholeMode.ALL);
                if (sellWholeDontAsk) {
                    PokeTradeConfig.setSellWholeConfirm(false);
                }
                runSellWhole(PokeTradeConfig.SellWholeMode.ALL);
            } else if (button == 0 && layout.sellWholeExpanded().contains(localX, localY)) {
                showSellWholePopup = false;
                PokeTradeConfig.setSellWholeMode(PokeTradeConfig.SellWholeMode.EXPANDED);
                if (sellWholeDontAsk) {
                    PokeTradeConfig.setSellWholeConfirm(false);
                }
                runSellWhole(PokeTradeConfig.SellWholeMode.EXPANDED);
            } else if (button == 0 && layout.sellWholeDontAsk().contains(localX, localY)) {
                sellWholeDontAsk = !sellWholeDontAsk;
            } else if (button == 0 && !layout.sellWholeModal().contains(localX, localY)) {
                // 面板外点击 → 关闭（不执行）
                showSellWholePopup = false;
            }
            return true;
        }
        // [NEW] 会话 #21-H 修订：仓储分类选择弹窗（模态，z=400）。点击可视区内分类行即选中并
        // 关闭；点面板外关闭。打开时吃掉一切点击，避免误触下层按钮。
        if (showCategoryModal) {
            if (button == 0) {
                ExchangeUiModel.Rect modal = layout.categoryModal();
                int rowY = modal.y() + 16;
                int row = (localY - rowY) / CATEGORY_ROW_H;
                if (localX >= modal.x() && localX <= modal.right()
                        && localY >= rowY && localY < rowY + CATEGORY_VISIBLE_ROWS * CATEGORY_ROW_H
                        && row >= 0) {
                    int target = categoryScroll + row;
                    if (target == 0) {
                        slotCategoryIndex = -1; // 「全部」
                    } else if (target - 1 < categories.size()) {
                        slotCategoryIndex = target - 1;
                    }
                    showCategoryModal = false;
                } else if (!modal.contains(localX, localY)) {
                    showCategoryModal = false;
                }
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
        // [CHANGED] 会话 #21-B：操作说明按钮（左栏范围输入框上方、与展开按钮同 Y）点击切换
        // [CHANGED] 会话 #21-C：开帮助面板时顺带关闭一键出售弹窗（两模态互斥）
        if (button == 0 && !leftCollapsed && layout.helpButton().contains(localX, localY)) {
            showHelp = !showHelp;
            showSellWholePopup = false;
            return true;
        }
        // 帮助面板打开时点击面板外 → 关闭（吃掉点击，避免误触下层按钮）
        if (showHelp && button == 0 && !layout.helpModal().contains(localX, localY)) {
            showHelp = false;
            return true;
        }
        // [CHANGED] 仓储扫描范围：点击切换档位（翻倍，最大 648 后重置 16）
        if (button == 0 && layout.radiusInput().contains(localX, localY)) {
            cycleStorageRadius();
            return true;
        }
        // [CHANGED] 会话 #16 组 4（任务 C）：一键出售(整箱全部) —— 无待售确认进行中才可点击
        // [CHANGED] 会话 #21-C：点击弹出模式选择弹窗（全部/展开）。配置「不再提示」
        // （sellWholeConfirm=false）时跳过弹窗，直接按默认模式执行。
        if (button == 0 && !workflow.pending()
                && layout.sellWhole().contains(localX, localY)) {
            if (PokeTradeConfig.sellWholeConfirm()) {
                showSellWholePopup = true;
                showHelp = false; // 两模态互斥
                sellWholeMode = PokeTradeConfig.sellWholeMode();
                sellWholeDontAsk = false;
            } else {
                runSellWhole(PokeTradeConfig.sellWholeMode());
            }
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
            // [CHANGED] 会话 #15-B：拿起后 200ms 内的同一手势松开不回存——
            // 否则 LAN/单机同 tick 回包 setCarried 后，mouseReleased 会把刚拿起的物品又存回去。
            // [FIXED] 会话 #15-C：判定收敛到 ExchangeUiModel.immediateRedepositSuppressed，
            // 修掉 Long.MIN_VALUE 哨兵下溢导致的「拖入仓储格子全部无效」。
            if (ExchangeUiModel.immediateRedepositSuppressed(
                    pendingPickupAt, System.currentTimeMillis())) {
                return true;
            }
            // [CHANGED] Bug 2：存入模型改为「指定格子 + 组合键」——
            // 左键拖到格=整叠存该格（空格/同类可合并）；Shift=只存 1 个；Ctrl=自动找槽（旧行为兜底）；
            // 异类格/表头/空白拒绝并提示，不再静默自动排列。
            AccordionEntry entry = accordionEntryAt(lx, ly);
            StorageId dropStorage = entry != null ? entry.descriptor().storageId() : null;
            if (entry != null && entry.grid() != null && entry.grid().contains(lx, ly)) {
                if (hasControlDown()) {
                    depositCarriedTo(dropStorage, -1, entry.descriptor().displayName(),
                            carried.getCount());
                    return true;
                }
                int gi = accordionSlotIndexAt(entry, lx, ly);
                if (gi < 0 || gi >= entry.descriptor().slotCount()) {
                    sellMessage = t("poketrade.exchange.deposit.occupied");
                    sellMessageColor = PeStyle.TEXT_ERROR;
                    return true;
                }
                StorageItemSlot target = snapshotsByStorage.get(dropStorage.asString()) == null
                        ? null
                        : snapshotsByStorage.get(dropStorage.asString()).slots().get(gi);
                if (target != null && !canDepositInto(target, carried)) {
                    sellMessage = t("poketrade.exchange.deposit.occupied");
                    sellMessageColor = PeStyle.TEXT_ERROR;
                    return true;
                }
                depositCarriedTo(dropStorage, gi, null,
                        hasShiftDown() ? 1 : carried.getCount());
                return true;
            }
            if (entry != null) {
                // 落在表头（名称/箭头区）：Ctrl 自动找槽，否则提示拖到具体格子
                if (hasControlDown()) {
                    depositCarriedTo(dropStorage, -1, entry.descriptor().displayName(),
                            carried.getCount());
                    return true;
                }
                sellMessage = t("poketrade.exchange.deposit.hint");
                sellMessageColor = PeStyle.TEXT_WARN;
                return true;
            }
            // 落在左栏列表空白处（非条目）：提示拖到具体格子，避免静默回存背包
            if (layout.left().contains(lx, ly)) {
                sellMessage = t("poketrade.exchange.deposit.hint");
                sellMessageColor = PeStyle.TEXT_WARN;
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
        // [CHANGED] 会话 #19：携带栈球类按 encode 球种匹配仓储槽位 itemId（同为 encode 键），
        // 大师球可并入大师球槽位而非被注册表键 pixelmon:poke_ball 误判为普通球不匹配。
        String carriedId = PokeballIdentity.encode(carried);
        if (carriedId == null) {
            return false;
        }
        if (!targetId.equals(carriedId)) {
            return false;
        }
        return target.count() + carried.getCount() <= carried.getMaxStackSize();
    }

    /** 把鼠标上的物品存入指定仓储（slotIndex=-1 表示服务端自动找槽位；count 为本次存入数量）。 */
    private void depositCarriedTo(StorageId storageId, int targetSlot, String transferName, int count) {
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
                -1L, Math.max(1, Math.min(count, menu.getCarried().getCount()))));
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
        // [CHANGED] 会话 #21-D：购物车「批量出售」只预览/出售玩家背包物品（语义固定），
        // 不再因待售队列残留而误结算仓储待售——此前 cartSell 检查 sellQueue 非空即
        // submitStorageSell，而 cancelPreview 不清 sellQueue：一键出售取消后残留仓储待售，
        // 再点批量出售会误把仓储物品也卖出去（玩家反馈的特定条件 bug，见会话 #21-D）。
        if (layout.cartSell().contains(x, y) && !workflow.pending()) {
            if (sellEnabled) {
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
        // [CHANGED] 会话 #21-H 修订：左栏：物品分类按钮 → 弹出分类选择弹窗（原循环切换；
        // 弹窗列出「全部」+ 各分类，补齐 lang 键后显示中文，点击直接选中）。
        if (!leftCollapsed && layout.slotCategory().contains(x, y)) {
            // 打开时滚动定位到当前选中行（第 0 行 = 「全部」），使当前筛选立即可见
            int totalRows = categories.size() + 1;
            categoryScroll = Math.max(0, Math.min(slotCategoryIndex + 1,
                    Math.max(0, totalRows - CATEGORY_VISIBLE_ROWS)));
            showCategoryModal = true;
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
        // [CHANGED] 会话 #21-F Bug 3：原「清空待售」按钮改为一键展开/一键收起。
        // 清空待售能力由预览取消路径（cancelPreview）保留，按钮让位给列表折叠导航。
        if (!leftCollapsed && layout.storageClear().contains(x, y)) {
            toggleAllExpanded();
            return true;
        }
        if (!leftCollapsed && layout.storageDeposit().contains(x, y) && !workflow.pending()) {
            depositAllToStorage();
            return true;
        }
        // [CHANGED] 会话 #21-E：排序按钮 —— 循环切换仓储列表排序档
        if (!leftCollapsed && layout.storageSort().contains(x, y)) {
            cycleStorageSort();
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
        // [CHANGED] 会话 #21-E：刷新选中箱 + 全部展开箱快照（此前仅选中箱）
        refreshExpandedSnapshots();
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
            // [CHANGED] 会话 #21-F Bug 1：target_blocked → 明确「该格已有其他物品」，
            // 不再一律笼统的「存入失败」；并记录 code 便于诊断
            PokeEMC.LOGGER.warn("[storage-diag] client deposit failed code={} msg={}",
                    response.code(), response.message());
            sellMessage = t("target_blocked".equals(response.code())
                    ? "poketrade.exchange.deposit.failed.blocked"
                    : "poketrade.exchange.deposit.failed");
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
        // [CHANGED] 会话 #15-A：整体滚动条点击（列表右缘外的列间隙 141..143，
        // 不落在任何条目上，必须在 accordionEntryAt 之前判定）。按相对 y 换算 target。
        int accordionSbX = layout.left().right() + 1;
        if (x >= accordionSbX && x <= accordionSbX + 2
                && y >= layout.listTop() && y < ACCORDION_BOTTOM_LIMIT) {
            int total = storage.visibleStorages().size();
            int visible = accordionEntries().size();
            int maxOffset = Math.max(0, total - visible);
            if (maxOffset > 0) {
                int relY = y - layout.listTop();
                int trackH = ACCORDION_BOTTOM_LIMIT - layout.listTop();
                int target = Math.round((float) relY / trackH * maxOffset);
                accordionScroll = Math.max(0, Math.min(target, maxOffset));
                return;
            }
        }
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
            persistExpandedStorages();
            return;
        }
        // [REMOVED] 会话 #21-F Bug 2：移除每格小滑条（原双箱第 8 排滚动点击跳页）。
        // 展开箱现显示全部行，由手风琴大滑条导航，无需每格滚动条。
        // 网格槽位：先确保该仓储被选中，再执行操作
        StorageId selected = storage.getSelectedStorageId();
        boolean justSelected = selected == null || !selected.equals(id);
        if (justSelected) {
            selectStorageById(id);
        }
        StorageItemSlot slot = accordionSlotAt(entry, x, y);
        if (slot == null) {
            return;
        }
        // [CHANGED] 会话 #21-F Bug 1：刚选中该箱时缓存快照可能过期（指纹/版本与服务端
        // 不符，直接发操作会被拒），把基于指纹的操作挂起，等 onSnapshotResponse 用新
        // 快照重放（replayPendingSlotAction）。拖放存入不校验指纹，松开时直接用当前格。
        if (justSelected) {
            if (!menu.getCarried().isEmpty()) {
                return; // 松开时执行存入（deposit 不依赖缓存指纹）
            }
            PendingSlotOp op = storageWithdrawShift() ? PendingSlotOp.WITHDRAW
                    : (button == 1 ? PendingSlotOp.CONTEXT_MENU : PendingSlotOp.PICKUP);
            pendingSlotAction = new PendingSlotAction(id, slot.slotIndex(), op, x, y);
            sellMessage = t("poketrade.exchange.sell.storage.loading");
            sellMessageColor = PeStyle.TEXT_DIM;
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

    /**
     * [CHANGED] 会话 #21-F Bug 1：新快照到达后重放挂起的槽位操作。此时选中箱就是该箱，
     * 快照已套用（指纹/版本最新），用新数据执行，不再被服务端以「状态已变化」拒绝。
     */
    private void replayPendingSlotAction(StorageId storageId) {
        PendingSlotAction pending = pendingSlotAction;
        if (pending == null || !pending.storageId.equals(storageId)) {
            return;
        }
        pendingSlotAction = null;
        StorageSnapshot snap = snapshotsByStorage.get(storageId.asString());
        if (snap == null) {
            return;
        }
        StorageItemSlot slot = snap.slots().get(pending.slotIndex);
        if (slot == null || !matchesItemFilter(slot)) {
            sellMessage = t("poketrade.exchange.withdraw.failed.empty");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        switch (pending.op) {
            case PICKUP -> pickUpFromStorage(slot);
            case WITHDRAW -> withdrawFromStorage(slot);
            case CONTEXT_MENU -> contextMenu = new ContextMenu(pending.x, pending.y, slot, storageId);
        }
    }

    // [CHANGED] 会话 #21-F Bug 2：移除 gridScroll——展开箱显示全部行，无每格滚动。
    private record AccordionEntry(StorageDescriptor descriptor, ExchangeUiModel.Rect header,
                                  ExchangeUiModel.Rect grid) {
        boolean expanded() {
            return grid != null;
        }
    }

    /** 手风琴条目几何（按滚动偏移跳过前面的仓储；展开网格按剩余高度裁剪）。 */
    private List<AccordionEntry> accordionEntries() {
        List<AccordionEntry> out = new ArrayList<>();
        List<StorageDescriptor> visible = storage.visibleStorages();
        int y = layout.listTop();
        int bottomLimit = ACCORDION_BOTTOM_LIMIT;
        int start = Math.max(0, accordionScroll);
        for (int i = start; i < visible.size() && y < bottomLimit; i++) {
            StorageDescriptor d = visible.get(i);
            int headerH = 12;
            ExchangeUiModel.Rect header = new ExchangeUiModel.Rect(
                    layout.left().x(), y, layout.left().width(), headerH);
            ExchangeUiModel.Rect grid = null;
            if (expandedStorages.contains(d.storageId().asString())
                    && y + headerH < bottomLimit) {
                // [CHANGED] 会话 #11：网格高度按容器容量 slotCount 计算（单箱 4 行、双箱 8 行）。
                // 之前按快照「已占用槽数」算，空/半空箱子只显示 1 行（问题 2）。
                // [CHANGED] 会话 #21-F Bug 2：移除每格小滑条后网格高度=容器全部行
                // （按剩余可用高度裁剪），隐藏行由手风琴大滑条滚动访问（不再嵌套滚动）。
                int maxRows = Math.max(1, (bottomLimit - (y + headerH)) / SLOT);
                int rows = Math.min(accordionGridRows(d), maxRows);
                grid = new ExchangeUiModel.Rect(layout.left().x() + 2, y + headerH,
                        snapshotCols * SLOT, rows * SLOT);
            }
            out.add(new AccordionEntry(d, header, grid));
            y += headerH + (grid == null ? 0 : grid.height());
        }
        return out;
    }

    /** 展开网格可见行数：按容器容量 slotCount 计算并裁剪到面板高度上限（问题 2 修复）。 */
    // [CHANGED] 会话 #21-F Bug 2：移除每格小滑条——网格高度=容器全部行（单箱 4、双箱 8），
    // 不再裁剪到 MAX_ACCORDION_ROWS；越界行由手风琴大滑条滚动访问。
    private int accordionGridRows(StorageDescriptor d) {
        return ExchangeUiModel.accordionContentRows(d.slotCount(), snapshotCols);
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

    /** 展开网格点击位置对应的槽位（含滚动偏移与物品过滤；空格返回 null）。 */
    private StorageItemSlot accordionSlotAt(AccordionEntry entry, int x, int y) {
        int gi = accordionSlotIndexAt(entry, x, y);
        if (gi < 0) {
            return null;
        }
        StorageSnapshot snap = snapshotsByStorage.get(entry.descriptor().storageId().asString());
        if (snap == null) {
            return null;
        }
        StorageItemSlot slot = snap.slots().get(gi);
        // [CHANGED] Bug 2：网格按存储槽号寻址。保留过滤语义：被搜索/分类过滤掉的槽位
        // 画成空、也不可点击（与旧的压缩列表行为一致）。
        return slot != null && matchesItemFilter(slot) ? slot : null;
    }

    /** [CHANGED] 会话 #21-F Bug 2：移除每格滚动后网格=存储槽号直接寻址（无 scroll 偏移）。 */
    private int accordionSlotIndexAt(AccordionEntry entry, int x, int y) {
        if (entry.grid() == null || !entry.grid().contains(x, y)) {
            return -1;
        }
        int col = (x - entry.grid().x()) / SLOT;
        int row = (y - entry.grid().y()) / SLOT;
        int visibleRows = entry.grid().height() / SLOT;
        if (col < 0 || col >= snapshotCols || row < 0 || row >= visibleRows) {
            return -1;
        }
        return row * snapshotCols + col;
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
        pendingPickupAt = System.currentTimeMillis();
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
        // [CHANGED] Bug 1：球类 itemId 含 '#'，ResourceLocation.parse 会抛异常；
        // 改经身份解码还原带组件的样本栈（大师球只与大师球合并，不再当精灵球处理）。
        ItemStack sample = null;
        try {
            sample = PokeballIdentity.decode(slot.itemId(), 1);
        } catch (RuntimeException ignored) {
            // 物品 id 无法解析时按不可取出处理
        }
        if (sample == null || sample.isEmpty()) {
            sellMessage = t("poketrade.exchange.withdraw.invalid");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        Inventory inv = this.minecraft.player.getInventory();
        int maxStack = Math.max(1, sample.getMaxStackSize());
        int target = -1;
        int count = Math.min(slot.count(), maxStack);
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
        // [CHANGED] 会话 #21-F Bug 1：任何移动（取出/拿起）后刷新选中箱 + 全部展开箱快照
        //（此前仅刷新选中箱），防止非选中展开箱缓存快照过期（指纹/版本过期）导致
        // 下次操作被服务端拒——反馈的「第 3 个箱子无法取出/贩卖/放置」即由此累积。
        refreshExpandedSnapshots();
        if (pendingPickup) {
            pendingPickup = false;
            if (response.success()) {
                sellMessage = t("poketrade.exchange.pickup.done");
                sellMessageColor = PeStyle.TEXT_OK;
            } else {
                // [CHANGED] 会话 #21-F Bug 1 诊断：客户端记录移动失败 code（与服务端
                // [storage-diag] 对应，便于核对是哪一环节拒绝）
                PokeEMC.LOGGER.warn("[storage-diag] client pickup failed code={} msg={}",
                        response.code(), response.message());
                sellMessage = t(moveFailureKey("poketrade.exchange.pickup.failed", response.code()));
                sellMessageColor = PeStyle.TEXT_ERROR;
            }
            return;
        }
        if (response.success()) {
            sellMessage = t("poketrade.exchange.withdraw.done");
            sellMessageColor = PeStyle.TEXT_OK;
        } else {
            PokeEMC.LOGGER.warn("[storage-diag] client withdraw failed code={} msg={}",
                    response.code(), response.message());
            sellMessage = t(moveFailureKey("poketrade.exchange.withdraw.failed", response.code()));
            sellMessageColor = PeStyle.TEXT_ERROR;
        }
    }

    /**
     * [CHANGED] 会话 #21-F Bug 1：把服务端移动失败 code 映射为具体文案（替代一律
     * 「状态已变化」）。content_changed/revision_conflict 提示已自动刷新、请重试；
     * 其余按语义细分，便于玩家与后续排障定位。未知 code 回落 base 键。
     */
    private static String moveFailureKey(String base, String code) {
        return switch (code) {
            case "content_changed" -> base + ".changed";
            case "source_empty" -> base + ".empty";
            case "revision_conflict" -> base + ".revision";
            case "permission_denied" -> base + ".permission";
            default -> base;
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int x = toLocalX(mouseX), y = toLocalY(mouseY);
        // [NEW] 会话 #21-H 修订：分类弹窗内滚轮滚动列表（含「全部」共 categories+1 项）
        if (showCategoryModal) {
            categoryScroll = ExchangeUiModel.clampScroll(
                    categoryScroll - (int) deltaY,
                    categories.size() + 1, CATEGORY_VISIBLE_ROWS);
            return true;
        }
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
            // [CHANGED] Bug 6：cartScroll 行式钳制（可见 4 行，≤28 种自动钳到 0 = 不滚动）
            cartScroll = ExchangeUiModel.clampScroll(
                    cartScroll - (int) deltaY,
                    ExchangeUiModel.accordionContentRows(this.cart.size(), this.cartCols),
                    ExchangeUiModel.Layout.CART_ROWS);
            return true;
        }
        if (!leftCollapsed) {
            AccordionEntry over = accordionEntryAt(x, y);
            // [REMOVED] 会话 #21-F Bug 2：移除每格网格滚动（storageScrolls）。
            // 指针落在任意条目（表头或展开网格）上时统一滚动手风琴整体列表。
            if (over != null) {
                // 手风琴条目滚动
                accordionScroll = ExchangeUiModel.clampScroll(
                        accordionScroll + (deltaY > 0 ? -1 : 1),
                        storage.visibleStorages().size(), 1);
                return true;
            }
            // [CHANGED] 会话 #15-A：指针落在列表区空白处（不在任何条目/网格上）时也滚动整体列表
            if (y < ACCORDION_BOTTOM_LIMIT
                    && x >= layout.left().x() && x <= layout.left().right()) {
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
        // [CHANGED] 会话 #21-C：ESC 依次关闭一键出售弹窗 / 帮助面板（模态优先）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && showSellWholePopup) {
            showSellWholePopup = false;
            return true;
        }
        // [NEW] 会话 #21-H 修订：ESC 关闭分类选择弹窗
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && showCategoryModal) {
            showCategoryModal = false;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && showHelp) {
            showHelp = false;
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
            // [CHANGED] 会话 #21-E：出售成功后刷新选中箱 + 全部展开箱快照
            //（此前仅选中箱，非选中展开箱含末影箱残留旧槽位）。
            refreshExpandedSnapshots();
        } else if (action == ExchangeUiModel.ResultAction.KEEP_DRAFT) {
            awaitingSnapshotClear = false;
        }
        if (operation == ExchangeUiModel.Operation.STORAGE_SELL && lastResult != TradeResult.SUCCESS) {
            // [CHANGED] 会话 #21-F Bug 1 诊断：记录仓储出售失败的具体原因
            PokeEMC.LOGGER.warn("[storage-diag] client storage_sell failed reason={} code={}",
                    menu.getResultReason(), menu.getResultCode());
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
            // [CHANGED] Bug 1：批量出售(附近箱子) 失败码 → 明确文案
            case NOTHING_TO_SELL -> "poketrade.exchange.result.storage_sell.nothing_to_sell";
            case TOO_MANY -> "poketrade.exchange.result.storage_sell.too_many";
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
            // [CHANGED] 会话 #21-E：刷新选中箱 + 全部展开箱快照（此前仅选中箱，
            // 末影箱等非选中展开箱残留旧物品）。
            refreshExpandedSnapshots();
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
        // [CHANGED] 会话 #21-B：操作说明帮助面板（helpButton 点击切换；同样 z=400 提层）
        if (showHelp) {
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F);
            renderHelpModal(g);
            g.pose().popPose();
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        }
        // [CHANGED] 会话 #21-C：一键出售模式选择弹窗（sellWhole 点击；同样 z=400 提层）
        if (showSellWholePopup) {
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F);
            renderSellWholePopup(g, lmx, lmy);
            g.pose().popPose();
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        }
        // [NEW] 会话 #21-H 修订：仓储分类选择弹窗（slotCategory 点击；同样 z=400 提层）
        if (showCategoryModal) {
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F);
            renderCategoryModal(g, lmx, lmy);
            g.pose().popPose();
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        }
        // [CHANGED] 会话 #15-D：文字重放移回缩放矩阵内，随 uiScale 同步缩放（与
        // StorageBrowserScreen 现有做法一致）；0.75/0.5 档轻微模糊为已接受取舍。
        // MAIN 文字在 z=0 重放：在弹窗区域（z=400 深度≈0.48）被 LEQUAL cull，
        // 主界面文字不透出弹窗，与矩阵内行为一致。
        drawPendingText(g, TextLayer.MAIN);
        // TOP 文字（弹窗/右键菜单/帮助面板）以 z=400 + disableDepthTest 重放，晚画覆盖弹窗背景。
        if (sellPreview != null || contextMenu != null || showHelp
                || showSellWholePopup || showCategoryModal) {
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 400.0F);
            drawPendingText(g, TextLayer.TOP);
            g.pose().popPose();
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        }
        endScaledRender(g);

        // [CHANGED] Bug D 修复：基类 AbstractContainerScreen.render 不调用 renderTooltip，
        // 容器子类必须显式调用，否则背包/仓储物品悬停提示不显示。
        // [CHANGED] 会话 #15-D：tooltip 也画回矩阵内（局部坐标 lmx/lmy），随缩放；
        // 方法内命中逻辑直接用局部坐标（调用方已换算，结果与现状恒等）。
        // [CHANGED] 会话 #20-B：右键菜单打开且鼠标悬停菜单矩形时，抑制基类 hoveredSlot
        // tooltip——菜单矩形可能覆盖玩家背包槽位（menu.slots），基类 renderTooltip 会与
        // 右键菜单 tooltip（z=400 内的 renderTooltip）在同一鼠标位置叠加渲染，表现为
        // 「两段文字叠印、单层背景、中文乱码重叠尾部正常」（菜单 tooltip 长句延伸到右端）。
        // [CHANGED] 会话 #20 补丁 8：移除 [DBG-TOOLTIP] 诊断日志（bug 已根治，抑制逻辑保留）——
        // 右键菜单打开且鼠标在菜单矩形内时跳过基类 hoveredSlot tooltip，避免与右键菜单
        // tooltip 在同一坐标叠加渲染。
        if (contextMenu == null || !contextMenuRect().contains(lmx, lmy)) {
            this.renderTooltip(g, lmx, lmy);
        }
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
            // [CHANGED] 会话 #21-H 修订：目录为空时按模式分流提示——学习模式下目录可能
            // 因「尚未出售任何物品」而空，给出可操作的引导（先卖一件）；否则走通用无匹配。
            String key = "LEARNING".equals(this.catalogMode)
                    ? "poketrade.exchange.catalog.empty.learning"
                    : "poketrade.exchange.search.none";
            String none = this.font.plainSubstrByWidth(
                    t(key),
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
            // [CHANGED] Bug 6：可见格 = cartCols × 可见行（28 格，非容量 54）；渲染前行式钳制，
            // 避免 cart 尺寸缩水后 cartScroll 越界。idx 仍按行滚偏移取购物车第 N 种物品。
            int cartVisibleRows = layout.cartGrid().height() / SLOT;
            int cartTotalRows = ExchangeUiModel.accordionContentRows(cart.size(), cartCols);
            int renderCartScroll = Math.max(0, Math.min(cartScroll,
                    Math.max(0, cartTotalRows - cartVisibleRows)));
            for (int i = 0; i < cartVisibleRows * cartCols; i++) {
                int idx = i + renderCartScroll * cartCols;
                int gx = layout.cartGrid().x() + (i % cartCols) * SLOT;
                int gy = layout.cartGrid().y() + (i / cartCols) * SLOT;
                PeStyle.slot(g, gx, gy);
                if (idx >= 0 && idx < cart.size()) {
                    if (idx == selectedCart) {
                        g.fill(gx - 1, gy - 1, gx + 17, gy + 17, 0x338B6B1B);
                    }
                    // [CHANGED] 会话 #14：球类 itemId 含 '#'，tryParse 返回 null →
                    // 球类购物车格无图标。改 decode 还原带组件栈。
                    ItemStack s = PokeballIdentity.decode(cart.get(idx).itemId(), 1);
                    if (s != null && !s.isEmpty()) {
                        g.renderItem(s, gx + 1, gy + 1);
                        g.renderItemDecorations(this.font, s, gx + 1, gy + 1);
                    }
                }
            }
            // [CHANGED] Bug 6：行式滚动条（网格右缘外侧）。≤可见行(4 行/28 格)时
            // PeStyle.scrollbar 自动画平轨 = 向下兼容无滑条。
            PeStyle.scrollbar(g, layout.cartGrid().right() + 1, layout.cartGrid().y(),
                    layout.cartGrid().height(), cartTotalRows, cartVisibleRows, renderCartScroll);
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

    /** [NEW] 会话 #21-H：目录模式 → 短名（modeText 左上指示器）。未知模式回退原串。 */
    private static String modeShortLabel(String mode) {
        return "LEARNING".equals(mode) ? t("poketrade.exchange.mode.learning")
                : "FULL".equals(mode) ? t("poketrade.exchange.mode.full")
                : mode == null ? "" : mode;
    }

    /** [NEW] 会话 #21-H：目录模式 → tooltip 翻译键（未知模式回退 unknown 说明，带 %s 原值）。 */
    private static String modeTipKey(String mode) {
        return "LEARNING".equals(mode) ? "poketrade.exchange.mode.learning.tip"
                : "FULL".equals(mode) ? "poketrade.exchange.mode.full.tip"
                : "poketrade.exchange.mode.unknown.tip";
    }

    /** [CHANGED] Bug F：目录分类现为可翻译键（itemGroup.* / 模组 tab 键），按当前语言本地化；
     *  非翻译键（模组 literal 名）translatable 无语言键时 fallback 显示原样；unknown 保持原样。
     *  [CHANGED] 会话 #21-C：真实作用 = 物品所属 creative tab 的可翻译键。部分分类键在语言文件
     *  无对应条目时，getString() 回退返回 key 本身（如 itemGroup.someModTab），此时按
     *  prettyCategoryKey 美化显示，避免按钮/下拉露出原始键值。 */
    private static Component categoryLabel(String category) {
        if (category == null || category.isEmpty() || "unknown".equals(category)) {
            return Component.literal(category == null ? "unknown" : category);
        }
        Component c = Component.translatable(category);
        String localized = c.getString();
        if (localized.equals(category)) {
            return Component.literal(prettyCategoryKey(category));
        }
        return c;
    }

    /** 无语言键的分类键美化：去命名空间前缀（itemGroup. 等），'_' 与 '.' 转空格，单词首字母大写。 */
    private static String prettyCategoryKey(String key) {
        String s = key;
        int dot = s.indexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '_' || ch == '.') {
                sb.append(' ');
                cap = true;
            } else if (cap && Character.isLetter(ch)) {
                sb.append(Character.toUpperCase(ch));
                cap = false;
            } else {
                sb.append(ch);
                cap = false;
            }
        }
        return sb.toString().trim();
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
        // [NEW] 会话 #21-H：中栏左上目录模式指示器（学习/全高亮）+ 悬停说明（过滤语义在服务端）。
        ExchangeUiModel.Rect modeRect = layout.modeText();
        recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(
                        modeShortLabel(this.catalogMode), Math.max(8, modeRect.width() - 2)),
                modeRect.x(), modeRect.y(), PeStyle.TEXT_DIM);
        if (modeRect.contains(x, y)) {
            g.renderTooltip(this.font, List.of(Component.translatable(
                            modeTipKey(this.catalogMode), this.catalogMode)),
                    java.util.Optional.empty(), this.leftPos + x, this.topPos + y);
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
        // 中栏钱包：大额缩写（1k/1m/1b），悬停显示完整千分位金额（避免大数撑出区域截断/穿模）
        long bal = menu.getBalance();
        String balStr = t("poketrade.exchange.balance") + " " + ExchangeUiModel.formatWallet(bal);
        recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(balStr,
                        Math.max(16, layout.wallet().width() - 2)),
                layout.wallet().x(), layout.wallet().y(),
                bal > 0 ? PeStyle.TEXT_OK : PeStyle.TEXT_DIM);
        // [CHANGED] 会话 #25：钱包悬停 tooltip 显示完整千分位金额
        if (layout.wallet().contains(x, y)) {
            g.renderTooltip(this.font, List.of(
                            Component.translatable("poketrade.exchange.balance"),
                            Component.literal(ExchangeUiModel.formatAmount(bal) + " PKM")),
                    java.util.Optional.empty(), this.leftPos + x, this.topPos + y);
        }
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
            // [CHANGED] Bug 5：统计区重排。容量行独立；「总件数：N」与「总价 M」合并为
            // 单行（原三行 y+76/87/98 上下叠压拥挤）——总件数左对齐、总价右对齐紧贴栏右缘，
            // 总价过长时按剩余宽度截断兜底（不吞总件数）。cartTotal 行不再渲染。
            int rw = Math.max(16, layout.cartCapacity().width() - 2);
            String capStr = t("poketrade.exchange.cart.capacity",
                    cart.size(), ExchangeUiModel.Layout.CART_CAPACITY);
            recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(capStr, rw),
                    layout.cartCapacity().x(), layout.cartCapacity().y(), PeStyle.TEXT_DIM);
            String itemsStr = t("poketrade.exchange.cart.items", cart.totalItems());
            String totalStr = t("poketrade.exchange.cart.total") + " "
                    + ExchangeUiModel.formatAmount(cartTotalCost());
            int totalW = this.font.width(totalStr);
            int totalX = layout.cartItems().right() - Math.min(totalW, rw);
            int itemsW = Math.max(16, totalX - 2 - layout.cartItems().x());
            recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(itemsStr, itemsW),
                    layout.cartItems().x(), layout.cartItems().y(), PeStyle.TEXT_DIM);
            recordText(TextLayer.MAIN, this.font.plainSubstrByWidth(totalStr, rw),
                    totalX, layout.cartItems().y(), PeStyle.TEXT_TITLE);
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
        // [CHANGED] Bug 4：标题常显于钱包行下方（applyLayout 已定位 titleLabelY）；
        // 不再受左栏收起影响（此前左栏收起即消失，且位于左栏顶部与搜索框抢位）。
        String tLabel = this.font.plainSubstrByWidth(this.title.getString(),
                Math.max(16, this.layout.width() - 10));
        recordText(TextLayer.MAIN, tLabel,
                this.titleLabelX, this.titleLabelY, 0x404040, false);
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
     * [CHANGED] 会话 #21-B：操作说明帮助面板（helpButton 切换）。背景在 z=400 内直接绘制，
     * 文字经 recordText(TOP) 于矩阵内 z=400 重放（renderHelpModal 在 z=400 段内调用，TOP 重放
     * 条件已含 showHelp）。每行按面板宽截断，长说明不越界。
     */
    private void renderHelpModal(GuiGraphics g) {
        ExchangeUiModel.Rect modal = layout.helpModal();
        g.fill(modal.x(), modal.y(), modal.right(), modal.bottom(), 0xFFF1E5C8);
        PeStyle.windowFrame(g, modal.x(), modal.y(), modal.width(), modal.height());
        recordText(TextLayer.TOP, t("poketrade.exchange.help.title"),
                modal.x() + 6, modal.y() + 5, PeStyle.TEXT_TITLE);
        int textW = Math.max(16, modal.width() - 12);
        int lineY = modal.y() + 17;
        for (String line : t("poketrade.exchange.help.body").split("\n")) {
            recordText(TextLayer.TOP, this.font.plainSubstrByWidth(line, textW),
                    modal.x() + 6, lineY, PeStyle.TEXT);
            lineY += 11;
        }
    }

    /**
     * [CHANGED] 会话 #21-C：一键出售模式选择弹窗（sellWhole 点击）。背景/按钮在 z=400 内绘制，
     * 文字经 recordText(TOP) 于矩阵内 z=400 重放（TOP 重放条件已含 showSellWholePopup）。
     * 左右两个选项：全部/展开，当前配置默认项高亮（pressed=true）；悬停选项时以 tooltip
     * 标注两者差异；底部「不再提示」勾选框 + 关闭按钮。
     */
    private void renderSellWholePopup(GuiGraphics g, int mx, int my) {
        ExchangeUiModel.Rect modal = layout.sellWholeModal();
        g.fill(modal.x(), modal.y(), modal.right(), modal.bottom(), 0xFFF1E5C8);
        PeStyle.windowFrame(g, modal.x(), modal.y(), modal.width(), modal.height());
        int textW = Math.max(24, modal.width() - 12);
        recordText(TextLayer.TOP,
                this.font.plainSubstrByWidth(t("poketrade.exchange.sell.whole.modal.title"), textW),
                modal.x() + 6, modal.y() + 5, PeStyle.TEXT_TITLE);
        // 两个选项按钮（左=全部，右=展开）；当前默认作用域 pressed=true 高亮
        boolean allPressed = sellWholeMode == PokeTradeConfig.SellWholeMode.ALL;
        boolean expandedPressed = sellWholeMode == PokeTradeConfig.SellWholeMode.EXPANDED;
        ExchangeUiModel.Rect allRect = layout.sellWholeAll();
        ExchangeUiModel.Rect expandedRect = layout.sellWholeExpanded();
        PeStyle.buttonBg(g, allRect.x(), allRect.y(), allRect.width(), allRect.height(),
                true, allPressed, allRect.contains(mx, my), this.leftPos + mx, this.topPos + my);
        recordButton(TextLayer.TOP, this.font.plainSubstrByWidth(
                        t("poketrade.exchange.sell.whole.all"), Math.max(8, allRect.width() - 4)),
                true, allRect.x(), allRect.y(), allRect.width(), allRect.height());
        PeStyle.buttonBg(g, expandedRect.x(), expandedRect.y(), expandedRect.width(), expandedRect.height(),
                true, expandedPressed, expandedRect.contains(mx, my), this.leftPos + mx, this.topPos + my);
        recordButton(TextLayer.TOP, this.font.plainSubstrByWidth(
                        t("poketrade.exchange.sell.whole.expanded"), Math.max(8, expandedRect.width() - 4)),
                true, expandedRect.x(), expandedRect.y(), expandedRect.width(), expandedRect.height());
        // 「不再提示」勾选框 + 关闭按钮
        ExchangeUiModel.Rect dontAskRect = layout.sellWholeDontAsk();
        g.fill(dontAskRect.x(), dontAskRect.y(), dontAskRect.x() + 10, dontAskRect.y() + 10,
                sellWholeDontAsk ? 0xFFB8860B : 0xFFE0E0E0);
        if (sellWholeDontAsk) {
            g.drawString(this.font, "✓", dontAskRect.x() + 2, dontAskRect.y(), 0xFFFFFFFF);
        }
        recordText(TextLayer.TOP, this.font.plainSubstrByWidth(
                        t("poketrade.exchange.sell.whole.dont.ask"), Math.max(8, dontAskRect.width() - 14)),
                dontAskRect.x() + 14, dontAskRect.y() + 1, PeStyle.TEXT);
        ExchangeUiModel.Rect closeRect = layout.sellWholeClose();
        recordButton(TextLayer.TOP, t("poketrade.exchange.sell.whole.close"),
                true, closeRect.x(), closeRect.y(), closeRect.width(), closeRect.height());
        // 悬停选项时用 tooltip 标注差异（模态内 renderTooltip 可直接调用，List 单行形式同全文件）
        if (allRect.contains(mx, my)) {
            g.renderTooltip(this.font,
                    List.of(Component.translatable("poketrade.exchange.sell.whole.all.tip")),
                    java.util.Optional.empty(), mx, my);
        } else if (expandedRect.contains(mx, my)) {
            g.renderTooltip(this.font,
                    List.of(Component.translatable("poketrade.exchange.sell.whole.expanded.tip")),
                    java.util.Optional.empty(), mx, my);
        }
    }

    /**
     * [NEW] 会话 #21-H 修订：仓储分类选择弹窗（slotCategory 点击）。背景/文字在 z=400 内绘制，
     * 文字经 recordText(TOP) 于矩阵内 z=400 重放（TOP 重放条件已含 showCategoryModal）。
     * 列出「全部」+ 目录各分类（经 categoryLabel 本地化，补齐 lang 键后显示中文），
     * 点击即选中并关闭；滚轮滚动超出可视 9 行的列表。
     */
    private void renderCategoryModal(GuiGraphics g, int mx, int my) {
        ExchangeUiModel.Rect modal = layout.categoryModal();
        g.fill(modal.x(), modal.y(), modal.right(), modal.bottom(), 0xFFF1E5C8);
        PeStyle.windowFrame(g, modal.x(), modal.y(), modal.width(), modal.height());
        recordText(TextLayer.TOP,
                this.font.plainSubstrByWidth(t("poketrade.exchange.category.modal.title"),
                        Math.max(24, modal.width() - 12)),
                modal.x() + 6, modal.y() + 5, PeStyle.TEXT_TITLE);
        int rowY = modal.y() + 16;
        int textW = Math.max(24, modal.width() - 12);
        for (int r = 0; r < CATEGORY_VISIBLE_ROWS; r++) {
            int target = categoryScroll + r;
            String label;
            boolean selected;
            if (target == 0) {
                label = t("poketrade.exchange.category.all");
                selected = slotCategoryIndex < 0;
            } else if (target - 1 < categories.size()) {
                label = categoryLabel(categories.get(target - 1)).getString();
                selected = slotCategoryIndex == target - 1;
            } else {
                break; // 已超出实际条目数
            }
            PeStyle.segmentedBg(g, modal.x() + 4, rowY, modal.width() - 8, CATEGORY_ROW_H,
                    selected, mx >= modal.x() + 4 && mx <= modal.right() - 4
                            && my >= rowY && my < rowY + CATEGORY_ROW_H,
                    mx, my);
            recordText(TextLayer.TOP,
                    this.font.plainSubstrByWidth(label, textW),
                    modal.x() + 6, rowY + 2, selected ? PeStyle.TEXT_PKM : PeStyle.TEXT);
            rowY += CATEGORY_ROW_H;
        }
        // 右侧滚动条（含「全部」共 categories+1 行；无超出时自动画平轨）
        PeStyle.scrollbar(g, modal.right() - 3, modal.y() + 16,
                CATEGORY_VISIBLE_ROWS * CATEGORY_ROW_H,
                categories.size() + 1, CATEGORY_VISIBLE_ROWS, categoryScroll);
    }

    /**
     * 出售预览模态标签/按钮——[CHANGED] 会话 #12：背景几何在矩阵内 z=400 绘制，
     * 文字全部改 recordText(TOP)，在 endScaledRender 后以屏幕空间整数坐标 + z=400 重放。
     */
    private void renderSellPreviewLabels(GuiGraphics g, int mouseX, int mouseY) {
        ExchangeUiModel.Rect modal = layout.previewModal();
        ExchangeUiModel.Rect lines = layout.previewLines();
        // [CHANGED] 会话 #21-D：x/y 供下方取消/确认按钮 hovered 判定使用（既有逻辑，保持不动）；
        // 本函数新增的条目悬停命中与来源 tooltip 改用布局局部坐标 mouseX/mouseY
        // （leftPos 为屏幕居中偏移，仅适用于未缩放矩阵，条目行命中不可沿用）。
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
            // [CHANGED] 会话 #21-E：不再显示原始 storageId 键值（minecraft:overworld|…|坐标）
            // 与单箱 revision——改为预览物品的真实来源箱名列表（含标记号，跨箱子多来源）。
            List<String> sources = new ArrayList<>();
            for (List<String> names : buildPreviewItemSources().values()) {
                for (String n : names) {
                    if (!sources.contains(n)) {
                        sources.add(n);
                    }
                }
            }
            if (sources.isEmpty() && storagePreview.storageName() != null
                    && !storagePreview.storageName().isEmpty()) {
                sources.add(storagePreview.storageName());
            }
            String info = t("poketrade.exchange.sell.preview.storage_info",
                    String.join("、", sources));
            recordText(TextLayer.TOP, this.font.plainSubstrByWidth(info, textWidth),
                    modal.x() + 6, modal.y() + 26, PeStyle.TEXT_DIM);
        }
        // [CHANGED] 会话 #12 问题 A：可滚动条目列表——价格右对齐到弹窗右缘，名称整行动态截断。
        // 旧实现价格左对齐固定 x=modal.right()-24，千分位多位时溢出右边界；名称截断又不含
        // ×数量 后缀。新实现：subtotal 完整千分位（仅超可用宽度才截断兜底），priceX 右锚定
        // （几何来自 ExchangeUiModel.previewRowLayout 纯函数，便于测试）；名称整行
        // （名称 ×数量）截断到 priceX-6，两者永不重叠，短价格时名称可延展更宽。
        int pageStart = previewPage * ExchangeUiModel.Layout.PREVIEW_ROWS;
        // [CHANGED] 会话 #21-D：悬停行 tooltip（#21）+ 条目名跑马灯（#22）+ 右侧滑条（#22）。
        int hoveredLine = -1;
        for (int i = 0; i < ExchangeUiModel.Layout.PREVIEW_ROWS; i++) {
            int idx = pageStart + i;
            if (idx >= sellPreview.lines().size()) break;
            ExchangeUiModel.PreviewLine line = sellPreview.lines().get(idx);
            String subtotal = this.font.plainSubstrByWidth(
                    ExchangeUiModel.formatAmount(line.subtotal()),
                    Math.max(24, modal.right() - 24 - lines.x()));
            ExchangeUiModel.PreviewRowLayout rowLayout =
                    ExchangeUiModel.previewRowLayout(modal, lines, this.font.width(subtotal));
            int rowY = lines.y() + i * 11;
            // 悬停行高亮（背景淡色，指示可点击单条出售）。
            // [CHANGED] 会话 #21-D：命中用布局局部坐标 mouseX/mouseY（=调用方 lmx/lmy）——
            // 函数开头 x=mouseX-leftPos 是历史死代码（leftPos 为屏幕居中偏移，在 uiScale
            // 矩阵内重复减去会导致悬停错位），此处与 drawContextMenuTooltip 同源。
            if (mouseX >= lines.x() && mouseX < lines.right()
                    && mouseY >= rowY && mouseY < rowY + 11) {
                hoveredLine = idx;
                g.fill(lines.x(), rowY, lines.right(), rowY + 11, 0x20FFFFFF);
            }
            String lineTextFull = t("poketrade.exchange.sell.preview.line",
                    line.displayName(), line.count());
            // 名称超宽 → 跑马灯横向滚动（与仓储箱子名同款 marqueeX），不再截断；
            // 不超宽保持静态截断（短名静止，符合「名称过长时增加滚动」）。
            if (this.font.width(lineTextFull) > rowLayout.nameMax()) {
                // [CHANGED] 会话 #24c：marqueeX 改「头追尾」传送带——循环绘制所有与
                // 可见区相交的副本（间距 marqueePeriod），文字流连续无空档；价格在下方
                // recordText(TOP) 后记录、z 顺序在后，滚到价格左缘的文字被价格盖住。
                // [CHANGED] 会话 #24b：speedMs 40→60，与表头跑马灯同步减速。
                int gap = 24;
                int priceX = rowLayout.priceX() - 4;
                int period = ExchangeUiModel.marqueePeriod(this.font.width(lineTextFull), gap);
                int mx = ExchangeUiModel.marqueeX(System.currentTimeMillis(), 60,
                        this.font.width(lineTextFull), gap, lines.x(), priceX);
                for (int xx = mx; xx < priceX; xx += period) {
                    if (xx + this.font.width(lineTextFull) > lines.x()) {
                        recordText(TextLayer.TOP, lineTextFull, xx, rowY, PeStyle.TEXT_DIM);
                    }
                }
            } else {
                recordText(TextLayer.TOP, this.font.plainSubstrByWidth(lineTextFull, rowLayout.nameMax()),
                        lines.x(), rowY, PeStyle.TEXT_DIM);
            }
            recordText(TextLayer.TOP, subtotal,
                    rowLayout.priceX(), rowY, PeStyle.TEXT_TITLE);
        }
        // 向下兼容滑条：条目数 > 可见行时右侧指示可滚动（≤ PREVIEW_ROWS 时自动平轨）
        if (sellPreview.lines().size() > ExchangeUiModel.Layout.PREVIEW_ROWS) {
            PeStyle.scrollbar(g, lines.right() + 1, lines.y(), lines.height(),
                    sellPreview.lines().size(), ExchangeUiModel.Layout.PREVIEW_ROWS,
                    pageStart);
        }
        // 悬停条目 tooltip：行1=物品名×数量；行2=单价·小计；行3=来源（仓储明确标注来源仓储列表）
        if (hoveredLine >= 0) {
            ExchangeUiModel.PreviewLine line = sellPreview.lines().get(hoveredLine);
            List<Component> tipLines = new ArrayList<>();
            tipLines.add(Component.literal(line.displayName() + " ×" + line.count()));
            tipLines.add(Component.literal(t("poketrade.exchange.sell.preview.tip.price",
                    ExchangeUiModel.formatAmount(line.unitPrice()),
                    ExchangeUiModel.formatAmount(line.subtotal()))));
            if (sellPreview.source() == ExchangeUiModel.SellSource.STORAGE) {
                // [CHANGED] 会话 #21-E：逐条精确来源——该物品聚合自哪些箱子（含标记号）。
                // 多箱子（末影箱+箱子）时逐一列出，不再统一显示单一选中箱。
                List<String> names = buildPreviewItemSources().getOrDefault(line.itemId(), List.of());
                String src = names.isEmpty()
                        ? (storagePreview != null && storagePreview.storageName() != null
                                ? storagePreview.storageName() : "?")
                        : String.join("、", names);
                tipLines.add(Component.literal(t("poketrade.exchange.sell.preview.tip.storage", src)));
            } else {
                tipLines.add(Component.literal(t("poketrade.exchange.sell.preview.tip.inventory")));
            }
            g.renderTooltip(this.font, tipLines, java.util.Optional.empty(), mouseX, mouseY);
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
        int w = 118;
        int h = CONTEXT_MENU_ITEMS * 12; // 7 项 × 12px（会话 #16：批量操作扩到 7 项）
        int x = Math.max(2, Math.min(contextMenu.x(), layout.width() - w - 2));
        int y = Math.max(2, Math.min(contextMenu.y(), layout.height() - h - 2));
        return new ExchangeUiModel.Rect(x, y, w, h);
    }

    /** 右键菜单项数（[CHANGED] 会话 #21-D：移除「拿起」「加入/移出待售」两项——
     *  玩家反馈这两个操作已被批量操作/一键出售取代、且容易与批量出售冲突。安全边界：
     *  pickUpFromStorage 仍被左键点击、withdrawFromStorage 仍被右 Shift 取出使用，
     *  底层方法必须保留，仅从菜单隐藏。剩余：0 取出到背包 / 1 批量取出同类 /
     *  2 批量出售同类(整箱) / 3 批量出售同类(附近箱子) / 4 一键出售(整箱全部)）。 */
    private static final int CONTEXT_MENU_ITEMS = 5;

    /** 右键菜单项：标签 lang 键 + 悬停 tooltip lang 键（null = 无 tooltip）。 */
    private static final String[][] CONTEXT_MENU_ENTRIES = {
            {"poketrade.exchange.withdraw.to_inventory", null},
            {"poketrade.exchange.batch.withdraw", "poketrade.exchange.batch.withdraw.tip"},
            {"poketrade.exchange.batch.sell.storage", "poketrade.exchange.batch.sell.storage.tip"},
            {"poketrade.exchange.batch.sell.nearby", "poketrade.exchange.batch.sell.nearby.tip"},
            {"poketrade.exchange.batch.sell.whole", "poketrade.exchange.batch.sell.whole.tip"}
    };

    /** 右键菜单：仓储槽位操作（拿起 / 取出到背包 / 待售 / 4 项批量操作 + 悬停 tooltip）。 */
    private void renderContextMenu(GuiGraphics g, int mx, int my) {
        if (contextMenu == null) {
            return;
        }
        ExchangeUiModel.Rect rect = contextMenuRect();
        g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), 0xF0E8E0C8);
        PeStyle.windowFrame(g, rect.x(), rect.y(), rect.width(), rect.height());
        // [CHANGED] 会话 #20 补丁 8：悬停命中改为「行级」判定 + 循环外只画一次 tooltip。
        // 补丁 7 的 rect.contains(mx, my) 与行号 i 无关：鼠标一进菜单矩形即 7 行全部
        // hovered=true，i=3..6 四个带 tip 的行各调一次 drawContextMenuTooltip 在同一坐标
        // 叠加（「叠印字墙」根因未除）。现按真实 y 落在本行 [rowY, rowY+12) 才命中，
        // 并先收集 hoveredIndex，循环结束再统一画一次 tooltip，杜绝同帧多画。
        int hoveredIndex = -1;
        for (int i = 0; i < CONTEXT_MENU_ITEMS; i++) {
            int rowY = rect.y() + i * 12;
            boolean hovered = mx >= rect.x() && mx < rect.right()
                    && my >= rowY && my < rowY + 12;
            if (hovered) {
                hoveredIndex = i;
                g.fill(rect.x() + 1, rowY, rect.right() - 1, rowY + 12, 0x408B6B1B);
            }
            recordText(TextLayer.TOP, t(CONTEXT_MENU_ENTRIES[i][0]),
                    rect.x() + 4, rowY + 2, PeStyle.TEXT);
        }
        // 悬停 tooltip：循环外每帧至多调用一次 renderTooltip（悬停行带 tip 时才画）
        if (hoveredIndex >= 0 && CONTEXT_MENU_ENTRIES[hoveredIndex][1] != null) {
            drawContextMenuTooltip(g, t(CONTEXT_MENU_ENTRIES[hoveredIndex][1]), mx, my);
        }
    }

    /**
     * 右键菜单悬停 tooltip：改用原版 {@link GuiGraphics#renderTooltip} 渲染。
     * [CHANGED] 会话 #19/#20：自绘管线（fill + recordText→drawPendingText→drawString）
     * 在 CJK 字形下反复出现「中文乱码重叠、尾部正常」，两次修复（换行 wrap / 去二次截断）
     * 均未根治；而本项目仓储/目录/购物车悬停全部走原版 renderTooltip 且中文显示正常。
     * 故改回原版标准多行 tooltip 管线（对任意文本稳健、自动避边与缩进、随矩阵缩放）。
     * 多行仍由 {@link #wrapText} 按完整码点拆行，绝不切半个字符。
     */
    private void drawContextMenuTooltip(GuiGraphics g, String text, int mx, int my) {
        int maxW = Math.max(40, layout.width() - 40) - 8;
        List<Component> lines = new ArrayList<>();
        for (String line : wrapText(text, maxW)) {
            lines.add(Component.literal(line));
        }
        if (!lines.isEmpty()) {
            g.renderTooltip(this.font, lines, java.util.Optional.empty(), mx, my);
        }
    }

    /** 按像素宽度把文本拆成多行（换行符视为分隔；宽字符按 font 实际宽度计）。 */
    private List<String> wrapText(String text, int maxPx) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || maxPx <= 0) {
            return text == null || text.isEmpty() ? lines : List.of(text);
        }
        for (String seg : text.split("\n", -1)) {
            if (this.font.width(seg) <= maxPx) {
                lines.add(seg);
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < seg.length(); ) {
                int cp = seg.codePointAt(i);
                int charLen = Character.charCount(cp);
                String ch = seg.substring(i, i + charLen);
                if (this.font.width(line.toString() + ch) > maxPx) {
                    if (line.length() > 0) {
                        lines.add(line.toString());
                        line.setLength(0);
                        continue;
                    }
                }
                line.append(ch);
                i += charLen;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
            }
        }
        return lines;
    }

    private void runContextOption(ContextMenu menu, int option) {
        selectStorageById(menu.storageId());
        switch (option) {
            // [CHANGED] 会话 #21-D：菜单移除「拿起」(旧 case 0) 与「加入/移出待售」(旧 case 2)；
            // pickUpFromStorage 仍由左键点击、withdrawFromStorage 仍由右 Shift 取出调用，
            // 仅菜单不再暴露这两项。case 号顺移：
            case 0 -> withdrawFromStorage(menu.slot());
            case 1 -> withdrawAllFromStorage(menu.slot().itemId());
            case 2 -> batchSellItemFromStorage(menu.slot().itemId());
            case 3 -> batchSellItemNearby(menu.slot().itemId());
            // [CHANGED] 会话 #21-C：右键菜单「一键出售全部」复用弹窗逻辑（弹窗/直售同出口）。
            case 4 -> runSellWhole(PokeTradeConfig.sellWholeMode());
            default -> {
            }
        }
    }

    // ================= 批量操作（会话 #16，任务 B） =================

    /** 批量取出同类(整箱)：把当前仓储中与 itemId 同类的槽位全部取出合并到背包。 */
    private void withdrawAllFromStorage(String itemId) {
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
        if (workflow.pending()) {
            return;
        }
        PacketDistributor.sendToServer(new StorageBatchPacket(
                sessionId, UUID.randomUUID().toString(),
                StorageBatchPacket.Action.WITHDRAW_ALL, StorageBatchPacket.Scope.CURRENT,
                selected, itemId, 0, null));
        sellMessage = t("poketrade.exchange.batch.withdraw.pending");
        sellMessageColor = PeStyle.TEXT_DIM;
    }

    /** 批量出售同类(整箱)：本地快照筛同 itemId 槽位灌入待售队列 → 现有确认流程。 */
    private void batchSellItemFromStorage(String itemId) {
        StorageId selected = storage.getSelectedStorageId();
        if (selected == null || !storage.hasPermission(StoragePermission.SELL)) {
            sellMessage = t("poketrade.exchange.sell.blocked");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (storage.getSelectedSnapshotRevision() < 0) {
            sellMessage = t("poketrade.exchange.sell.storage.loading");
            sellMessageColor = PeStyle.TEXT_WARN;
            return;
        }
        sellQueue.clear();
        StorageId sid = storage.getSelectedStorageId();
        for (StorageItemSlot slot : storage.visibleSlots().values()) {
            if (slot.itemId().equals(itemId) && slot.count() > 0) {
                sellQueue.put(pendingSellKey(sid, slot.slotIndex()), new PendingSell(
                        sid, slot.slotIndex(), slot.itemId(), slot.count(), slot.fingerprint()));
            }
        }
        submitStorageSell();
    }

    /** 批量出售同类(附近箱子)：服务端半径扫描 + 直接出售（无本地预览，贵重项由服务端校验）。 */
    private void batchSellItemNearby(String itemId) {
        if (!sellEnabled) {
            sellMessage = t("poketrade.exchange.sell.disabled");
            sellMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (workflow.pending()) {
            return;
        }
        // [CHANGED] Bug 1：此前漏调 workflow.begin —— 成功回执被 Workflow.complete 判为
        // IGNORE（无成功提示/无仓储刷新），「正在出售附近同类物品…」永久卡住 = 「无响应」。
        // 与 sendStorageSell 的 STORAGE_SELL 契约保持一致。
        if (!workflow.begin(ExchangeUiModel.Operation.STORAGE_SELL, menu.getResultNonce())) {
            return;
        }
        // [CHANGED] 会话 #21-D：携带「本箱」（当前选中/右键的仓储）storageId——
        // 服务端 NEARBY 扫描对末影箱（virtual 无坐标）直接跳过、且本箱可能超出
        // 玩家坐标半径，导致「本箱物品不会被卖出去」。服务端据此显式追加本箱同类可售槽位。
        PacketDistributor.sendToServer(new StorageBatchPacket(
                sessionId, UUID.randomUUID().toString(),
                StorageBatchPacket.Action.SELL_ITEM, StorageBatchPacket.Scope.NEARBY,
                storage.getSelectedStorageId(), itemId, storage.getRadius(), null));
        sellMessage = t("poketrade.exchange.batch.sell.nearby.pending");
        sellMessageColor = PeStyle.TEXT_DIM;
    }

    /**
     * 一键出售：按 {@link SellWholeMode} 选取目标箱子串联出售其全部可售物品。
     * [CHANGED] 会话 #19：此前只卖当前选中箱子；现按玩家要求检测箱子列表状态。
     * [CHANGED] 会话 #21-C：原 sellWholeStorage() 重构为 runSellWhole(mode)——
     * EXPANDED=仅当前展开且 SELL 权限、快照已加载的箱子（等价旧行为）；ALL=全部
     * 可见箱子中可售者（含收起态）。sendStorageSell 按 PendingSell.storageId 多箱子
     * 聚合，revisions 逐箱校验（任务 C 按钮与右键菜单共用）。
     */
    private void runSellWhole(PokeTradeConfig.SellWholeMode mode) {
        if (workflow.pending()) {
            return;
        }
        List<StorageDescriptor> targets = new ArrayList<>();
        for (StorageDescriptor d : storage.visibleStorages()) {
            if (mode == PokeTradeConfig.SellWholeMode.ALL) {
                // ALL：所有 SELL 权限且快照已加载的箱子（无论展开与否）
                if (storage.allowsOn(d.storageId(), StoragePermission.SELL)
                        && snapshotsByStorage.get(d.storageId().asString()) != null) {
                    targets.add(d);
                }
            } else if (expandedStorages.contains(d.storageId().asString())
                    && storage.allowsOn(d.storageId(), StoragePermission.SELL)
                    && snapshotsByStorage.get(d.storageId().asString()) != null) {
                // EXPANDED：仅展开态（等价旧 sellWholeStorage 行为）
                targets.add(d);
            }
        }
        if (targets.isEmpty()) {
            sellMessage = t("poketrade.exchange.batch.sell.whole.empty");
            sellMessageColor = PeStyle.TEXT_WARN;
            return;
        }
        sellQueue.clear();
        sellPreview = null;
        storagePreview = null;
        previewConfirmed = false;
        for (StorageDescriptor d : targets) {
            StorageId sid = d.storageId();
            StorageSnapshot snap = snapshotsByStorage.get(sid.asString());
            if (snap == null) {
                continue;
            }
            for (StorageItemSlot slot : snap.slots().values()) {
                if (slot.itemId() != null && slot.count() > 0) {
                    sellQueue.put(pendingSellKey(sid, slot.slotIndex()), new PendingSell(
                            sid, slot.slotIndex(), slot.itemId(), slot.count(), slot.fingerprint()));
                }
            }
        }
        submitStorageSell();
    }

    /** 待售条目复合键：跨箱子唯一。 */
    private static String pendingSellKey(StorageId storageId, int slotIndex) {
        return storageId.asString() + "#" + slotIndex;
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
        // [CHANGED] 会话 #15-D：矩阵内传入局部坐标 lmx/lmy（render() 已换算）；
        // 命中与绘制都直接用局部坐标，随矩阵缩放。
        int x = mouseX, y = mouseY;
        if (sellPreview != null) {
            // 弹窗打开时禁止下层目录/购物车/仓储的悬停提示，避免提示浮在弹窗之上
            return;
        }
        // [CHANGED] 会话 #19：右键菜单唤起时屏蔽仓储/目录/购物车悬停信息
        //（「屏蔽箱子指标信息」），避免指标 tooltip 与菜单重叠显示。
        if (contextMenu != null) {
            return;
        }
        // [CHANGED] 会话 #21-C：一键出售模式弹窗打开时同样屏蔽下层悬停信息（选项差异
        // tooltip 由 renderSellWholePopup 内自行渲染）。
        if (showSellWholePopup) {
            return;
        }
        // [NEW] 会话 #21-H 修订：分类选择弹窗打开时同样屏蔽下层悬停信息
        if (showCategoryModal) {
            return;
        }
        // 左栏：手风琴表头信息 / 展开网格槽位信息
        if (!leftCollapsed) {
            AccordionEntry entry = accordionEntryAt(x, y);
            if (entry != null) {
                if (entry.header().contains(x, y)) {
                    // [CHANGED] 会话 #21-G Bug 5/6：指针信息隔离——悬停表头右侧展开/收起
                    // 按钮区（right-15..right）时不显示仓储详情 tooltip，与按钮高亮互斥，
                    // 二者只能出现一个，避免长提示挡在按钮上方。
                    if (x >= entry.header().right() - 15) {
                        return;
                    }
                    StorageDescriptor d = entry.descriptor();
                    List<Component> lines = new ArrayList<>();
                    // [CHANGED] 会话 #21-E：表头 tooltip 与列表同名带同类型标记号
                    lines.add(Component.literal(displayNameWithMarker(d)));
                    lines.add(Component.translatable(
                            "poketrade.storage.type." + d.storageId().adapterType()));
                    lines.add(Component.translatable(d.claimed()
                                    ? "poketrade.storage.distance" : "poketrade.gui.unclaimed",
                            d.distance()));
                    String owner = d.ownerName() == null || d.ownerName().isBlank()
                            ? (d.ownerId() == null ? "-"
                                    : d.ownerId().toString().substring(0, 8) + "…")
                            : d.ownerName();
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
                    // [CHANGED] 会话 #21-C：移除操作说明行（snapshot.hint）——已有「操作说明」
                    // 帮助面板承载全部操作指引，tooltip 只留基本信息和价格，不再挡视野。
                    // [CHANGED] Bug 2：仓储槽位 tooltip 补价格行（目录有价显示买/卖价，无价提示暂无定价）
                    long buy = 0, sell = 0;
                    boolean priced = false;
                    for (ExchangeCatalogPacket.EntryWire e : catalog) {
                        if (e.itemId().equals(slot.itemId())) {
                            buy = e.buyPrice();
                            sell = e.sellPrice();
                            priced = true;
                            break;
                        }
                    }
                    if (priced) {
                        lines.add(Component.translatable("poketrade.exchange.buy")
                                .append(Component.literal(": " + ExchangeUiModel.formatAmount(buy))));
                        lines.add(Component.translatable("poketrade.exchange.sell")
                                .append(Component.literal(": " + ExchangeUiModel.formatAmount(sell))));
                    } else {
                        lines.add(Component.translatable("poketrade.exchange.storage.no.price"));
                    }
                    // [CHANGED] Bug G：仓储槽位 tooltip 同样带物品图标
                    g.renderTooltip(this.font, lines, java.util.Optional.empty(), s, mouseX, mouseY);
                    return;
                }
            }
        }
        // [CHANGED] 会话 #21-C：移除存入格操作说明 tooltip（sell.direct.hint 是纯操作指引，
        // 已有帮助面板承载；hover 存入格不再弹提示，落到下方基类 hoveredSlot 处理）。
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
                // [CHANGED] 会话 #14：球类 itemId 含 '#'，tryParse 返回 null → 球类购物车
                // tooltip 无图标/名称。改 decode 还原带组件栈。
                ItemStack s = PokeballIdentity.decode(line.itemId(), 1);
                if (s == null) {
                    s = ItemStack.EMPTY;
                }
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
        // [CHANGED] 会话 #21-B：操作说明按钮 —— 左栏范围输入框上方、与展开按钮同 Y（12），
        // 点击切换帮助面板（不再用指针旁大字提示挡视野）。开启时 selected 高亮。
        PeStyle.buttonBg(g, layout.helpButton().x(), layout.helpButton().y(),
                layout.helpButton().width(), layout.helpButton().height(),
                true, showHelp, layout.helpButton().contains(x, y),
                this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN, this.font.plainSubstrByWidth(
                        t("poketrade.exchange.help.button"),
                        Math.max(8, layout.helpButton().width() - 4)),
                true, layout.helpButton().x(), layout.helpButton().y(),
                layout.helpButton().width(), layout.helpButton().height());
        // 范围行：标签 + 点击切换按钮（显示当前档位；selected 高亮表示当前生效值）
        recordText(TextLayer.MAIN, t("poketrade.gui.range"),
                layout.left().x() + 2, layout.radiusInput().y() + 2, PeStyle.TEXT);
        ExchangeUiModel.Rect radiusCtrl = layout.radiusInput();
        PeStyle.segmentedBg(g, radiusCtrl.x(), radiusCtrl.y(),
                radiusCtrl.width(), radiusCtrl.height(),
                true, false, 0, 0);
        recordSegmented(TextLayer.MAIN, String.valueOf(storage.getRadius()), true,
                radiusCtrl.x(), radiusCtrl.y(), radiusCtrl.width(), radiusCtrl.height());
        // 一键出售(整箱全部) 按钮：右贴左栏右缘；选中仓储 + 有出售权限 + 无进行中操作才可用
        // [CHANGED] 会话 #16 组 4（任务 C）：新增 sellWhole 控件，复用 sellWholeStorage 确认流程
        ExchangeUiModel.Rect sellWholeCtrl = layout.sellWhole();
        boolean sellWholeEnabled = storage.getSelectedStorageId() != null
                && storage.hasPermission(StoragePermission.SELL)
                && !workflow.pending();
        boolean sellWholeHover = sellWholeCtrl.contains(x, y);
        PeStyle.buttonBg(g, sellWholeCtrl.x(), sellWholeCtrl.y(),
                sellWholeCtrl.width(), sellWholeCtrl.height(),
                sellWholeEnabled, false, sellWholeHover && sellWholeEnabled,
                this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN,
                this.font.plainSubstrByWidth(t("poketrade.exchange.sell.whole"),
                        Math.max(8, sellWholeCtrl.width() - 2)),
                sellWholeEnabled,
                sellWholeCtrl.x(), sellWholeCtrl.y(),
                sellWholeCtrl.width(), sellWholeCtrl.height());
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
        // [CHANGED] 会话 #15-A：scissor 裁剪到底部按钮线之上（纵深防御），任何越界的
        // 展开网格/悬停高亮都被裁掉；按钮绘制在循环之后、裁剪区之外保持可见。
        g.enableScissor(screenX(layout.left().x()), screenY(layout.listTop()),
                screenX(layout.left().right()), screenY(ACCORDION_BOTTOM_LIMIT));
        for (AccordionEntry entry : accordionEntries()) {
            renderAccordionEntry(g, entry, x, y);
        }
        g.disableScissor();
        // 整体滚动条（列表右缘外列间隙 141..143，不与每格滚动条 137..139 重叠；
        // 无需滚动时 PeStyle.scrollbar 自动画平轨，无害）
        PeStyle.scrollbar(g, layout.left().right() + 1, layout.listTop(),
                ACCORDION_BOTTOM_LIMIT - layout.listTop(),
                storage.visibleStorages().size(), accordionEntries().size(), accordionScroll);
        // 出售区按钮（刷新 / 清空待售 / 存入；批量出售已移到购物车）
        PeStyle.buttonBg(g, layout.storageRefresh().x(), layout.storageRefresh().y(),
                layout.storageRefresh().width(), layout.storageRefresh().height(),
                !workflow.pending(), false,
                layout.storageRefresh().contains(x, y), this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN, t("poketrade.exchange.refresh"), !workflow.pending(),
                layout.storageRefresh().x(), layout.storageRefresh().y(),
                layout.storageRefresh().width(), layout.storageRefresh().height());
        // [CHANGED] 会话 #21-F Bug 3：原「清空待售」改为一键展开/一键收起（常亮，无灰置条件）
        PeStyle.buttonBg(g, layout.storageClear().x(), layout.storageClear().y(),
                layout.storageClear().width(), layout.storageClear().height(),
                true, false,
                layout.storageClear().contains(x, y), this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN, expandCollapseLabel(), true,
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
        // [CHANGED] 会话 #21-D：一键存入按钮悬停说明——功能此前已存在（服务端自动找槽 +
        // 装满自动溢流到下一个可存仓储），但文案「存入」不达意，玩家误以为只能逐格手动存入。
        if (depositEnabled && layout.storageDeposit().contains(x, y)) {
            List<Component> depositTip = List.of(
                    Component.translatable("poketrade.exchange.deposit.tip"));
            g.renderTooltip(this.font, depositTip, java.util.Optional.empty(),
                    this.leftPos + x, this.topPos + y);
        }
        // [CHANGED] 会话 #21-E：排序按钮 —— 点击循环切换排序档（距离/放置时间升/降/标记正/倒序），
        // 悬停 tooltip 说明当前档位含义。
        ExchangeUiModel.Rect sortCtrl = layout.storageSort();
        boolean sortHover = sortCtrl.contains(x, y);
        PeStyle.buttonBg(g, sortCtrl.x(), sortCtrl.y(), sortCtrl.width(), sortCtrl.height(),
                true, false, sortHover, this.leftPos + x, this.topPos + y);
        recordButton(TextLayer.MAIN, this.font.plainSubstrByWidth(sortModeLabel(),
                        Math.max(8, sortCtrl.width() - 2)),
                true, sortCtrl.x(), sortCtrl.y(), sortCtrl.width(), sortCtrl.height());
        if (sortHover) {
            g.renderTooltip(this.font, List.of(Component.literal(sortModeTip())),
                    java.util.Optional.empty(), this.leftPos + x, this.topPos + y);
        }
    }

    private void renderAccordionEntry(GuiGraphics g, AccordionEntry entry, int x, int y) {
        StorageDescriptor d = entry.descriptor();
        ExchangeUiModel.Rect header = entry.header();
        boolean selected = d.storageId().equals(storage.getSelectedStorageId());
        if (selected) {
            g.fill(header.x() + 1, header.y(), header.right() - 1, header.bottom(), 0x408B6B1B);
        }
        // 只显示容器名称；末影箱带紫色「末」徽标
        // [CHANGED] 会话 #21-E：同类型容器按放置时间基准标 ①-⑳（末影箱排除），名字后缀标记号，
        // 便于玩家区分多个同名箱子（"Dev的箱子①" vs "Dev的箱子②"）。
        String name = displayNameWithMarker(d);
        boolean ender = "vanilla_ender_chest".equals(d.storageId().adapterType());
        int tx = header.x() + 2;
        if (ender) {
            // [CHANGED] 会话 #24c：徽标本体移到名称之后绘制（见下方），此处仅预留名称起点
            // 偏移（徽标 8px + 1px 间距），名称左端 leftEdge 右移不压徽标。
            tx += 10;
        }
        // [CHANGED] 会话 #24d：名称文字 scissor 收窄到 [tx, rightEdge]——左=名称起点、
        // 右=展开按钮左缘。滚动/静态名称两端在按钮与末影徽标区域外被硬裁，名称永不
        // 覆盖展开按钮（区域互斥，比后绘 z 轴更彻底）；与外层手风琴 scissor 叠加裁剪。
        // 徽标与按钮在名称 scissor 之外独立绘制，互不干扰。
        int availW = Math.max(16, header.width() - 20 - (tx - header.x()));
        int nameW = this.font.width(name);
        int rightEdge = header.right() - 15; // 展开按钮左缘 = 名称文字裁剪右边界
        // [CHANGED] 会话 #24e：enableScissor 是 (x1,y1,x2,y2) 右/底边界语义（非宽高）！
        // 此前误把 width/height 差值当 x2/y2，负宽/高 → Math.max(0,..) 裁成 0 → 名称整体不显示。
        // 现传右边界 screenX(rightEdge)、底边界 screenY(header.bottom()) 的绝对屏幕坐标（与外层同款）。
        g.enableScissor(screenX(tx), screenY(header.y()),
                screenX(rightEdge), screenY(header.bottom()));
        if (nameW > availW) {
            // [CHANGED] 会话 #24c：marqueeX 改为「头追尾」传送带——从基准副本起以
            // marqueePeriod 为间距循环绘制所有与可见区相交的副本：尾部滚出左缘时头部
            // 已从右缘进入，全程连续、无空档、无相位归零瞬移。speedMs 60（会话 #24b 调慢）。
            // [CHANGED] 会话 #21-G Bug 4：跑马灯名称内联绘制（z≈0）。
            int gap = 24;
            int period = ExchangeUiModel.marqueePeriod(nameW, gap);
            int mx = ExchangeUiModel.marqueeX(System.currentTimeMillis(), 60,
                    nameW, gap, tx, rightEdge);
            for (int xx = mx; xx < rightEdge; xx += period) {
                if (xx + nameW > tx) {
                    g.drawString(this.font, name, xx, header.y() + 2, PeStyle.TEXT_TITLE, true);
                }
            }
        } else {
            g.drawString(this.font, this.font.plainSubstrByWidth(name, availW),
                    tx, header.y() + 2, PeStyle.TEXT_TITLE, true);
        }
        g.disableScissor();
        // [CHANGED] 会话 #24d：末影箱「末」徽标绘制在名称 scissor 之外（[header.x(), tx]
        // 独立区域——名称文字被收窄 scissor 裁在 tx，永远到不了徽标区）。仍为内联绘制。
        if (ender) {
            int bx = header.x() + 2;
            g.fill(bx, header.y() + 2, bx + 8, header.y() + 10, 0xFF1A1A24);
            g.drawString(this.font, "末", bx + 1, header.y() + 2, 0xFFA98BD6, true);
        }
        // 展开/收起按钮（表头右侧）
        String arrow = entry.expanded() ? "▾" : "▸";
        PeStyle.buttonBg(g, header.right() - 15, header.y() + 1, 13, 10,
                true, false, header.contains(x, y) && x >= header.right() - 15,
                this.leftPos + x, this.topPos + y);
        // [CHANGED] 会话 #24：箭头改内联 g.drawString（z≈0、随表头 scissor 裁剪）。
        // 此前 recordButton(MAIN) 经 drawPendingText（disableScissor 之后）以 z=160 重放、
        // 不受手风琴裁剪——列表滚动时滚出裁剪区的箭头残留在屏上，与下方箱子的表头/
        // 展开按钮/网格叠印穿模（即用户所见“名称文字覆盖了旁边的展开”）。内联后与
        // 名称、按钮背景同层（z≈0）且顺序在后（背景→箭头 盖住 名称），被 scissor 正确裁掉。
        PeStyle.ButtonText bt = PeStyle.buttonText(this.font, arrow, true,
                header.right() - 15, header.y() + 1, 13, 10);
        g.drawString(this.font, bt.label(), bt.textX(), bt.textY(), bt.color(), true);
        if (!entry.expanded()) {
            return;
        }
        String key = d.storageId().asString();
        StorageSnapshot snap = snapshotsByStorage.get(key);
        List<StorageItemSlot> slots = snap == null ? List.of() : filteredSlots(snap);
        ExchangeUiModel.Rect grid = entry.grid();
        int cols = snapshotCols;
        int visibleRows = Math.max(1, grid.height() / SLOT);
        // [CHANGED] 会话 #21-F Bug 2：移除每格滚动（scroll 恒为 0）——展开箱显示全部行，
        // 越界行由手风琴大滑条滚动访问。
        // slots 仅用于空态文案判定，与槽位寻址渲染无关。
        // [CHANGED] Bug 2：网格改按存储槽号寻址（gi = 槽位号，空格渲染为空槽）。此前按
        // 「已占用槽位压缩列表」渲染（slots.get(index)），空格与存储槽位无一一对应，
        // 拖入空格只能回落服务端自动找槽（表现为自动排列）。槽号寻址后「拖到哪格存进哪格」。
        for (int row = 0; row < visibleRows; row++) {
            for (int col = 0; col < cols; col++) {
                int gi = row * cols + col;
                int sx = grid.x() + col * SLOT;
                int sy = grid.y() + row * SLOT;
                PeStyle.slot(g, sx, sy);
                if (snap != null && gi >= 0 && gi < d.slotCount()) {
                    StorageItemSlot slot = snap.slots().get(gi);
                    if (slot != null && matchesItemFilter(slot)) {
                        // [CHANGED] 会话 #19：待售高亮按当前渲染箱子复合键匹配（多箱子一键出售）。
                        PendingSell pending = sellQueue.get(pendingSellKey(d.storageId(), gi));
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
        }
        // [REMOVED] 会话 #21-F Bug 2：移除每格小滑条（PeStyle.scrollbar）——展开箱显示
        // 全部行，由手风琴大滑条导航。
        if (slots.isEmpty()) {
            // [CHANGED] 会话 #24：空态提示改内联绘制（z≈0、随 scissor 裁剪），与名称/箭头
            // 一致，不再经 z=160 的 drawPendingText 浮出裁剪区穿模。
            g.drawString(this.font, t("poketrade.gui.empty"),
                    grid.x() + 2, grid.y() + 8, PeStyle.TEXT_DIM);
        }
    }

    private ItemStack toStack(StorageItemSlot slot) {
        ItemStack stack = ItemStack.EMPTY;
        try {
            // [CHANGED] Bug 1：球类经身份解码还原球种组件（否则显示普通精灵球）。
            ItemStack s = PokeballIdentity.decode(slot.itemId(), slot.count());
            if (s != null) {
                stack = s;
            }
        } catch (RuntimeException ignored) {
            // 物品 id 无法解析时显示空
        }
        return stack;
    }
}
