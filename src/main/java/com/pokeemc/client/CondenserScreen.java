package com.pokeemc.client;

import com.pokeemc.emc.PKMManager;
import com.pokeemc.menu.CondenserMenu;
import com.pokeemc.network.SetCondenserTargetPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 能量凝聚器屏幕：
 * <ul>
 *   <li>顶部：标题 + 目标物品 + PKM 缓冲</li>
 *   <li>中部：输入槽 / 输出槽（自动凝聚）</li>
 *   <li>底部：搜索框 + 物品列表（点击选择凝聚目标）</li>
 * </ul>
 * 点击物品即把该物品设为凝聚目标（发送 {@link SetCondenserTargetPacket}）。
 */
public class CondenserScreen extends AbstractContainerScreen<CondenserMenu> {

    private static final int BG_WIDTH = 176;
    private static final int BG_HEIGHT = 250;
    private static final int COLUMNS = 9;
    private static final int ROWS = 4;
    private static final int VISIBLE = COLUMNS * ROWS;
    private static final NumberFormat FORMATTER = NumberFormat.getIntegerInstance();

    /** 列表面板（表头内显示当前凝聚目标）区域 */
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

    public CondenserScreen(CondenserMenu menu, Inventory playerInventory, Component title) {
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
            this.lastQuery = null;
            this.scrollOffset = 0;
        });
        this.addRenderableWidget(this.searchBox);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        refreshFiltered();
    }

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
                String id = stack.getItem().builtInRegistryHolder().key().location().toString().toLowerCase();
                if (!name.contains(query) && !display.contains(query) && !id.contains(query)) {
                    continue;
                }
            }
            filteredItems.add(entry);
        }
        filteredItems.sort(Comparator.comparingLong(PKMManager.PricedStack::value).reversed());
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
        // 投入 / 产出槽
        PeStyle.slot(graphics, x + 44, y + 37);
        PeStyle.slot(graphics, x + 114, y + 37);
        // 物品列表面板
        PeStyle.inset(graphics, x + 6, y + PANEL_TOP, BG_WIDTH - 12, PANEL_BOTTOM - PANEL_TOP, 0xFFA8A8A8);
        // 玩家背包槽位
        PeStyle.playerInventory(graphics, x + 8, y + INV_Y, y + HOTBAR_Y);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, PeStyle.TEXT_TITLE);
        // PKM 缓冲（标题同行右侧）
        String pkmText = Component.translatable("poketrade.gui.pkm").getString()
                + ": " + FORMATTER.format(menu.getPkm());
        graphics.drawString(this.font, pkmText, this.imageWidth - this.font.width(pkmText) - 8,
                this.titleLabelY, PeStyle.TEXT_PKM);
        // 槽位标签（在槽位上方，不与搜索框/槽位重叠）
        graphics.drawString(this.font, "投入", 44, 27, PeStyle.TEXT);
        graphics.drawString(this.font, "产出", 114, 27, PeStyle.TEXT);
        // 列表表头：显示当前凝聚目标
        if (!menu.getTarget().isEmpty()) {
            ItemStack targetStack = menu.getTarget();
            if (!targetStack.isEmpty()) {
                graphics.drawString(this.font, "目标:", LIST_LEFT, PANEL_TOP + 5, PeStyle.TEXT);
                graphics.drawString(this.font, targetStack.getHoverName().getString(),
                        LIST_LEFT + 30, PANEL_TOP + 5, PeStyle.TEXT_ACCENT);
            }
        } else {
            graphics.drawString(this.font, "目标: 点击下方物品选择", LIST_LEFT, PANEL_TOP + 5, PeStyle.TEXT_DIM);
        }
        // 玩家背包标签
        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, PeStyle.TEXT);
        // 物品列表
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
                        Component.literal(pkm).withStyle(net.minecraft.ChatFormatting.YELLOW),
                        Component.literal("点击设为凝聚目标").withStyle(net.minecraft.ChatFormatting.GRAY)
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
        int start = listStart();
        for (int i = 0; i < VISIBLE && start + i < filteredItems.size(); i++) {
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int x = this.leftPos + LIST_LEFT + col * CELL;
            int y = this.topPos + LIST_TOP + row * CELL;
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                ItemStack stack = filteredItems.get(start + i).stack();
                menu.setTarget(stack);
                PacketDistributor.sendToServer(new SetCondenserTargetPacket(stack));
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
