# 右键菜单 Tooltip 中文叠印 Bug —— 独立代码分析包

> 用途：把交易所 UI 中「右键菜单 + 悬停 tooltip」全部相关代码与上下文单独提取，供其它 AI / 人工独立分析。
> 项目：NeoForge 21.1.248（MC 1.21.1），modid=`poketrade`，包 `com.pokeemc.client`。
> 文件：`src/main/java/com/pokeemc/client/ExchangeScreen.java`（全部代码来自此单文件，行号为 2026-08-10 当前版本）。
> 生成时间：2026-08-10。

---

## 0. 一句话问题

交易所 UI 里，**右键仓储槽位弹出的菜单**（7 项），悬停其中带说明的菜单项时，**tooltip 中文显示为「两段文字错位叠印的字墙，仅最右端尾部正常」**。反复修复未根治，需独立分析。

**先看 §1 渲染体系**——这是最容易导致误判的隐藏上下文，所有分析必须基于它。

---

## 1. 关键渲染体系（必须先理解）

### 1.1 坐标双轨制 + 自缩放矩阵

```java
// L342-348：屏幕坐标 -> 布局局部坐标
private int toLocalX(double mouseX) { return (int) Math.floor((mouseX - scaledOriginX) / uiScale); }
private int toLocalY(double mouseY) { return (int) Math.floor((mouseY - scaledOriginY) / uiScale); }

// L355-375：进入自适应缩放渲染
private void beginScaledRender(GuiGraphics g) {
    ...
    this.uiScale = UiScaling.fitScale(windowWidth, windowHeight, imageWidth, imageHeight); // 0.5 / 0.75 / 1.0
    this.scaledOriginX = Math.round((windowWidth - imageWidth * uiScale) / 2f);
    this.scaledOriginY = Math.round((windowHeight - imageHeight * uiScale) / 2f);
    var pose = g.pose();
    pose.pushPose();
    pose.translate(scaledOriginX, scaledOriginY, 0);
    pose.scale(uiScale, uiScale, 1);
    this.leftPos = 0; this.topPos = 0;
}
private void endScaledRender(GuiGraphics g) { g.pose().popPose(); }
```

- 界面内所有绘制/命中使用**局部坐标**（未缩放、以布局左上角为原点）。
- 真实屏幕显示 = `scaledOrigin + local * uiScale`。uiScale 非 1 时字形会被矩阵缩放。

### 1.2 z 层级与深度测试（本 Bug 的重灾区）

GUI 主投影为 `setOrtho(0.., 1000, 21000)` + modelview `translate(0,0,-11000)`，深度随 z 单调：
| 元素 | z | 深度≈ |
|---|---|---|
| 物品图标（renderItem 内部） | 150 | 0.49 |
| 普通界面文字/几何 | 0 | 0.50 |
| 弹窗 / 右键菜单（被「提升」） | 400 | 0.48 |

右键菜单因为要盖住物品图标（z=150），所以渲染时被整体提升：

```java
// render() L2136-2144：右键菜单在最顶层
g.flush();
RenderSystem.disableDepthTest();
g.pose().pushPose();
g.pose().translate(0.0F, 0.0F, 400.0F);
renderContextMenu(g, lmx, lmy);
g.pose().popPose();
g.flush();
RenderSystem.enableDepthTest();
```

**注意**：`drawContextMenuTooltip` 里的 `g.renderTooltip(...)` 是在这个 `z=400` pose **内**调用的。而原版 `GuiGraphics.renderTooltip` 内部会再 `pose().translate(tooltipX, tooltipY, 400.0F)` 画背景、用进入时的矩阵画文字——即**背景 z≈800（深度≈0.46）、文字 z≈400（深度≈0.48）**。深度差 + disableDepthTest 的组合行为请自行推演。

### 1.3 文字重放机制 recordText / drawPendingText

本 UI 大量文字不直接 `drawString`，而是**记录后延迟重放**：

```java
// L190-241
private enum TextLayer { MAIN, TOP }
private record TextDraw(TextLayer layer, String text, int x, int y, int color, boolean shadow) {}
private final java.util.List<TextDraw> pendingText = new java.util.ArrayList<>(64);

private void recordText(TextLayer layer, String text, int x, int y, int color) {
    recordText(layer, text, x, y, color, true);
}
private void recordText(TextLayer layer, String text, int x, int y, int color, boolean shadow) {
    pendingText.add(new TextDraw(layer, text, x, y, color, shadow));
}

private void drawPendingText(GuiGraphics g, TextLayer layer) {
    for (TextDraw d : pendingText) {
        if (d.layer() != layer) continue;
        g.drawString(this.font, d.text(), d.x(), d.y(), d.color(), d.shadow());
    }
}
```

render() 每帧生命周期（L2090-2161）：

```java
pendingText.clear();                 // L2092 每帧清空
beginScaledRender(g);                 // L2104 进入缩放矩阵
...（绘制几何、面板、标签等，含 recordText）...
renderContextMenu(g, lmx, lmy);       // L2141 右键菜单（z=400）
...
drawPendingText(g, TextLayer.MAIN);   // L2149 矩阵内 z=0 重放 MAIN 文字
if (sellPreview != null || contextMenu != null) {
    // L2151-2159：TOP 文字 z=400 + disableDepthTest 重放
    g.pose().translate(0,0,400);
    drawPendingText(g, TextLayer.TOP);
}
endScaledRender(g);                   // L2161
```

即：**右键菜单的「标签文字」走 recordText(TOP)，在 z=400 延迟重放；而「悬停 tooltip」走原版 renderTooltip，在 z=400 pose 内立即绘制**。二者渲染时序/层级关系请重点审查。

---

## 2. 右键菜单完整代码（ExchangeScreen.java）

### 2.1 状态字段

```java
// L177
private ContextMenu contextMenu;

// L243
private record ContextMenu(int x, int y, StorageItemSlot slot, StorageId storageId) {
}
```

### 2.2 右键触发（仓储槽位处理，L1663-1666）

```java
if (button == 1) {
    contextMenu = new ContextMenu(x, y, slot, id);   // 右键 -> 弹出菜单
    return;
}
```

（`x, y` 为仓储槽位的局部坐标，非鼠标坐标；`slot` 是被右键的仓储槽位，决定后续「同类」语义。）

### 2.3 mouseClicked 命中与分发（L1112-1127）

```java
public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
    ... 其它控件命中 ...
}
```

### 2.4 菜单矩形（L2566-2572）

```java
private ExchangeUiModel.Rect contextMenuRect() {
    int w = 118;
    int h = CONTEXT_MENU_ITEMS * 12; // 7 项 × 12px
    int x = Math.max(2, Math.min(contextMenu.x(), layout.width() - w - 2));
    int y = Math.max(2, Math.min(contextMenu.y(), layout.height() - h - 2));
    return new ExchangeUiModel.Rect(x, y, w, h);
}
```

### 2.5 菜单定义（L2574-2587）

```java
private static final int CONTEXT_MENU_ITEMS = 7;

/** 右键菜单项：标签 lang 键 + 悬停 tooltip lang 键（null = 无 tooltip）。 */
private static final String[][] CONTEXT_MENU_ENTRIES = {
        {"poketrade.exchange.pickup", null},
        {"poketrade.exchange.withdraw.to_inventory", null},
        {"poketrade.exchange.sell.toggle", null},
        {"poketrade.exchange.batch.withdraw", "poketrade.exchange.batch.withdraw.tip"},
        {"poketrade.exchange.batch.sell.storage", "poketrade.exchange.batch.sell.storage.tip"},
        {"poketrade.exchange.batch.sell.nearby", "poketrade.exchange.batch.sell.nearby.tip"},
        {"poketrade.exchange.batch.sell.whole", "poketrade.exchange.batch.sell.whole.tip"}
};
```

### 2.6 渲染菜单 + 悬停 tooltip（L2589-2614，**核心嫌疑区**）

```java
private void renderContextMenu(GuiGraphics g, int mx, int my) {
    if (contextMenu == null) return;
    ExchangeUiModel.Rect rect = contextMenuRect();
    g.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), 0xF0E8E0C8);   // 米黄底
    PeStyle.windowFrame(g, rect.x(), rect.y(), rect.width(), rect.height());
    for (int i = 0; i < CONTEXT_MENU_ITEMS; i++) {
        int rowY = rect.y() + i * 12;
        boolean hovered = rect.contains(mx, my);   // [CHANGED] 曾为 rect.contains(mx, rowY+6)，见 §4
        if (hovered) {
            g.fill(rect.x() + 1, rowY, rect.right() - 1, rowY + 12, 0x408B6B1B); // 行高亮
        }
        recordText(TextLayer.TOP, t(CONTEXT_MENU_ENTRIES[i][0]),
                rect.x() + 4, rowY + 2, PeStyle.TEXT);   // 菜单项标签 -> 延迟重放
        if (hovered && CONTEXT_MENU_ENTRIES[i][1] != null) {
            drawContextMenuTooltip(g, t(CONTEXT_MENU_ENTRIES[i][1]), mx, my); // 悬停 tooltip -> 立即 renderTooltip
        }
    }
}
```

### 2.7 悬停 tooltip（L2616-2635）

```java
private void drawContextMenuTooltip(GuiGraphics g, String text, int mx, int my) {
    int maxW = Math.max(40, layout.width() - 40) - 8;
    List<Component> lines = new ArrayList<>();
    for (String line : wrapText(text, maxW)) {
        lines.add(Component.literal(line));
    }
    System.out.println("[DBG-TIP] mx=" + mx + " my=" + my + " maxW=" + maxW
            + " lines=" + lines.size() + " text=" + text);   // 诊断日志
    if (!lines.isEmpty()) {
        g.renderTooltip(this.font, lines, java.util.Optional.empty(), mx, my);
    }
}
```

### 2.8 换行（L2637-2668）

```java
private List<String> wrapText(String text, int maxPx) {
    List<String> lines = new ArrayList<>();
    if (text == null || text.isEmpty() || maxPx <= 0) {
        return text == null || text.isEmpty() ? lines : List.of(text);
    }
    for (String seg : text.split("\n", -1)) {
        if (this.font.width(seg) <= maxPx) { lines.add(seg); continue; }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < seg.length(); ) {
            int cp = seg.codePointAt(i);
            int charLen = Character.charCount(cp);
            String ch = seg.substring(i, i + charLen);
            if (this.font.width(line.toString() + ch) > maxPx) {
                if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); continue; }
            }
            line.append(ch);
            i += charLen;
        }
        if (line.length() > 0) lines.add(line.toString());
    }
    return lines;
}
```

### 2.9 菜单项执行（L2670-2694）

```java
private void runContextOption(ContextMenu menu, int option) {
    selectStorageById(menu.storageId());
    switch (option) {
        case 0 -> pickUpFromStorage(menu.slot());
        case 1 -> withdrawFromStorage(menu.slot());
        case 2 -> { /* 待售 toggle */ }
        case 3 -> withdrawAllFromStorage(menu.slot().itemId());
        case 4 -> batchSellItemFromStorage(menu.slot().itemId());
        case 5 -> batchSellItemNearby(menu.slot().itemId());
        case 6 -> sellWholeStorage();
        default -> {}
    }
}
```

### 2.10 render() 中 tooltip 抑制逻辑（L2163-2176）

```java
// render() 末尾（endScaledRender 之后）：
if (contextMenu != null && contextMenuRect().contains(lmx, lmy)) {
    // 右键菜单开着且鼠标在菜单矩形内：抑制 this.renderTooltip（防仓储槽位/背包 tooltip 叠加）
    System.out.println("[DBG-TOOLTIP] ctx open, suppress base hoveredSlot="
            + (this.hoveredSlot == null ? "null" : this.hoveredSlot.getItem()));
} else {
    this.renderTooltip(g, lmx, lmy);   // 覆盖方法，内含手写仓储/目录/购物车 tooltip + super（基类 hoveredSlot）
}
```

---

## 3. lang 键值（zh_cn / en_us，均为纯文本，无 § / 控制字符 / 零宽字符，已逐码点校验）

| key | zh_cn | en_us |
|---|---|---|
| pickup | 拿起 | Pick up |
| withdraw.to_inventory | 取出到背包 | Withdraw to inventory |
| sell.toggle | 加入/移出待售 | Toggle sell queue |
| batch.withdraw | 批量取出同类 | Withdraw all (same item) |
| batch.withdraw.tip | 把整箱中所有同类物品取出并合并到背包，放不下的留在仓储 | Withdraw all same-item slots from this storage into your inventory (excess stays in storage) |
| batch.sell.storage | 批量出售同类(整箱) | Sell all (same item, storage) |
| batch.sell.storage.tip | 出售整箱中所有与右键槽位同类物品，超阈值需确认 | Sell every same-item slot in this storage; confirmation required above threshold |
| batch.sell.nearby | 批量出售同类(附近箱子) | Sell all (same item, nearby) |
| batch.sell.nearby.tip | 按当前扫描范围出售附近箱子中所有同类物品 | Sell every same-item slot in nearby storages within the current scan radius |
| batch.sell.whole | 一键出售(所有展开箱子) | Sell all expanded storages |
| batch.sell.whole.tip | 把所有展开箱子的全部可售物品串联出售，超阈值需确认 | Sell every sellable item in every expanded storage; confirmation required above threshold |

`t(key)` 实现（L2020-2027）：
```java
private static String t(String key) { return Component.translatable(key).getString(); }
private static String t(String key, Object... args) { return Component.translatable(key, args).getString(); }
```

---

## 4. 截图实证发现（视觉模型分析 run/screenshots/2026-08-10_00.22.32.png）

1. 右键菜单（米黄底 7 行）标签文字**清晰正常**，与 tooltip 不重叠。
2. tooltip 为**紫色背景、单层背景、一行文字带**，高约 42 原始像素（可能多行叠在一处）。
3. tooltip 内部**两段不同文字错位叠印**：左侧约 9/10 区域笔画互相穿插成字墙，**最右端约 1/10（尾部「仓储」二字）干净可读**。
4. 尾部「仓储」→ 对应 `batch.withdraw.tip`（尾字「仓储」），即悬停第 4 项「批量取出同类」时触发。
5. 其它界面：右栏「0/27格/总件数：0/总价 0」三行**行距 8px < 字形高 9px 上下重叠**（独立布局 bug，已修）。左栏箱子名「末□Dev 的末影箱」含**豆腐块（缺字形）** + 玩家名字母挤压；菜单项「/」「( )」显示为**斜体/花体**（疑 § 格式码或字体 fallback，未深究）。
6. run 环境 = dev 类路径加载最新代码；已排除 lang 文本污染与 Pixelmon 字体注入（pixelmon.jar 内无字体资源）。

---

## 5. 已尝试的修复（避免重复踩坑，附结论）

| 版本 | 改了什么 | 结果 |
|---|---|---|
| 旧版（#19/#20 前） | tooltip 自绘：`g.fill` 底框 + `recordText(TOP, 行, ...)` 延迟重放 | 叠印未根治 |
| 补丁 1/2 | wrapText 换行 / 去 plainSubstrByWidth 二次截断 | 未根治 |
| 补丁 5 | 自绘 → 原版 `g.renderTooltip(font, lines, empty, mx, my)` | **仍叠印**（截图实证） |
| 补丁 6 | 菜单开着且鼠标在菜单矩形内时**抑制 `this.renderTooltip`**（防槽位/背包 tooltip 叠加） | 玩家复测「仍乱」（或未重启，存疑） |
| 补丁 7 | 悬停命中 `rect.contains(mx, rowY+6)` → **`rect.contains(mx, my)`**（原来鼠标 x 一进菜单 7 行全 hovered，每行各画一个 tooltip 叠加） | 已编译安装，**待实测** |

**补丁 7 的关键推理**：旧命中 `rect.contains(mx, rowY+6)` 的 y 参数取「该行中心」，与鼠标 y 无关且每行中心必然在菜单矩形内 → 鼠标 x 进入菜单即 7 行全 hovered → **4 个 tooltip（i=3..6）在同一鼠标位置叠加**。这能解释「两段叠印字墙」以及**为什么自绘与 renderTooltip 两条独立管线都叠印**（共用此前置 bug）。

---

## 6. 留给独立分析的疑点（若补丁 7 仍未根治）

1. **第二段文字的来源仍未 100% 实证**：补丁 6 的抑制日志 `[DBG-TOOLTIP]` / `[DBG-TIP]` 输出到 run 环境 latest.log，可据此确认 `hoveredSlot` 是否为 null、tooltip 每帧调用几次、文本为何。
2. **原版 `renderTooltip` 在 z=400 + disableDepthTest + uiScale 缩放矩阵内的行为**：背景（内部 translate 400 → z≈800）与文字（进入时矩阵 z≈400）深度差 0.02，叠加 disableDepthTest 的全局状态，是否造成文字/背景双写或半透明混合？（本项目仓储/目录/购物车 tooltip 在 z=0、无 disableDepthTest 的矩阵内调用，**中文正常**——右键菜单 tooltip 是唯一在 z=400 + disableDepthTest 上下文里调用 renderTooltip 的。）
3. **字体 fallback**：菜单标签中文正常、tooltip 中文叠印，同为 `this.font`；若悬停命中修复后仍叠印，可临时把 tooltip 文本换成纯 ASCII 做二分定位（区分「字体 CJK 问题」vs「渲染管线问题」）。
4. 若需给独立分析者最小复现入口：`drawContextMenuTooltip`（§2.7）+ 它在 `renderContextMenu` 中被调用、而 `renderContextMenu` 在 render() L2141 的 `z=400 + disableDepthTest` pose 内——这是与「正常 tooltip」唯一的上下文差异。

---

## 7. 诊断日志（当前保留在代码中，dev 环境输出）

- `[DBG-TIP]`：drawContextMenuTooltip 每次调用的坐标/宽度/行数/文本。
- `[DBG-TOOLTIP]`：右键菜单打开时鼠标在菜单矩形内时 hoveredSlot 是否非 null。

两者输出到 `run/logs/latest.log`（gradle runClient 的 stdout）。
