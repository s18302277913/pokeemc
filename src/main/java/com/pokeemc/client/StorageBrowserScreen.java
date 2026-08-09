package com.pokeemc.client;

import com.pokeemc.exchange.ExchangeService;
import com.pokeemc.menu.StorageBrowserMenu;
import com.pokeemc.network.OpenStorageBrowserPacket;
import com.pokeemc.network.QueryStoragesPacket;
import com.pokeemc.network.StorageDepositPacket;
import com.pokeemc.network.StorageDepositCarriedPacket;
import com.pokeemc.network.StorageManagePacket;
import com.pokeemc.network.StorageMovePacket;
import com.pokeemc.network.StorageSellPacket;
import com.pokeemc.network.StorageSnapshotPacket;
import com.pokeemc.storage.StorageGrant;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.StoragePermissionSet;
import com.pokeemc.storage.StoragePrincipal;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageTemplate;
import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageEndpoint;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageQuery;
import com.poketrade.api.storage.StorageSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 仓储浏览器屏幕（Task 9/10）。
 *
 * <p>三栏布局：</p>
 * <ul>
 *   <li><b>左列</b>：范围输入 + 预设按钮、搜索框、排序/过滤分段、仓储列表（滚动）、扫描状态；</li>
 *   <li><b>中央</b>：选中仓储的槽位视图（快照渲染，无 {@code VIEW} 时不显示内容）；</li>
 *   <li><b>右栏</b>：六项权限、模板 COPY/FOLLOW、自动化开关、重命名与审计摘要（无 {@code MANAGE}
 *       时隐藏私有 ACL 详情）。</li>
 * </ul>
 *
 * <p>出售区：点击中央槽位物品加入出售请求（虚拟视图，真实物品始终留在仓储），
 * 点「结算出售」把请求经 {@link StorageSellPacket} 发给服务端；结算成功后清空
 * 出售区并重新拉取快照。</p>
 *
 * <p>坐标约定（与 {@link TransmutationTableScreen} 一致）：{@code renderBg} 用全局坐标，
 * {@code renderLabels} 在 translate(leftPos, topPos) 内用局部坐标，{@code mouseClicked}
 * 用全局坐标。</p>
 */
public class StorageBrowserScreen
        extends AbstractContainerScreen<StorageBrowserMenu.Standalone>
        implements BrowserHost {

    private static final int BG_WIDTH = 330;
    private static final int BG_HEIGHT = 240;

    // 左列（仓储列表）
    private static final int LEFT_X = 6;
    private static final int LIST_TOP = 62;
    private static final int LIST_H = 168;
    private static final int LIST_ROW_H = 16;
    private static final int LIST_VISIBLE = 9;

    // 中央（槽位视图）
    private static final int CENTER_X = 112;
    private static final int CENTER_TOP = 30;
    private static final int SLOT_CELL = 18;
    private static final int SLOT_COLS = 8;

    // 右栏（管理）
    private static final int RIGHT_X = 222;
    private static final int RIGHT_W = 104;

    // 底部出售区
    private static final int SELL_TOP = 212;

    private final StorageViewModel viewModel = new StorageViewModel();
    private final String sessionId = UUID.randomUUID().toString().substring(0, 8);

    /** 自适应缩放：当前帧生效的缩放比例与缩放后界面左上角（GUI 逻辑像素）。 */
    private float uiScale = 1.0f;
    private float scaledOriginX;
    private float scaledOriginY;

    private EditBox searchBox;
    private EditBox radiusBox;
    private EditBox renameBox;
    private String manageMessage = "";
    private int manageMessageColor = PeStyle.TEXT_OK;

    /** 出售区：仓储槽位 -> 待出售条目。 */
    private final Map<Integer, PendingSell> sellQueue = new LinkedHashMap<>();

    /** 待出售条目（虚拟视图，仅记录仓储槽位与数量，不复制真实物品）。 */
    private record PendingSell(StorageId storageId, int slotIndex, String itemId,
                               int count, long fingerprint) {
    }

    /** 中央快照槽位按下待判定点击/拖拽的源槽位（松开时结算）。 */
    private StorageItemSlot dragSource;
    private int dragPressX;
    private int dragPressY;

    /** 最近一次收到的服务端管理数据（用于重新提交草稿/冲突展示）。 */
    private StorageManagePacket.Response lastManageResponse;

    /** 高风险管理确认：为 true 时「应用模板/提交权限」需二次点击。 */
    private boolean highRiskConfirm = false;

    public StorageBrowserScreen(StorageBrowserMenu.Standalone menu,
                                Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = BG_WIDTH;
        this.imageHeight = BG_HEIGHT;
        this.inventoryLabelY = BG_HEIGHT - 94;
        this.titleLabelX = 8;
        this.titleLabelY = 7;
    }

    // ================= 生命周期 =================

    @Override
    protected void init() {
        super.init();
        this.searchBox = new EditBox(this.font, LEFT_X + 10,
                20, 92, 12, Component.translatable("poketrade.gui.search"));
        this.searchBox.setMaxLength(64);
        this.searchBox.setBordered(false);
        this.searchBox.setTextColor(0xFF303030);
        this.searchBox.setResponder(s -> {
            viewModel.setSearchText(s == null ? "" : s);
            this.listOffset = 0;
        });
        this.addWidget(this.searchBox);

        this.radiusBox = new EditBox(this.font, LEFT_X + 10,
                42, 34, 12, Component.translatable("poketrade.gui.range"));
        this.radiusBox.setMaxLength(4);
        this.radiusBox.setBordered(false);
        this.radiusBox.setTextColor(0xFF303030);
        this.radiusBox.setValue(String.valueOf(viewModel.getRadius()));
        this.radiusBox.setResponder(s -> {
            try {
                viewModel.setRadius(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // 非法输入保持原值
            }
            this.listOffset = 0;
        });
        this.addWidget(this.radiusBox);

        this.renameBox = new EditBox(this.font, RIGHT_X + 10,
                142, RIGHT_W - 20, 12, Component.translatable("poketrade.gui.name"));
        this.renameBox.setMaxLength(32);
        this.renameBox.setBordered(false);
        this.renameBox.setTextColor(0xFF303030);
        this.addWidget(this.renameBox);

        // 打开即发起一次查询（服务端会校验菜单会话）
        requestQuery();
    }

    /** 屏幕坐标 -> 布局局部坐标（与当前缩放一致；1.0 时等价于减 leftPos/topPos）。 */
    private int toLocalX(double mouseX) {
        return (int) Math.floor((mouseX - scaledOriginX) / uiScale);
    }

    private int toLocalY(double mouseY) {
        return (int) Math.floor((mouseY - scaledOriginY) / uiScale);
    }

    /** 进入自适应缩放渲染（局部坐标 + 统一缩放矩阵；渲染期间 leftPos/topPos 置 0）。 */
    private void beginScaledRender(GuiGraphics g) {
        this.uiScale = UiScaling.fitScale(this.width, this.height, BG_WIDTH, BG_HEIGHT);
        this.scaledOriginX = (this.width - BG_WIDTH * this.uiScale) / 2f;
        this.scaledOriginY = (this.height - BG_HEIGHT * this.uiScale) / 2f;
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);
        beginScaledRender(g);
        int lmx = toLocalX(mouseX), lmy = toLocalY(mouseY);
        super.render(g, lmx, lmy, partialTick);
        if (searchBox != null) {
            searchBox.render(g, lmx, lmy, partialTick);
        }
        if (radiusBox != null) {
            radiusBox.render(g, lmx, lmy, partialTick);
        }
        if (renameBox != null) {
            renameBox.render(g, lmx, lmy, partialTick);
        }
        // [CHANGED] Bug D 修复：基类 render 不调用 renderTooltip，容器子类必须显式调用；
        // 缩放局部坐标与 beginScaledRender 矩阵、super.render 的 hoveredSlot 命中保持一致。
        this.renderTooltip(g, lmx, lmy);
        endScaledRender(g);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
        this.renderBg(g, partialTick, mx, my);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // 出售结果由转化桌屏幕（TransmutationTableScreen）轮询；独立浏览器无出售 DataSlot。
    }

    // ================= 网络请求 =================

    /** 发送仓储列表查询。 */
    private void requestQuery() {
        PacketDistributor.sendToServer(new QueryStoragesPacket(
                sessionId,
                viewModel.getRadius(),
                viewModel.getSearchText(),
                querySort(),
                queryFilter(),
                200));
    }

    /** 发送选中仓储的快照请求。 */
    private void requestSnapshot(StorageId storageId) {
        PacketDistributor.sendToServer(new StorageSnapshotPacket(sessionId, storageId));
    }

    /** 发送管理详情请求。 */
    private void requestDetails(StorageId storageId) {
        PacketDistributor.sendToServer(new StorageManagePacket(
                sessionId, storageId, -1L, StorageManagePacket.ManageAction.GET_DETAILS,
                Map.of(), null, null, null, null, null));
    }

    private StorageQuery.Sort querySort() {
        return switch (viewModel.getSortMode()) {
            case NAME -> StorageQuery.Sort.NAME;
            case FREE_SLOTS -> StorageQuery.Sort.FREE_SLOTS;
            case RECENTLY_UPDATED -> StorageQuery.Sort.RECENTLY_UPDATED;
            case DISTANCE -> StorageQuery.Sort.DISTANCE;
        };
    }

    private StorageQuery.Filter queryFilter() {
        return switch (viewModel.getFilterMode()) {
            case DEPOSIT -> StorageQuery.Filter.DEPOSITABLE;
            case WITHDRAW -> StorageQuery.Filter.WITHDRAWABLE;
            case SELL -> StorageQuery.Filter.SELLABLE;
            case BREAK -> StorageQuery.Filter.OWNED;
            case MANAGE -> StorageQuery.Filter.MANAGEABLE;
            case VIEW -> StorageQuery.Filter.VIEWABLE;
            case ALL -> StorageQuery.Filter.VIEWABLE;
        };
    }

    // ================= BrowserHost 回调 =================

    @Override
    public void onQueryResponse(QueryStoragesPacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        viewModel.setStorages(response.storages());
        viewModel.setPermissionsByStorage(response.permissions());
        viewModel.setScanComplete(response.storages().stream().allMatch(StorageDescriptor::scanComplete));
        listOffset = Math.min(listOffset,
                Math.max(0, viewModel.visibleStorages().size() - LIST_VISIBLE));
        if (viewModel.getSelectedStorageId() == null
                && !viewModel.visibleStorages().isEmpty()) {
            selectStorage(viewModel.visibleStorages().get(0));
        }
    }

    @Override
    public void onSnapshotResponse(StorageSnapshotPacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        Map<Integer, StorageItemSlot> slots = response.viewAllowed()
                ? response.slots() : Map.of();
        viewModel.applySnapshot(new StorageSnapshot(
                response.storageId(), response.revision(), slots));
        menu.setBrowsedStorage(response.storageId(),
                viewModel.getSelectedDescriptor(),
                viewModel.getSelectedSnapshot(),
                response.revision());
        menu.markSnapshotStale(false);
    }

    @Override
    public void onManageResponse(StorageManagePacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        lastManageResponse = response;
        // 管理响应只更新 MANAGE 位，绝不能覆盖查询阶段注入的 VIEW/SELL 等权限，
        // 否则打开浏览器后中央槽位会误判为“无查看权限”、出售区被禁用。
        EnumSet<StoragePermission> merged = EnumSet.noneOf(StoragePermission.class);
        for (StoragePermission p : StoragePermission.values()) {
            if (viewModel.hasPermission(p)) {
                merged.add(p);
            }
        }
        if (response.canManage()) {
            merged.add(StoragePermission.MANAGE);
        } else {
            merged.remove(StoragePermission.MANAGE);
        }
        viewModel.setMyPermissions(merged, viewModel.isOwner());
        viewModel.setGrants(response.grants());
        viewModel.setTemplates(response.templates());
        if (StorageManagePacket.CODE_REVISION_CONFLICT.equals(response.code())) {
            manageMessage = t("poketrade.gui.revision_conflict");
            manageMessageColor = PeStyle.TEXT_WARN;
        } else if (StorageManagePacket.CODE_PERMISSION_DENIED.equals(response.code())) {
            manageMessage = t("poketrade.gui.permission_denied");
            manageMessageColor = PeStyle.TEXT_ERROR;
        } else if (!response.code().isEmpty() && !StorageManagePacket.CODE_OK.equals(response.code())) {
            manageMessage = response.message().isEmpty() ? response.code() : response.message();
            manageMessageColor = PeStyle.TEXT_ERROR;
        } else {
            manageMessage = "";
        }
        // 同步自动化与名称到草稿
        if (renameBox != null && response.templateBinding() == null
                && response.revision() >= 0 && viewModel.getSelectedDescriptor() != null) {
            String name = viewModel.getSelectedDescriptor().displayName();
            renameBox.setValue(name == null ? "" : name);
        }
    }

    /** lang 翻译快捷方式（renderLabels 内 drawString 需要 String）。 */
    private static String t(String key) {
        return Component.translatable(key).getString();
    }

    /** lang 翻译快捷方式（带参数）。 */
    private static String t(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    // ================= 选中 =================

    private void selectStorage(StorageDescriptor descriptor) {
        viewModel.selectStorage(descriptor.storageId(), descriptor);
        menu.setBrowsedStorage(descriptor.storageId(), descriptor, null, -1L);
        menu.markSnapshotStale(true);
        highRiskConfirm = false;
        requestSnapshot(descriptor.storageId());
        requestDetails(descriptor.storageId());
    }

    // ================= 渲染 =================

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        PeStyle.runeBackground(graphics, x, y, BG_WIDTH, BG_HEIGHT);
        PeStyle.windowFrame(graphics, x, y, BG_WIDTH, BG_HEIGHT);
        // 左列
        PeStyle.inset(graphics, x + LEFT_X, y + 18, 100, 16, 0xFF9E9E9E);   // 搜索框凹槽
        PeStyle.inset(graphics, x + LEFT_X, y + 40, 100, 16, 0xFF9E9E9E);   // 范围框凹槽
        PeStyle.inset(graphics, x + LEFT_X, y + LIST_TOP, 100, LIST_H, 0xFFA8A8A8); // 列表面板
        // 中央槽位面板
        PeStyle.inset(graphics, x + CENTER_X - 4, y + CENTER_TOP - 6, 8 * SLOT_CELL + 8, 96, 0xFFA8A8A8);
        // 右栏
        PeStyle.inset(graphics, x + RIGHT_X, y + 18, RIGHT_W, 122, 0xFFA8A8A8);
        // 出售区
        PeStyle.inset(graphics, x + 6, y + SELL_TOP, 100, 22, 0xFF9E9E9E);
        PeStyle.inset(graphics, x + CENTER_X - 4, y + SELL_TOP, 8 * SLOT_CELL + 8, 22, 0xFF9E9E9E);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 标题
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, PeStyle.TEXT_TITLE);
        graphics.drawString(this.font, t("poketrade.gui.browser_subtitle"), 8, 12, PeStyle.TEXT_DIM);

        // 左列表头
        graphics.drawString(this.font, t("poketrade.gui.range"), LEFT_X + 48, 34, PeStyle.TEXT);
        graphics.drawString(this.font, t("poketrade.gui.nearby"), LEFT_X, LIST_TOP + 4, PeStyle.TEXT);
        // 预设按钮 1/32/128
        int by = 34;
        PeStyle.segmented(graphics, this.font, LEFT_X + 54, by, 14, 12,
                "1", viewModel.isRadiusPreset(1), false, mouseX, mouseY);
        PeStyle.segmented(graphics, this.font, LEFT_X + 70, by, 22, 12,
                "32", viewModel.isRadiusPreset(32), false, mouseX, mouseY);
        PeStyle.segmented(graphics, this.font, LEFT_X + 94, by, 28, 12,
                "128", viewModel.isRadiusPreset(128), false, mouseX, mouseY);

        // 排序/过滤分段
        PeStyle.segmented(graphics, this.font, LEFT_X, LIST_TOP - 12, 50, 11,
                t("poketrade.gui.distance"), viewModel.getSortMode() == StorageViewModel.SortMode.DISTANCE, false, mouseX, mouseY);
        PeStyle.segmented(graphics, this.font, LEFT_X + 51, LIST_TOP - 12, 49, 11,
                t("poketrade.gui.name"), viewModel.getSortMode() == StorageViewModel.SortMode.NAME, false, mouseX, mouseY);

        // 仓储列表
        int start = listStart();
        List<StorageDescriptor> visible = viewModel.visibleStorages();
        for (int i = 0; i < LIST_VISIBLE && start + i < visible.size(); i++) {
            StorageDescriptor d = visible.get(start + i);
            int rowY = LIST_TOP + 14 + i * LIST_ROW_H;
            boolean selected = d.storageId().equals(viewModel.getSelectedStorageId());
            if (selected) {
                graphics.fill(LEFT_X + 1, rowY - 1, LEFT_X + 99, rowY + LIST_ROW_H - 1,
                        0x408B6B1B);
            }
            String name = d.displayName();
            if (name.length() > 10) {
                name = name.substring(0, 10);
            }
            graphics.drawString(this.font, name, LEFT_X + 2, rowY, PeStyle.TEXT_TITLE);
            String meta = d.claimed() ? (d.distance() + "m") : t("poketrade.gui.unclaimed");
            graphics.drawString(this.font, meta, LEFT_X + 2, rowY + 8, PeStyle.TEXT_DIM);
        }
        PeStyle.scrollbar(graphics, LEFT_X + 98, LIST_TOP + 8,
                LIST_H - 14, visible.size(), LIST_VISIBLE, start);

        // 扫描状态
        if (!viewModel.isScanComplete()) {
            graphics.drawString(this.font, t("poketrade.gui.scanning"), LEFT_X, LIST_TOP + LIST_H - 8, PeStyle.TEXT_WARN);
        }

        // 中央槽位（选中仓储）
        StorageId selected = viewModel.getSelectedStorageId();
        if (selected != null) {
            graphics.drawString(this.font, shortId(selected), CENTER_X - 4, CENTER_TOP - 16, PeStyle.TEXT_DIM);
            if (!viewModel.hasPermission(StoragePermission.VIEW)) {
                graphics.drawString(this.font, t("poketrade.gui.no_view"), CENTER_X, CENTER_TOP + 20, PeStyle.TEXT_ERROR);
            } else {
                Map<Integer, StorageItemSlot> slots = viewModel.visibleSlots();
                int i = 0;
                for (StorageItemSlot slot : slots.values()) {
                    int col = i % SLOT_COLS;
                    int row = i / SLOT_COLS;
                    int sx = CENTER_X + col * SLOT_CELL;
                    int sy = CENTER_TOP + row * SLOT_CELL;
                    if (row >= 5) {
                        break;
                    }
                    PeStyle.slot(graphics, sx, sy);
                    ItemStack stack = toStack(slot);
                    graphics.renderItem(stack, sx + 1, sy + 1);
                    graphics.drawString(this.font, String.valueOf(slot.count()),
                            sx + 6, sy + 9, PeStyle.TEXT_PKM);
                    i++;
                }
                if (slots.isEmpty()) {
                    graphics.drawString(this.font, t("poketrade.gui.empty"), CENTER_X, CENTER_TOP + 20, PeStyle.TEXT_DIM);
                }
            }
        } else {
            graphics.drawString(this.font, t("poketrade.gui.select_storage"), CENTER_X - 4, CENTER_TOP + 20, PeStyle.TEXT_DIM);
        }

        // 右栏：管理（仅选中时）
        if (selected != null) {
            graphics.drawString(this.font, t("poketrade.gui.manage"), RIGHT_X + 2, 20, PeStyle.TEXT_TITLE);
            if (!viewModel.hasPermission(StoragePermission.MANAGE)) {
                graphics.drawString(this.font, t("poketrade.gui.no_manage"), RIGHT_X + 2, 36, PeStyle.TEXT_DIM);
            } else {
                // 六项权限复选框（允许）
                int py = 32;
                for (StoragePermission p : StoragePermission.values()) {
                    boolean checked = viewModel.visibleGrants().values().stream()
                            .anyMatch(g -> g.allow().allows(p));
                    PeStyle.checkbox(graphics, RIGHT_X + 2, py,
                            checked, false, mouseX, mouseY);
                    graphics.drawString(this.font, p.name(), RIGHT_X + 14, py, PeStyle.TEXT);
                    py += 12;
                }
                // 模板
                graphics.drawString(this.font, t("poketrade.gui.template"), RIGHT_X + 2, py + 4, PeStyle.TEXT_TITLE);
                int ty = py + 16;
                List<StorageTemplate> templates = viewModel.getTemplates();
                if (templates.isEmpty()) {
                    graphics.drawString(this.font, t("poketrade.gui.no_template"), RIGHT_X + 2, ty, PeStyle.TEXT_DIM);
                } else {
                    for (int i = 0; i < Math.min(templates.size(), 3); i++) {
                        StorageTemplate t = templates.get(i);
                        String label = (t.scope() == StorageTemplate.Scope.SERVER ? "[S] " : "") + t.name();
                        graphics.drawString(this.font, label.length() > 10 ? label.substring(0, 10) : label,
                                RIGHT_X + 2, ty + i * 11, PeStyle.TEXT);
                    }
                }
                // 重命名
                graphics.drawString(this.font, t("poketrade.gui.rename"), RIGHT_X + 2, 136, PeStyle.TEXT_TITLE);
                // 自动化
                graphics.drawString(this.font, t("poketrade.gui.automation"), RIGHT_X + 2, 158, PeStyle.TEXT_TITLE);
            }
        }

        // 出售区
        graphics.drawString(this.font, Component.translatable("poketrade.gui.sell_area", sellQueue.size()).getString(),
                LEFT_X, SELL_TOP + 4, PeStyle.TEXT_TITLE);
        graphics.drawString(this.font, t("poketrade.exchange.cart.clear"),
                LEFT_X + 6, SELL_TOP + 14, PeStyle.TEXT);
        graphics.drawString(this.font, t("poketrade.exchange.sell.storage"),
                LEFT_X + 44, SELL_TOP + 14, PeStyle.TEXT);
        graphics.drawString(this.font, t("poketrade.exchange.deposit"),
                LEFT_X + 84, SELL_TOP + 14, PeStyle.TEXT);
        if (!sellQueue.isEmpty()) {
            int i = 0;
            for (PendingSell sell : sellQueue.values()) {
                int sx = CENTER_X + i * SLOT_CELL;
                PeStyle.slot(graphics, sx, SELL_TOP + 2);
                graphics.renderItem(toStack(new StorageItemSlot(
                        sell.slotIndex, sell.itemId, sell.count, sell.fingerprint)),
                        sx + 1, SELL_TOP + 3);
                i++;
                if (i >= 4) {
                    break;
                }
            }
        }
        // 管理消息
        if (!manageMessage.isEmpty()) {
            graphics.drawString(this.font, manageMessage, RIGHT_X - 116, SELL_TOP + 4, manageMessageColor);
        }
    }

    private String shortId(StorageId id) {
        String loc = id.location();
        int idx = loc.lastIndexOf(',');
        return idx >= 0 ? loc.substring(idx + 1) : loc;
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

    private int listStart() {
        List<StorageDescriptor> visible = viewModel.visibleStorages();
        return Math.min(listOffset, Math.max(0, visible.size() - LIST_VISIBLE));
    }

    private int listOffset = 0;

    // ================= 交互 =================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int lx = toLocalX(mouseX);
        int ly = toLocalY(mouseY);
        if (button != 0) {
            return super.mouseClicked(lx, ly, button);
        }
        // 输入框在自适应缩放下以局部坐标命中（渲染也在统一缩放矩阵内）
        if (searchBox != null && searchBox.mouseClicked(lx, ly, button)) {
            return true;
        }
        if (radiusBox != null && radiusBox.mouseClicked(lx, ly, button)) {
            return true;
        }
        if (renameBox != null && renameBox.mouseClicked(lx, ly, button)) {
            return true;
        }

        // 预设范围按钮
        if (ly >= 34 && ly < 46 && lx >= LEFT_X + 54 && lx < LEFT_X + 96) {
            int preset = lx < LEFT_X + 68 ? 1 : (lx < LEFT_X + 92 ? 32 : 128);
            viewModel.setRadius(preset);
            radiusBox.setValue(String.valueOf(preset));
            requestQuery();
            return true;
        }
        // 排序分段
        if (ly >= LIST_TOP - 12 && ly < LIST_TOP - 1) {
            if (lx >= LEFT_X && lx < LEFT_X + 50) {
                viewModel.setSortMode(StorageViewModel.SortMode.DISTANCE);
            } else if (lx >= LEFT_X + 51 && lx < LEFT_X + 100) {
                viewModel.setSortMode(StorageViewModel.SortMode.NAME);
            }
            requestQuery();
            return true;
        }
        // 仓储列表选择
        List<StorageDescriptor> visible = viewModel.visibleStorages();
        int start = listStart();
        if (lx >= LEFT_X && lx < LEFT_X + 100 && ly >= LIST_TOP + 14 && ly < LIST_TOP + LIST_H) {
            int idx = start + (ly - (LIST_TOP + 14)) / LIST_ROW_H;
            if (idx < visible.size()) {
                selectStorage(visible.get(idx));
                return true;
            }
        }
        // 中央快照槽位：按下先记录（松开时区分点击/拖拽）；Shift=取出；拿着物品时松开=存入
        if (isCenterSlot(lx, ly)) {
            StorageItemSlot slot = centerSlotAt(lx, ly);
            if (slot == null) {
                return true;
            }
            if (hasShiftDown()) {
                int auto = findInventoryTarget(slot);
                if (auto >= 0) {
                    withdrawDragToInventory(slot, auto);
                } else {
                    manageMessage = t("poketrade.exchange.withdraw.full");
                    manageMessageColor = PeStyle.TEXT_ERROR;
                }
                return true;
            }
            if (!menu.getCarried().isEmpty()) {
                return true; // 松开时执行存入
            }
            dragSource = slot;
            dragPressX = lx;
            dragPressY = ly;
            return true;
        }
        // 出售区三个操作区：清空待售 / 结算出售 / 一键存入
        if (ly >= SELL_TOP && ly < SELL_TOP + 22) {
            if (lx >= LEFT_X && lx < LEFT_X + 40) {
                sellQueue.clear();
                return true;
            }
            if (lx >= LEFT_X + 42 && lx < LEFT_X + 80) {
                submitSell();
                return true;
            }
            if (lx >= LEFT_X + 82 && lx < LEFT_X + 106) {
                depositAll();
                return true;
            }
        }
        // 管理操作（权限提交/模板/自动化）——仅在选中且有 MANAGE 时
        if (viewModel.getSelectedStorageId() != null
                && viewModel.hasPermission(StoragePermission.MANAGE)) {
            handleManageClick(lx, ly);
        }
        return super.mouseClicked(lx, ly, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        return super.mouseDragged(toLocalX(mouseX), toLocalY(mouseY), button, dragX, dragY);
    }

    private boolean isCenterSlot(int lx, int ly) {
        return lx >= CENTER_X - 4 && lx < CENTER_X + 8 * SLOT_CELL
                && ly >= CENTER_TOP - 6 && ly < CENTER_TOP + 90;
    }

    private StorageItemSlot centerSlotAt(int lx, int ly) {
        int col = (lx - CENTER_X) / SLOT_CELL;
        int row = (ly - CENTER_TOP) / SLOT_CELL;
        int idx = row * SLOT_COLS + col;
        int i = 0;
        for (StorageItemSlot slot : viewModel.visibleSlots().values()) {
            if (i == idx) {
                return slot;
            }
            i++;
        }
        return null;
    }

    /** 局部坐标命中的玩家背包槽位（0-35）；未命中返回 -1。 */
    private int inventorySlotAt(int lx, int ly) {
        for (int i = 0; i < 36; i++) {
            Slot slot = menu.getSlot(i);
            if (lx >= slot.x && lx < slot.x + SLOT_CELL
                    && ly >= slot.y && ly < slot.y + SLOT_CELL) {
                return i;
            }
        }
        return -1;
    }

    /** 自动寻找背包目标槽位：优先同物品合并，再找空位；无合适槽位返回 -1。 */
    private int findInventoryTarget(StorageItemSlot source) {
        Item item = null;
        try {
            item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(source.itemId()));
        } catch (RuntimeException ignored) {
            // 物品 id 无法解析时按不可取出处理
        }
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return -1;
        }
        int maxStack = Math.max(1, item.getDefaultMaxStackSize());
        ItemStack sample = new ItemStack(item);
        Inventory inv = this.minecraft.player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, sample)
                    && s.getCount() < maxStack) {
                return i;
            }
        }
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 从仓储槽位取出到指定背包槽位（拖拽/Shift 共用）。 */
    private void withdrawDragToInventory(StorageItemSlot slot, int inventorySlot) {
        if (inventorySlot < 0) {
            return;
        }
        StorageId selected = viewModel.getSelectedStorageId();
        if (selected == null || !viewModel.hasPermission(StoragePermission.WITHDRAW)) {
            manageMessage = t("poketrade.exchange.withdraw.denied");
            manageMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (viewModel.getSelectedSnapshotRevision() < 0) {
            manageMessage = t("poketrade.exchange.sell.storage.loading");
            manageMessageColor = PeStyle.TEXT_WARN;
            return;
        }
        Item item = null;
        try {
            item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(slot.itemId()));
        } catch (RuntimeException ignored) {
            // 物品 id 无法解析时按不可取出处理
        }
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            manageMessage = t("poketrade.exchange.withdraw.invalid");
            manageMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        int maxStack = Math.max(1, item.getDefaultMaxStackSize());
        ItemStack existing = this.minecraft.player.getInventory().getItem(inventorySlot);
        int count;
        if (existing.isEmpty()) {
            count = Math.min(slot.count(), maxStack);
        } else if (ItemStack.isSameItemSameComponents(existing, new ItemStack(item))) {
            count = Math.min(slot.count(), maxStack - existing.getCount());
        } else {
            manageMessage = t("poketrade.exchange.withdraw.invalid");
            manageMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (count <= 0) {
            manageMessage = t("poketrade.exchange.withdraw.full");
            manageMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        manageMessage = t("poketrade.exchange.withdraw.pending");
        manageMessageColor = PeStyle.TEXT_DIM;
        PacketDistributor.sendToServer(new StorageMovePacket(
                sessionId, UUID.randomUUID().toString(), -1L,
                StorageEndpoint.storage(selected, slot.slotIndex()),
                StorageEndpoint.inventory(inventorySlot),
                count,
                slot.fingerprint(), 0L,
                Map.of(selected, viewModel.getSelectedSnapshotRevision())));
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        int lx = toLocalX(mouseX);
        int ly = toLocalY(mouseY);
        // 中央快照槽位按下后的结算：未移动 = 点击切换待售；移动并落在背包 = 拖拽取出
        if (button == 0 && dragSource != null) {
            StorageItemSlot source = dragSource;
            dragSource = null;
            int moved = Math.abs(lx - dragPressX) + Math.abs(ly - dragPressY);
            if (moved > 3) {
                int target = inventorySlotAt(lx, ly);
                if (target >= 0) {
                    withdrawDragToInventory(source, target);
                }
                return true;
            }
            StorageId selected = viewModel.getSelectedStorageId();
            if (selected != null && viewModel.hasPermission(StoragePermission.SELL)) {
                if (sellQueue.containsKey(source.slotIndex())) {
                    sellQueue.remove(source.slotIndex());
                } else {
                    sellQueue.put(source.slotIndex(), new PendingSell(
                            selected, source.slotIndex(), source.itemId(),
                            source.count(), source.fingerprint()));
                }
            }
            return true;
        }
        // 手里拿着物品，松手在中央槽位 -> 存入该槽位
        ItemStack carried = menu.getCarried();
        if (button == 0 && !carried.isEmpty() && isCenterSlot(lx, ly)) {
            StorageItemSlot slot = centerSlotAt(lx, ly);
            StorageId selected = viewModel.getSelectedStorageId();
            if (slot != null && selected != null
                    && viewModel.hasPermission(StoragePermission.DEPOSIT)
                    && viewModel.getSelectedSnapshotRevision() >= 0) {
                PacketDistributor.sendToServer(new StorageDepositCarriedPacket(
                        sessionId, UUID.randomUUID().toString(), selected, slot.slotIndex(),
                        viewModel.getSelectedSnapshotRevision(), carried.getCount()));
                manageMessage = t("poketrade.exchange.deposit.pending");
                manageMessageColor = PeStyle.TEXT_DIM;
                return true;
            }
        }
        return super.mouseReleased(lx, ly, button);
    }

    /** 右栏点击：权限提交、模板应用、自动化开关。 */
    private void handleManageClick(int lx, int ly) {
        if (lx < RIGHT_X || lx >= RIGHT_X + RIGHT_W) {
            return;
        }
        StorageId selected = viewModel.getSelectedStorageId();
        if (selected == null || lastManageResponse == null) {
            return;
        }
        long rev = lastManageResponse.revision();
        // 权限区域：六项复选框行（32..104）——点击行切换该权限的 allow 位
        if (ly >= 32 && ly < 104) {
            int idx = (ly - 32) / 12;
            if (idx >= 0 && idx < StoragePermission.values().length) {
                StoragePermission p = StoragePermission.values()[idx];
                Map<StoragePrincipal, StorageGrant> current = new LinkedHashMap<>(
                        viewModel.visibleGrants());
                boolean wasAllowed = current.values().stream().anyMatch(g -> g.allow().allows(p));
                StoragePrincipal principal = new StoragePrincipal.Public();
                StorageGrant before = current.get(principal);
                EnumSet<StoragePermission> allow = before == null
                        ? EnumSet.noneOf(StoragePermission.class)
                        : EnumSet.copyOf(before.allow().values());
                EnumSet<StoragePermission> deny = before == null
                        ? EnumSet.noneOf(StoragePermission.class)
                        : EnumSet.copyOf(before.deny().values());
                if (wasAllowed) {
                    allow.remove(p);
                } else {
                    allow.add(p);
                }
                current.put(principal, new StorageGrant(
                        StoragePermissionSet.from(allow), StoragePermissionSet.from(deny)));
                if (StorageViewModel.isHighRisk(current)) {
                    if (!highRiskConfirm) {
                        highRiskConfirm = true;
                        manageMessage = t("poketrade.gui.high_risk_confirm");
                        manageMessageColor = PeStyle.TEXT_WARN;
                        return;
                    }
                    highRiskConfirm = false;
                }
                PacketDistributor.sendToServer(new StorageManagePacket(
                        sessionId, selected, rev, StorageManagePacket.ManageAction.PUT_GRANTS,
                        current, null, null, null, null, null));
            }
            return;
        }
        // 模板应用（115..148）：第一个模板 COPY，第二个 FOLLOW
        if (ly >= 115 && ly < 148) {
            List<StorageTemplate> templates = viewModel.getTemplates();
            if (templates.isEmpty()) {
                return;
            }
            int slotIdx = (ly - 115) / 11;
            if (slotIdx >= templates.size()) {
                return;
            }
            StorageTemplate t = templates.get(slotIdx);
            StorageRecord.TemplateMode mode = slotIdx % 2 == 0
                    ? StorageRecord.TemplateMode.COPY : StorageRecord.TemplateMode.FOLLOW;
            if (StorageViewModel.isHighRisk(t.grants())) {
                if (!highRiskConfirm) {
                    highRiskConfirm = true;
                    manageMessage = t("poketrade.gui.high_risk_template_confirm");
                    manageMessageColor = PeStyle.TEXT_WARN;
                    return;
                }
                highRiskConfirm = false;
            }
            PacketDistributor.sendToServer(new StorageManagePacket(
                    sessionId, selected, rev, StorageManagePacket.ManageAction.APPLY_TEMPLATE,
                    Map.of(), t.id(), mode, null, null, null));
            return;
        }
        // 自动化（158..170）
        if (ly >= 158 && ly < 170) {
            // 单一“自动化”开关：两个方向一起切换，避免出现“只插不取/只取不插”的半开状态
            boolean bothOn = lastManageResponse.automationInsert()
                    && lastManageResponse.automationExtract();
            boolean value = !bothOn;
            PacketDistributor.sendToServer(new StorageManagePacket(
                    sessionId, selected, rev, StorageManagePacket.ManageAction.SET_AUTOMATION,
                    Map.of(), null, null, null, value, value));
        }
        // 重命名（136..150）
        if (ly >= 136 && ly < 150 && renameBox != null) {
            String name = renameBox.getValue().trim();
            if (!name.isEmpty()) {
                PacketDistributor.sendToServer(new StorageManagePacket(
                        sessionId, selected, rev, StorageManagePacket.ManageAction.RENAME,
                        Map.of(), null, null, name, null, null));
            }
        }
    }

    /** 发送出售请求（两阶段：服务端全成或全败）。 */
    private void submitSell() {
        if (sellQueue.isEmpty()) {
            return;
        }
        if (viewModel.getSelectedSnapshotRevision() < 0 || viewModel.isSnapshotStale()) {
            // 快照未加载或已过期时提交必然触发 revision 冲突：先提示而不是白失败
            manageMessage = t("poketrade.exchange.sell.storage.loading");
            manageMessageColor = PeStyle.TEXT_WARN;
            return;
        }
        List<ExchangeService.SellEntry> entries = new ArrayList<>();
        Map<StorageId, Long> revisions = new LinkedHashMap<>();
        for (PendingSell sell : sellQueue.values()) {
            entries.add(new ExchangeService.SellEntry(
                    sell.storageId(), sell.slotIndex(), sell.count(), sell.fingerprint()));
            revisions.put(sell.storageId(), viewModel.getSelectedSnapshotRevision());
        }
        PacketDistributor.sendToServer(new StorageSellPacket(
                sessionId, UUID.randomUUID().toString().substring(0, 8),
                entries, revisions));
    }

    /** 一键存入：把主背包全部物品存入选中的仓储（服务端自动找槽位）。 */
    private void depositAll() {
        StorageId selected = viewModel.getSelectedStorageId();
        if (selected == null) {
            manageMessage = t("poketrade.gui.select_storage");
            manageMessageColor = PeStyle.TEXT_WARN;
            return;
        }
        if (!viewModel.hasPermission(StoragePermission.DEPOSIT)) {
            manageMessage = t("poketrade.exchange.deposit.denied");
            manageMessageColor = PeStyle.TEXT_ERROR;
            return;
        }
        if (viewModel.getSelectedSnapshotRevision() < 0 || viewModel.isSnapshotStale()) {
            manageMessage = t("poketrade.exchange.sell.storage.loading");
            manageMessageColor = PeStyle.TEXT_WARN;
            return;
        }
        Inventory inv = this.minecraft.player.getInventory();
        List<StorageDepositPacket.DepositLine> lines = new ArrayList<>();
        for (int i = 0; i < inv.items.size(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                lines.add(new StorageDepositPacket.DepositLine(i, inv.getItem(i).getCount()));
            }
        }
        if (lines.isEmpty()) {
            manageMessage = t("poketrade.exchange.deposit.none");
            manageMessageColor = PeStyle.TEXT_DIM;
            return;
        }
        manageMessage = t("poketrade.exchange.deposit.pending");
        manageMessageColor = PeStyle.TEXT_DIM;
        PacketDistributor.sendToServer(new StorageDepositPacket(
                sessionId, UUID.randomUUID().toString(), selected,
                -1L, lines));
    }

    @Override
    public void onDepositResponse(StorageDepositPacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        StorageId selected = viewModel.getSelectedStorageId();
        if (selected != null) {
            requestSnapshot(selected);
        }
        manageMessage = t(response.success()
                        ? "poketrade.exchange.deposit.done"
                        : "poketrade.exchange.deposit.failed",
                response.movedLines(), response.totalLines());
        manageMessageColor = response.success() ? PeStyle.TEXT_OK : PeStyle.TEXT_ERROR;
    }

    @Override
    public void onMoveResponse(StorageMovePacket.Response response) {
        if (!sessionId.equals(response.sessionId())) {
            return;
        }
        StorageId selected = viewModel.getSelectedStorageId();
        if (selected != null) {
            requestSnapshot(selected);
        }
        manageMessage = t(response.success()
                ? "poketrade.exchange.withdraw.done"
                : "poketrade.exchange.withdraw.failed");
        manageMessageColor = response.success() ? PeStyle.TEXT_OK : PeStyle.TEXT_ERROR;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            int maxOffset = Math.max(0, viewModel.visibleStorages().size() - LIST_VISIBLE);
            listOffset = Math.max(0, Math.min(listOffset + (scrollY > 0 ? -1 : 1), maxOffset));
            return true;
        }
        return super.mouseScrolled(toLocalX(mouseX), toLocalY(mouseY), scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox.isFocused() || radiusBox.isFocused() || renameBox.isFocused()) {
            boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
            if (searchBox.isFocused()) {
                requestQuery();
            }
            if (radiusBox.isFocused()) {
                requestQuery();
            }
            return handled;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 排序与过滤映射（服务端枚举名与客户端一致，直接透传）。
     */
}
