package com.pokeemc.client;

import com.pokeemc.emc.PKMManager;
import com.pokeemc.menu.TransmutationTableMenu;
import com.pokeemc.network.OpenStorageBrowserPacket;
import com.pokeemc.network.TradePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 转化桌屏幕：
 * <ul>
 *   <li>顶部：标题 + PKM 余额（玩家 Pixelmon 钱包）</li>
 *   <li>中部：存入槽 / 结果槽（菜单自带渲染）</li>
 *   <li>底部：搜索框 + 可兑换物品列表（点击兑换 1 个，Shift+点击兑换一组）</li>
 * </ul>
 * 坐标约定（1.21.1）：
 * <ul>
 *   <li>renderBg 使用全局坐标（leftPos/topPos）</li>
 *   <li>renderLabels 在 translate(leftPos, topPos) 内，使用局部坐标</li>
 *   <li>mouseClicked 使用全局坐标</li>
 * </ul>
 */
public class TransmutationTableScreen extends AbstractContainerScreen<TransmutationTableMenu> {

    private static final int BG_WIDTH = 176;
    private static final int BG_HEIGHT = 250;
    private static final int COLUMNS = 9;
    private static final int ROWS = 4;
    private static final int VISIBLE = COLUMNS * ROWS;
    private static final NumberFormat FORMATTER = NumberFormat.getIntegerInstance();

    /** 列表面板（含表头）区域 */
    private static final int PANEL_TOP = 58;
    private static final int PANEL_BOTTOM = 148;
    private static final int LIST_LEFT = 8;
    private static final int LIST_TOP = 74;
    private static final int CELL = 18;

    /** 玩家背包 / 快捷栏（与菜单槽位坐标保持一致） */
    private static final int INV_Y = 164;
    private static final int HOTBAR_Y = 226;

    private EditBox searchBox;
    private final List<PKMManager.PricedStack> filteredItems = new ArrayList<>();
    private int scrollOffset = 0;
    private String lastQuery = null;
    private long lastPkmSnapshot = -1;

    /** PKM 余额文本区域（局部坐标 {x1,y1,x2,y2}，renderLabels 每帧更新；悬停显示完整值）。 */
    private int[] pkmBalanceBox;

    public TransmutationTableScreen(TransmutationTableMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = BG_WIDTH;
        this.imageHeight = BG_HEIGHT;
        this.inventoryLabelY = INV_Y - 11;
        this.titleLabelX = 8;
        this.titleLabelY = 7;
    }

    @Override
    protected void init() {
        super.init();
        // 无边框输入框：边框由背景自绘，避免默认黑底压住下方标签
        this.searchBox = new EditBox(this.font, this.leftPos + 10, this.topPos + 21, 156, 12, Component.literal("搜索"));
        this.searchBox.setMaxLength(64);
        this.searchBox.setValue("");
        this.searchBox.setBordered(false);
        this.searchBox.setTextColor(0xFF303030);
        this.searchBox.setTextColorUneditable(0xFF303030);
        this.searchBox.setResponder(s -> {
            this.lastQuery = null; // 强制刷新
            this.scrollOffset = 0;
        });
        this.addRenderableWidget(this.searchBox);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        refreshFiltered();
    }

    /** 缓存并刷新物品列表（按搜索词过滤、按 PKM 排序） */
    private void refreshFiltered() {
        long pkm = menu.getPkm();
        if (lastPkmSnapshot == pkm && lastQuery != null && lastQuery.equals(searchBox.getValue())) {
            return;
        }
        lastPkmSnapshot = pkm;
        lastQuery = searchBox.getValue();
        String query = lastQuery.toLowerCase();

        filteredItems.clear();
        for (PKMManager.PricedStack entry : PKMManager.snapshotStacks()) {
            ItemStack stack = entry.stack();
            if (!query.isEmpty()) {
                String name = stack.getItem().getDescriptionId().toLowerCase();
                String display = stack.getHoverName().getString().toLowerCase();
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(); // [CHANGED] 官方 API
                if (!name.contains(query) && !display.contains(query) && !id.contains(query)) {
                    continue;
                }
            }
            filteredItems.add(entry);
        }
        filteredItems.sort(Comparator.comparingLong(PKMManager.PricedStack::value).reversed());
    }

    /**
     * [CHANGED] Bug D 修复：基类 {@link AbstractContainerScreen#render} 只负责更新 hoveredSlot，
     * <b>不会</b>调用 renderTooltip（vanilla 约定由每个容器子类在 render 末尾显式调用）。
     * 本屏此前未覆盖 render，导致背包物品/存入取出槽悬停不显示任何提示；
     * 现在在基类渲染完成后调用 renderTooltip，列表条目走下方覆盖逻辑、背包走基类逻辑。
     */
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        // PE 符文底纹 + PE 风格外框
        PeStyle.runeBackground(graphics, x, y, BG_WIDTH, BG_HEIGHT);
        PeStyle.windowFrame(graphics, x, y, BG_WIDTH, BG_HEIGHT);
        // 搜索框凹槽
        PeStyle.inset(graphics, x + 8, y + 19, 160, 16, 0xFF9E9E9E);
        // 仓储浏览按钮（复用独立浏览器 UI，不维护第二套权限/范围状态）
        PeStyle.button(graphics, this.font, x + 118, y + 37, 50, 14, "浏览仓储",
                true, false, mouseX >= x + 118 && mouseX < x + 168
                        && mouseY >= y + 37 && mouseY < y + 51, mouseX, mouseY);
        // 存入 / 取出槽
        PeStyle.slot(graphics, x + 44, y + 37);
        PeStyle.slot(graphics, x + 114, y + 37);
        // 物品列表面板
        PeStyle.inset(graphics, x + 6, y + PANEL_TOP, BG_WIDTH - 12, PANEL_BOTTOM - PANEL_TOP, 0xFFA8A8A8);
        // 玩家背包槽位
        PeStyle.playerInventory(graphics, x + 8, y + INV_Y, y + HOTBAR_Y);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 局部坐标（已在 translate(leftPos, topPos) 内）
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, PeStyle.TEXT_TITLE);
        // PKM 余额（标题同行右侧，即玩家 Pixelmon 钱包余额）
        // [CHANGED] 会话 #25：大额缩写（1k/1m/1b）显示，避免大数撑出标题行与标题/背包穿模；
        // 记录文本区域（局部坐标）供 renderTooltip 悬停显示完整千分位金额。
        long pkm = menu.getPkm();
        String pkmText = Component.translatable("poketrade.gui.pkm").getString()
                + ": " + ExchangeUiModel.formatWallet(pkm);
        int pkmX = this.imageWidth - this.font.width(pkmText) - 8;
        this.pkmBalanceBox = new int[]{pkmX, this.titleLabelY,
                this.imageWidth - 8, this.titleLabelY + 9};
        graphics.drawString(this.font, pkmText, pkmX, this.titleLabelY, PeStyle.TEXT_PKM);
        // 槽位标签（在槽位上方，不与搜索框/槽位重叠）
        graphics.drawString(this.font, "存入", 44, 27, PeStyle.TEXT);
        graphics.drawString(this.font, "取出", 114, 27, PeStyle.TEXT);
        // 列表表头
        graphics.drawString(this.font, "可兑换（左键 1 个 / Shift 一组）", LIST_LEFT, PANEL_TOP + 5, PeStyle.TEXT);
        // 玩家背包标签
        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, PeStyle.TEXT);
        // 物品列表：绘制可见条目（悬停检测：mouseX/mouseY 为全局坐标，需减去 leftPos/topPos）
        int start = listStart();
        for (int i = 0; i < VISIBLE && start + i < filteredItems.size(); i++) {
            ItemStack stack = filteredItems.get(start + i).stack();
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int x = LIST_LEFT + col * CELL;
            int y = LIST_TOP + row * CELL;
            boolean hovered = mouseX - this.leftPos >= x && mouseX - this.leftPos < x + CELL
                    && mouseY - this.topPos >= y && mouseY - this.topPos < y + CELL;
            if (hovered) {
                graphics.fill(x, y, x + CELL, y + CELL, 0x60FFFFFF);
            }
            graphics.renderItem(stack, x + 1, y + 1);
        }
    }

    /** 悬停在列表条目上时显示名称与价格（避免价格数字压在图标上） */
    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        // [CHANGED] 会话 #25：悬停 PKM 余额（顶部缩写）显示完整千分位金额
        if (pkmBalanceBox != null
                && mouseX >= this.leftPos + pkmBalanceBox[0] && mouseX < this.leftPos + pkmBalanceBox[2]
                && mouseY >= this.topPos + pkmBalanceBox[1] && mouseY < this.topPos + pkmBalanceBox[3]) {
            graphics.renderTooltip(this.font, List.of(
                            Component.translatable("poketrade.gui.pkm")
                                    .withStyle(net.minecraft.ChatFormatting.YELLOW),
                            Component.literal(FORMATTER.format(menu.getPkm()) + " PKM")),
                    java.util.Optional.empty(), mouseX, mouseY);
            return;
        }
        int start = listStart();
        for (int i = 0; i < VISIBLE && start + i < filteredItems.size(); i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int x = this.leftPos + LIST_LEFT + col * CELL;
            int y = this.topPos + LIST_TOP + row * CELL;
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                PKMManager.PricedStack entry = filteredItems.get(start + i);
                ItemStack stack = entry.stack();
                String pkm = Component.translatable("poketrade.gui.pkm").getString()
                        + ": " + FORMATTER.format(entry.value());
                graphics.renderTooltip(this.font, List.of(
                        stack.getHoverName(),
                        Component.literal(pkm).withStyle(net.minecraft.ChatFormatting.YELLOW)
                ), java.util.Optional.empty(), mouseX, mouseY);
                return;
            }
        }
        super.renderTooltip(graphics, mouseX, mouseY);
    }

    /** 当前页首条目下标 */
    private int listStart() {
        return Math.min(scrollOffset * COLUMNS, Math.max(0, filteredItems.size() - VISIBLE));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (searchBox.isFocused() && (button == 0 || button == 1)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 物品列表点击（全局坐标）
        int start = listStart();
        // 仓储浏览按钮点击 -> 打开独立仓储浏览器（复用同一查询/权限/快照/事务 API）
        if (mouseX >= this.leftPos + 118 && mouseX < this.leftPos + 168
                && mouseY >= this.topPos + 37 && mouseY < this.topPos + 51) {
            PacketDistributor.sendToServer(new OpenStorageBrowserPacket());
            return true;
        }
        for (int i = 0; i < VISIBLE && start + i < filteredItems.size(); i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int x = this.leftPos + LIST_LEFT + col * CELL;
            int y = this.topPos + LIST_TOP + row * CELL;
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                ItemStack stack = filteredItems.get(start + i).stack();
                int count = hasShiftDown() ? stack.getMaxStackSize() : 1;
                PacketDistributor.sendToServer(new TradePacket(stack, count));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            int maxScroll = Math.max(0, (int) Math.ceil(filteredItems.size() / (double) COLUMNS) - ROWS);
            scrollOffset = Math.max(0, Math.min(scrollOffset + (scrollY > 0 ? -1 : 1), maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
