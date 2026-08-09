package com.pokeemc.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 等价交换（ProjectE）风格的 GUI 绘制工具。
 *
 * <p>直接把 PE 的整张 GUI 贴图铺到本模组窗口上会让装饰图案（转化环、凝聚器缓冲格、
 * 进度条）压在功能区后面，所以这里改为按 PE 的配色与边框结构重建面板，
 * 再从 PE 贴图上取一小块符文底纹平铺，既保持 PE 观感又不与槽位冲突。</p>
 *
 * <p>取色来自 PE 贴图实测值：面板底 198，高光 255，暗边 85，中缝 139。</p>
 */
public final class PeStyle {

    /** PE 转化桌贴图，用于取符文底纹 */
    private static final ResourceLocation TRANSMUTE_TEX =
            ResourceLocation.fromNamespaceAndPath("poketrade", "textures/gui/transmute.png");

    /** 贴图上一块可平铺、无硬边的淡符文区域（实测 seam 最小） */
    private static final int RUNE_U = 92;
    private static final int RUNE_V = 26;
    private static final int RUNE_SIZE = 16;

    public static final int PANEL = 0xFFC6C6C6;
    public static final int HILIGHT = 0xFFFFFFFF;
    public static final int SHADOW = 0xFF555555;
    public static final int OUTLINE = 0xFF000000;
    public static final int MID = 0xFF8B8B8B;

    public static final int TEXT = 0xFF404040;
    public static final int TEXT_TITLE = 0xFF202020;
    public static final int TEXT_PKM = 0xFF6B4E00;
    public static final int TEXT_DIM = 0xFF6E6E6E;
    public static final int TEXT_ACCENT = 0xFF1B6B84;

    private PeStyle() {
    }

    /** 铺满 PE 符文底纹（含面板底色），坐标为全局坐标 */
    public static void runeBackground(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        for (int dy = 0; dy < h; dy += RUNE_SIZE) {
            for (int dx = 0; dx < w; dx += RUNE_SIZE) {
                int tw = Math.min(RUNE_SIZE, w - dx);
                int th = Math.min(RUNE_SIZE, h - dy);
                g.blit(TRANSMUTE_TEX, x + dx, y + dy, RUNE_U, RUNE_V, tw, th);
            }
        }
    }

    /** PE 风格窗口外框：黑描边 + 左上高光 + 右下暗边 */
    public static void windowFrame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, OUTLINE);
        g.fill(x, y + h - 1, x + w, y + h, OUTLINE);
        g.fill(x, y, x + 1, y + h, OUTLINE);
        g.fill(x + w - 1, y, x + w, y + h, OUTLINE);

        g.fill(x + 1, y + 1, x + w - 1, y + 3, HILIGHT);
        g.fill(x + 1, y + 1, x + 3, y + h - 1, HILIGHT);
        g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, SHADOW);
        g.fill(x + w - 3, y + 1, x + w - 1, y + h - 1, SHADOW);
    }

    /** 下沉式内嵌面板（列表区/信息区），与原版槽位凹陷观感一致 */
    public static void inset(GuiGraphics g, int x, int y, int w, int h, int fill) {
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, SHADOW);
        g.fill(x, y, x + 1, y + h, SHADOW);
        g.fill(x, y + h - 1, x + w, y + h, HILIGHT);
        g.fill(x + w - 1, y, x + w, y + h, HILIGHT);
    }

    /** 原版风格槽位框：传入槽位左上角（即 Slot 坐标 -1） */
    public static void slot(GuiGraphics g, int slotX, int slotY) {
        inset(g, slotX - 1, slotY - 1, 18, 18, 0xFF8B8B8B);
    }

    /** 绘制一组 3x9 背包 + 1x9 快捷栏的槽位框 */
    public static void playerInventory(GuiGraphics g, int x, int invY, int hotbarY) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slot(g, x + col * 18, invY + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            slot(g, x + col * 18, hotbarY);
        }
    }

    // ================= Task 9/10 控件 =================

    /** 状态色 */
    public static final int TEXT_WARN = 0xFFA05A00;
    public static final int TEXT_OK = 0xFF2E7D32;
    public static final int TEXT_ERROR = 0xFFB3261E;
    public static final int TEXT_DANGER = 0xFFB3261E;
    /** 高亮/选中边框（PE 金褐色系） */
    public static final int SELECT_BORDER = 0xFF8B6B1B;
    /** 禁用态文本 */
    public static final int TEXT_DISABLED = 0xFFA0A0A0;

    /**
     * PE 风格按钮：左上高光 + 右下暗边的凸起按钮，按下时反转。
     * 返回按钮外框是否包含 (mx, my)。
     */
    public static boolean button(
            GuiGraphics g, Font font,
            int x, int y, int w, int h,
            String label, boolean enabled, boolean pressed, boolean hovered,
            int mx, int my) {
        int fill = enabled ? (hovered ? 0xFFD8D8D8 : 0xFFC6C6C6) : 0xFFB4B4B4;
        g.fill(x, y, x + w, y + h, fill);
        if (pressed) {
            g.fill(x, y, x + w, y + 1, SHADOW);
            g.fill(x, y, x + 1, y + h, SHADOW);
            g.fill(x, y + h - 1, x + w, y + h, HILIGHT);
            g.fill(x + w - 1, y, x + w, y + h, HILIGHT);
        } else {
            g.fill(x, y, x + w, y + 1, HILIGHT);
            g.fill(x, y, x + 1, y + h, HILIGHT);
            g.fill(x, y + h - 1, x + w, y + h, SHADOW);
            g.fill(x + w - 1, y, x + w, y + h, SHADOW);
        }
        int textColor = enabled ? TEXT_TITLE : TEXT_DISABLED;
        String safeLabel = label == null ? "" : font.plainSubstrByWidth(label, Math.max(4, w - 2));
        g.drawString(font, safeLabel, x + Math.max(1, w / 2 - font.width(safeLabel) / 2),
                y + Math.max(0, (h - 8) / 2), textColor);
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** PE 风格勾选框：小方框 + 对勾；返回是否命中。 */
    public static boolean checkbox(
            GuiGraphics g, int x, int y, boolean checked, boolean hovered, int mx, int my) {
        g.fill(x, y, x + 8, y + 8, 0xFFFFFFFF);
        g.fill(x, y, x + 8, y + 1, OUTLINE);
        g.fill(x, y, x + 1, y + 8, OUTLINE);
        g.fill(x, y + 7, x + 8, y + 8, OUTLINE);
        g.fill(x + 7, y, x + 8, y + 8, OUTLINE);
        if (hovered) {
            g.fill(x + 1, y + 1, x + 7, y + 7, 0xFFEDE3C8);
        }
        if (checked) {
            g.fill(x + 2, y + 2, x + 6, y + 6, 0xFF1B6B84);
        }
        return mx >= x && mx < x + 8 && my >= y && my < y + 8;
    }

    /** PE 风格分段按钮：一排等宽凸起小块，选中的填充更深的底并加框；返回是否命中。 */
    public static boolean segmented(
            GuiGraphics g, Font font,
            int x, int y, int w, int h,
            String label, boolean selected, boolean hovered, int mx, int my) {
        int fill = selected ? 0xFF8B8B8B : (hovered ? 0xFFD8D8D8 : 0xFFC6C6C6);
        g.fill(x, y, x + w, y + h, fill);
        if (selected) {
            g.fill(x, y, x + w, y + h, MID);
            g.fill(x, y, x + w, y + 1, OUTLINE);
            g.fill(x, y, x + 1, y + h, OUTLINE);
            g.fill(x, y + h - 1, x + w, y + h, OUTLINE);
            g.fill(x + w - 1, y, x + w, y + h, OUTLINE);
        } else {
            g.fill(x, y, x + w, y + 1, HILIGHT);
            g.fill(x, y, x + 1, y + h, HILIGHT);
            g.fill(x, y + h - 1, x + w, y + h, SHADOW);
            g.fill(x + w - 1, y, x + w, y + h, SHADOW);
        }
        int textColor = selected ? 0xFFFFFFFF : TEXT_TITLE;
        String safeLabel = label == null ? "" : font.plainSubstrByWidth(label, Math.max(4, w - 2));
        g.drawString(font, safeLabel, x + Math.max(1, w / 2 - font.width(safeLabel) / 2),
                y + Math.max(0, (h - 8) / 2), textColor);
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 内嵌列表右侧的滚动条：thumb 高度按可见比例计算；offset 为当前首行。 */
    public static void scrollbar(
            GuiGraphics g, int x, int y, int h, int totalRows, int visibleRows, int offset) {
        g.fill(x, y, x + 2, y + h, 0xFF8B8B8B);
        if (totalRows <= 0 || visibleRows <= 0 || totalRows <= visibleRows || h <= 0) {
            g.fill(x, y, x + 2, y + Math.max(0, h), 0xFFB0B0B0);
            return;
        }
        int maxOffset = Math.max(0, totalRows - visibleRows);
        int thumbH = Math.max(8, Math.min(h, h * Math.max(1, visibleRows) / Math.max(1, totalRows)));
        float ratio = maxOffset == 0 ? 0f : Math.min(1f, Math.max(0f, (float) offset / maxOffset));
        int thumbY = y + Math.max(0, (int) ((h - thumbH) * ratio));
        thumbY = Math.min(y + h - thumbH, thumbY);
        g.fill(x, y, x + 2, y + h, 0xFF6E6E6E);
        g.fill(x, y, x + 2, y + 2, HILIGHT);
        g.fill(x, y + h - 2, x + 2, y + h, SHADOW);
        g.fill(x, thumbY, x + 2, thumbY + thumbH, 0xFF8B6B1B);
    }

    /** 简短文本（带投影） */
    public static void text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color);
    }

    // ================= 屏幕空间文字重画配套（会话 #12 新增） =================

    /**
     * 按钮文字排版数据：label 已按可用宽度截断，textX/textY 为居中后的局部坐标，
     * color 依 enabled 取色。与 {@link #button} 内部公式逐字一致，避免复制取色逻辑。
     */
    public record ButtonText(String label, int textX, int textY, int color) {
    }

    /** 分段按钮文字排版数据（同 {@link ButtonText}，selected 用白字）。 */
    public record SegmentedText(String label, int textX, int textY, int color) {
    }

    /**
     * 按钮背景（凸起/按下/悬停）+ 命中检测；不绘制文字。
     * 供「几何矩阵内、文字矩阵外」的屏幕空间重画拆分使用：文字由
     * {@link #buttonText} 排版后由调用方另行以屏幕坐标绘制。
     */
    public static boolean buttonBg(
            GuiGraphics g, int x, int y, int w, int h,
            boolean enabled, boolean pressed, boolean hovered, int mx, int my) {
        int fill = enabled ? (hovered ? 0xFFD8D8D8 : 0xFFC6C6C6) : 0xFFB4B4B4;
        g.fill(x, y, x + w, y + h, fill);
        if (pressed) {
            g.fill(x, y, x + w, y + 1, SHADOW);
            g.fill(x, y, x + 1, y + h, SHADOW);
            g.fill(x, y + h - 1, x + w, y + h, HILIGHT);
            g.fill(x + w - 1, y, x + w, y + h, HILIGHT);
        } else {
            g.fill(x, y, x + w, y + 1, HILIGHT);
            g.fill(x, y, x + 1, y + h, HILIGHT);
            g.fill(x, y + h - 1, x + w, y + h, SHADOW);
            g.fill(x + w - 1, y, x + w, y + h, SHADOW);
        }
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 按钮文字排版（与 {@link #button} 内部公式一致）：label 截断 + 居中坐标 + enabled 取色。 */
    public static ButtonText buttonText(Font font, String label, boolean enabled,
            int x, int y, int w, int h) {
        String safeLabel = label == null ? "" : font.plainSubstrByWidth(label, Math.max(4, w - 2));
        return new ButtonText(safeLabel,
                x + Math.max(1, w / 2 - font.width(safeLabel) / 2),
                y + Math.max(0, (h - 8) / 2),
                enabled ? TEXT_TITLE : TEXT_DISABLED);
    }

    /**
     * 分段按钮背景 + 命中检测；不绘制文字。
     * 与 {@link #segmented} 的取色/描边逻辑一致，文字由 {@link #segmentedText} 另行排版。
     */
    public static boolean segmentedBg(
            GuiGraphics g, int x, int y, int w, int h,
            boolean selected, boolean hovered, int mx, int my) {
        int fill = selected ? 0xFF8B8B8B : (hovered ? 0xFFD8D8D8 : 0xFFC6C6C6);
        g.fill(x, y, x + w, y + h, fill);
        if (selected) {
            g.fill(x, y, x + w, y + h, MID);
            g.fill(x, y, x + w, y + 1, OUTLINE);
            g.fill(x, y, x + 1, y + h, OUTLINE);
            g.fill(x, y + h - 1, x + w, y + h, OUTLINE);
            g.fill(x + w - 1, y, x + w, y + h, OUTLINE);
        } else {
            g.fill(x, y, x + w, y + 1, HILIGHT);
            g.fill(x, y, x + 1, y + h, HILIGHT);
            g.fill(x, y + h - 1, x + w, y + h, SHADOW);
            g.fill(x + w - 1, y, x + w, y + h, SHADOW);
        }
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 分段按钮文字排版（与 {@link #segmented} 内部公式一致）：selected 用白字。 */
    public static SegmentedText segmentedText(Font font, String label, boolean selected,
            int x, int y, int w, int h) {
        String safeLabel = label == null ? "" : font.plainSubstrByWidth(label, Math.max(4, w - 2));
        return new SegmentedText(safeLabel,
                x + Math.max(1, w / 2 - font.width(safeLabel) / 2),
                y + Math.max(0, (h - 8) / 2),
                selected ? 0xFFFFFFFF : TEXT_TITLE);
    }
}
