package com.pokeemc.client;

import com.pokeemc.client.ExchangeUiModel.Layout;
import com.pokeemc.client.ExchangeUiModel.Rect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交易所屏幕几何布局测试：所有收起组合下，中央目录、左右栏、控件与预览模态
 * 都必须落在窗口内且互不重叠。屏幕渲染/点击统一消费 {@link ExchangeUiModel.Layout}，
 * 本测试即以该布局为唯一被测对象（不存在影子拷贝）。
 */
class ExchangeLayoutTest {

    private static final List<Layout> ALL_STATES = List.of(
            Layout.layoutFor(false, false),
            Layout.layoutFor(true, false),
            Layout.layoutFor(false, true),
            Layout.layoutFor(true, true));

    @Test
    void layoutForIsTheSingleGeometrySourceForEveryCollapseState() {
        // 屏幕将 layoutFor 的结果作为唯一几何来源，收起组合的窗口宽度需逐档回收边栏空间
        assertEquals(470, Layout.layoutFor(false, false).width());
        assertEquals(328, Layout.layoutFor(true, false).width());
        assertEquals(338, Layout.layoutFor(false, true).width());
        assertEquals(196, Layout.layoutFor(true, true).width());
    }

    @Test
    void middleColumnStaysInsideWindowAndVisibleInEveryCollapseState() {
        for (Layout layout : ALL_STATES) {
            assertTrue(layout.contains(layout.middle()),
                    "middle column outside window: " + layout);
            assertTrue(layout.middle().width() > 0);
            assertTrue(layout.middle().x() >= 0 && layout.middle().right() <= layout.width());
        }
    }

    @Test
    void visibleColumnsNeverOverlapEachOther() {
        for (Layout layout : ALL_STATES) {
            assertFalse(layout.leftVisible() && layout.left().overlaps(layout.middle()),
                    "left overlaps middle in " + layout);
            assertFalse(layout.rightVisible() && layout.middle().overlaps(layout.right()),
                    "middle overlaps right in " + layout);
            assertFalse(layout.leftVisible() && layout.rightVisible()
                    && layout.left().overlaps(layout.right()));
        }
    }

    @Test
    void everyControlRectLivesInsideItsWindow() {
        for (Layout layout : ALL_STATES) {
            for (Rect rect : layout.controls().values()) {
                // 收起列的控件不渲染、不点击，允许停留在展开位坐标（窗口外）；
                // 屏幕以 leftVisible/rightVisible 门控，因此只校验可见控件的边界
                boolean hiddenByCollapse = (!layout.leftVisible() && rect.x() < layout.middle().x())
                        || (!layout.rightVisible() && rect.x() >= layout.middle().right());
                if (!hiddenByCollapse) {
                    assertTrue(layout.contains(rect),
                            "control " + rect + " outside window " + layout);
                }
            }
        }
    }

    @Test
    void previewModalGeometryLeavesRoomForSourceInfoAndScrollableRows() {
        for (Layout layout : ALL_STATES) {
            Rect modal = layout.previewModal();
            Rect lines = layout.previewLines();
            Rect cancel = layout.previewCancel();
            Rect confirm = layout.previewConfirm();
            assertTrue(layout.contains(modal));
            assertTrue(modal.contains(lines));
            assertTrue(modal.contains(cancel));
            assertTrue(modal.contains(confirm));
            // 头部区域（标题/来源/仓储信息）要能放进 6 行物品区之上
            assertTrue(modal.y() + 38 <= lines.y(),
                    "no room for source/storage info lines above rows");
            // 行区必须在按钮区之上结束，给总计/跳过/截断留位
            assertTrue(lines.bottom() < cancel.y() && lines.bottom() < confirm.y());
        }
    }

    @Test
    void radiusInputCategoryAndFilterControlsSitInsideLeftColumnTopRows() {
        Layout layout = Layout.expanded();
        Rect radiusInput = layout.radiusInput();
        Rect category = layout.slotCategory();
        Rect filter = layout.filterSell();
        assertTrue(radiusInput.x() >= layout.left().x()
                && radiusInput.right() <= layout.left().right());
        assertTrue(category.x() >= layout.left().x()
                && category.right() <= layout.left().right());
        assertTrue(filter.x() >= layout.left().x()
                && filter.right() <= layout.left().right());
        assertFalse(category.overlaps(filter));
        assertEquals(layout.left().y() + 2, radiusInput.y());
        assertEquals(layout.left().y() + 28, category.y());
        assertEquals(layout.left().y() + 28, filter.y());
    }
}
