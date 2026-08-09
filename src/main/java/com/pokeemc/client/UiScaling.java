package com.pokeemc.client;

/**
 * 自适应 UI 缩放：按当前窗口（GUI 缩放后的逻辑像素）从预设档位
 * {1.0, 0.75, 0.5} 中选取能完整容纳界面（含 12px 边距）的最大档位。
 *
 * <p>屏幕渲染时把整块界面按该比例等比缩小并居中；鼠标坐标按同一比例
 * 反变换回局部坐标，保证点击/悬停/滚轮与绘制严格一致。</p>
 */
public final class UiScaling {

    /** 允许的缩放档位（从大到小尝试）。 */
    public static final float[] PRESETS = {1.0f, 0.75f, 0.5f};

    /** 界面与窗口边缘至少保留的间距（GUI 逻辑像素）。 */
    public static final int MARGIN = 12;

    private UiScaling() {
    }

    /** 返回能放下 {@code imageWidth x imageHeight} 界面的最大预设档位。 */
    public static float fitScale(int windowWidth, int windowHeight, int imageWidth, int imageHeight) {
        for (float scale : PRESETS) {
            if (imageWidth * scale <= windowWidth - MARGIN
                    && imageHeight * scale <= windowHeight - MARGIN) {
                return scale;
            }
        }
        return PRESETS[PRESETS.length - 1];
    }
}
