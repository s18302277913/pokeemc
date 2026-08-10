# PokeEMC 模组开发日志

> 本日志自会话 #23（2026-08-10）从零开始记录。
> 此前日志与持久记忆已按用户要求删除，仅保留可复现的代码事实与运行日志（run/logs/latest.log）。

---

## 会话 #23（2026-08-10 07:0x–07:4x）——「分类 unknown」根因调查与修复

### 1. 问题现象（用户报告 + 截图）

- 用户报告：交易所物品分类不全、分类显示默认英文/unknown。左侧仓储分类与交易所 tooltip 均受影响。
- 最新两张截图（run/screenshots/2026-08-10_07.13.34.png / 07.13.44.png）实证：
  - 钻石靴子 tooltip 显示 `[unknown] minecraft`（分类字段 = `unknown`）。
  - 分类筛选弹窗仅列出：全部 / 建筑方块 / 自然方块 / 精灵球 / unknown —— **无「战斗用品」**。
- 触发条件：`./gradlew runClient`（dev 环境，当前源码；run/mods 仅有 pixelmon.jar）。

### 2. 关键代码事实（预先核实）

- 分类解析链：`ExchangePriceService.categoryOf(TradeItemId)`：
  1. `categoryOverrides` 覆盖表（数据驱动 `data/poketrade/exchange/categories.json`）
  2. 球类兜底 `poketrade.category.pokeballs`（itemId 含 `#`）
  3. `computeCategory(ItemStack)` 遍历全部 `CATEGORY` tab 的 `getDisplayItems()`，返回 tab 标题翻译键（如 `itemGroup.combat`），否则 `"unknown"`
- `categories.json` 含 478 条映射，`minecraft:diamond_boots → itemGroup.combat`。
- 运行日志时间线（07:12）：`exchange configs reloaded` 出现在三次 2569 条 rebuild 后；最终 3217 条 rebuild（Server thread）在配方计算完成后。

### 3. 调查过程与证据

| 步骤 | 操作 | 结论 |
|---|---|---|
| 1 | 核对源码 categories.json | diamond_boots→combat 正确，映射无问题 |
| 2 | 读 ExchangeConfigLoader | 监听 `"poketrade/exchange"` 目录，匹配 `*.json` 文件名 |
| 3 | 反编译 neoforge-21.1.248-sources.jar 的 `SimpleJsonResourceReloadListener.scanDirectory` | **key 经 `FileToIdConverter` 去掉目录前缀与 `.json` 后缀**：`data/poketrade/exchange/categories.json` → key `poketrade:categories` |
| 4 | 对比 PkmDataLoader（监听 `"pkm"`） | 目录参数应为**路径部分**（不含命名空间）；`"poketrade/exchange"` 会扫描 `data/<ns>/poketrade/exchange/`（多一层目录）→ **resources 恒空** |
| 5 | runServer 加临时日志实证 | `forEach` 空 → `applyCategoryOverrides` 从未被调用 → override 恒 `Map.of()` |
| 6 | runServer 实证 computeCategory | 仅前 3 个 tab（buildingBlocks=627 / coloredBlocks=470 / natural=377）有内容，**combat 及后续全部 size=0** → diamond_boots NOT FOUND → unknown |
| 7 | 反编译 `tryRebuildTabContents` | `CACHED_PARAMETERS` 相同则返回 false 不重建；首次 `buildAllTabContents` 遍历全部 CATEGORY tab 构建 |
| 8 | 改造 ensureTabsBuilt 用 `server.registryAccess()` | **tabs 完整构建**：combat displayItems=214，`diamond_boots → itemGroup.combat` ✓ |
| 9 | 修复 ExchangeConfigLoader 目录参数 | `applied 478 category overrides` ✓（categories.json 生效） |

### 4. 根因（双根因，缺一不可）

**根因 1：ExchangeConfigLoader 目录参数错误 → 分类覆盖表从未加载**

`ExchangeConfigLoader` 构造 `super(new Gson(), "poketrade/exchange")`。
`SimpleJsonResourceReloadListener` 按**各命名空间**扫描 `data/<ns>/<directory>/`，key 经 `FileToIdConverter` 生成。
- 实际文件：`data/poketrade/exchange/categories.json`（ns=poketrade，路径 exchange/）
- 监听器找的是：`data/<ns>/poketrade/exchange/*.json`（多一层 `poketrade/`）

→ resources 恒空 → `applyCategoryOverrides`、数据包 `prices.json` 覆盖、`sell_rules.json` 全部静默失效。
（`prices.json` 内置默认值另经 `PokeEMC.commonSetup` classpath 直读，故大师球 500 万仍生效。）

**根因 2：服务端 tabs 构建中断 → computeCategory 对多数物品返回 unknown**

`ensureTabsBuilt` 传入 `RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)`（仅静态注册表，**不含数据包动态注册表 / tag**）。
`tryRebuildTabContents` → `buildAllTabContents` 遍历全部 CATEGORY tab，tag 驱动的 tab（functional/redstone/combat 等）生成时 `holders().getTag(...)` 抛异常 → **forEach 中断** → 仅前 3 个静态 tab 有内容，后续全部 size=0。
且 `tryRebuildTabContents` 的 `CACHED_PARAMETERS` 使**相同参数重试返回 false 不再重建**，旧 `tabsRetryPending` 复位机制因此无效。

> 注：会话 #16/#22 的「长键 vs 短键」修复（`naturalBlocks`→`natural` 等，1.20 长键在 1.21.1 语言文件不存在）是**有效的部分修复**（客户端本地化兜底），但不是本次 `[unknown]` 的根因。

### 5. 修复

- `src/main/java/com/pokeemc/exchange/price/ExchangeConfigLoader.java`
  - 构造目录参数 `"poketrade/exchange"` → `"exchange"`
  - apply() 匹配改为去 `.json` 后缀（`"prices"` / `"sell_rules"` / `"categories"`），并校验命名空间 `poketrade`
- `src/main/java/com/pokeemc/exchange/price/ExchangePriceService.java`
  - `ensureTabsBuilt()`：优先用 `ServerLifecycleHooks.getCurrentServer().registryAccess()`（含数据包 tag），无服务器时回退静态注册表
- `ExchangePriceService.applyCategoryOverrides()` 增加正式 info 日志（覆盖数），便于确认数据驱动分类生效

### 6. 验证

- `./gradlew build`：编译通过，单测全绿（702 tests，0 failures）
- `./gradlew runServer` 实证：
  - `applied 478 category overrides` ✓
  - `tryRebuildTabContents` 成功，combat displayItems=214
  - `diamond_boots → itemGroup.combat` ✓
  - 最终 `exchange catalog rebuilt with 3217 entries` ✓
- `./gradlew jar` 重新打包（08:04，1,050,946 B，自包含 jarJar api）
- **安装到 poke（PCL 启动器版）**：`cp build/libs/poketrade-1.0.0.jar → D:/PCL 正式版 2.12.2/.minecraft/versions/poke/mods/poketrade-1.0.0.jar`，覆盖旧版（04:50，1,036,158 B）；sha256 校验一致（`6bdb7e81…`）。mods 目录另含 `pixelmon.jar`（394 MB，未变动）。

### 7. ADR

**ADR-140：ExchangeConfigLoader 数据目录参数应为路径部分（不含命名空间）**
- 状态：已采纳（会话 #23）
- 背景：SimpleJsonResourceReloadListener 的 directory 参数是相对 `data/<ns>/` 的路径；`FileToIdConverter` 自动按命名空间扫描并剥离目录前缀与 `.json` 后缀生成 key。
- 决策：`super(new Gson(), "exchange")`；apply() 按 key 文件名（无后缀）匹配，并校验 `poketrade` 命名空间。
- 后果：categories.json / 数据包 prices.json 覆盖 / sell_rules.json 恢复加载；478 条分类覆盖生效。

**ADR-141：服务端构建创造 tab 必须用 server.registryAccess()（含数据包 tag）**
- 状态：已采纳（会话 #23）
- 背景：`RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)` 无数据包动态注册表，tag 驱动的 tab 生成抛异常中断构建。
- 决策：`ensureTabsBuilt()` 优先取 `ServerLifecycleHooks.getCurrentServer().registryAccess()`。
- 后果：服务端 tabs 完整构建，`computeCategory` 对所有 vanilla 物品正确归类；`CACHED_PARAMETERS` 缓存因 registry 参数变化而正确失效重建。
- 注意：纯 JVM 单测（无服务器）仍回退静态注册表（computeCategory 依赖注册表，测试环境本就不可用）。

### 8. 遗留 / 后续

- 分类覆盖仅 478 条；PKM 兜底约 4342 个值，未覆盖物品依赖 `computeCategory`（现 tabs 已完整构建，绝大多数 vanilla 物品可正确归类）。如需更高覆盖，可扩展 `gen_categories.py` 重生成。
- runClient 实机复核截图（打开交易所 tooltip + 分类筛选弹窗，确认「战斗用品」出现、`[unknown]` 消失）。
- Pixelmon / TCG 模组 tab 内容在服务端构建中的完整性未逐一核验（displayItems 取决于各模组构建逻辑）。

---

## 会话 #24（2026-08-10 08:20）——仓储列表箱子名跑马灯修复（滚动周期 + z 轴穿模）

### 1. 本次需求

用户报告：交易所左栏仓储列表的箱子名称滚动方式异常，期望「从右到左滚动到头后，再从右边出来」；且名称文字 z 轴异常，覆盖了旁边的展开按钮。

### 2. 根因（双缺陷，独立）

**缺陷 1：marqueeX 周期缺「进入行程」→ 滚动「没到头」就跳回**

- `ExchangeUiModel.marqueeX`：`period = nameWidth + gap`。文字左端从 `rightEdge` 滚到 `rightEdge-(nameWidth+gap-1)`，**右端停在 `rightEdge-gap+1` —— 从未越过可见区左缘（尾巴留在区内）**，phase 归零即瞬间跳回右侧。
- 数值例（nameW=120、可见区 [0,100]、rightEdge=85）：phase=143 时文字右端=62，还有 62px 留在可见区就跳回 mx=85。
- 期望行为：文字完整滚出左缘（右端 < leftEdge）→ 空 gap → 再从右缘进入。

**缺陷 2：表头展开按钮箭头 recordButton(MAIN) z=160 浮出 scissor → 叠印穿模**

- 名称/末影徽标已内联（z≈0、受手风琴 scissor 裁剪，会话 #21-G Bug 4）。但展开按钮箭头仍 `recordButton(MAIN)` → `drawPendingText` 在 `disableScissor` **之后**以 z=160 重放，**不受裁剪**。
- 手风琴滚动时，滚出裁剪区的表头箭头文字残留在屏上，叠印在下方箱子的表头/展开按钮/网格上——即用户所见「名称文字覆盖了旁边的展开」。
- 同类隐患：展开箱网格空态提示（`recordText(MAIN)`）同样浮出裁剪区。

### 3. 修复（[CHANGED] 留痕）

- `ExchangeUiModel.marqueeX`：签名加 `leftEdge`，`travel = (rightEdge-leftEdge)+nameWidth+gap`；更新 javadoc。
- `ExchangeScreen.renderAccordionEntry` 表头：调用补 `leftEdge=tx`（名称起点，末影箱含徽标偏移）。
- `ExchangeScreen` 结算预览行：调用补 `leftEdge=lines.x()`（与表头同款完整行程）。
- `ExchangeScreen.renderAccordionEntry` 展开按钮箭头：`recordButton(MAIN)` → 内联 `g.drawString`（经 `PeStyle.buttonText` 排版；与名称/按钮背景同层 z≈0、顺序在后，受 scissor 裁剪）。
- 展开箱网格空态提示：`recordText(MAIN)` → 内联 `g.drawString`。

### 4. 验证

- `./gradlew build`：编译通过，**702 tests / 0 failures**。
- `ExchangeUiModelTest.marqueeXScrollsLeftThenCyclesAround` 更新为新签名，新增「右端越过 leftEdge 才算滚到头」断言（now=12000 → mx=-20 右端恰在左缘；now=12100 → mx=-21 完全移出）。
- `./gradlew jar` 重新打包（08:19，1,051,003 B）；安装到 PCL poke（覆盖 08:05 旧版 1,050,946 B），sha256 一致 `c297905a…`。

### 5. ADR

**ADR-142：marqueeX 周期须含进入行程（rightEdge-leftEdge）**
- 状态：已采纳（会话 #24）
- 背景：旧周期 `nameWidth+gap` 假定 rightEdge 即左缘，未计入文字从右缘滚到左缘的行程，导致文字右端停在 `rightEdge-gap+1` 处未滚出即跳回。
- 决策：`travel = (rightEdge-leftEdge)+nameWidth+gap`；调用方传入可见区左缘。
- 后果：文字完整滚出左缘后经 gap 空档再从右缘进入，符合「滚到头后从右边出来」预期；表头/结算预览两处调用点同步。

**ADR-143：表头裁剪区内元素一律内联绘制（z≈0、随 scissor 裁剪）**
- 状态：已采纳（会话 #24）
- 背景：手风琴裁剪区内元素若经 `recordText`/`recordButton`（MAIN z=160）在 `disableScissor` 后重放，将脱离裁剪区浮出叠印。会话 #21-G Bug 4 已内联名称/徽标，箭头与空态遗漏。
- 决策：展开按钮箭头、网格空态提示改内联 `g.drawString`。
- 后果：滚动时表头内全部元素被 scissor 正确裁剪，不再与下方元素叠印；箭头仍在按钮背景之上（内联后绘）。

### 6. 遗留 / 后续

- 实机复核：PCL 启动 poke 打开交易所，观察超宽长名箱子（如「Dev 的箱子①」）：
  1) 名称从右缘滚入 → 完整滚出左缘 → 空档后从右缘重新进入；
  2) 滚动列表使表头越出裁剪区，确认箭头/名称不再残留叠印。
- 手风琴裁剪区内的 `recordText(MAIN)` 元素已全量梳理（仅箭头与空态两处），未来新增一律内联。
- 名称滚动速度 speedMs=40ms/px、gap=24px 维持既有值；如需调节可改 `marqueeX` 调用参数。

### 6b. 速度微调（会话 #24b，08:23）

- 需求：滚动速度太快要再慢一些。
- 改动：`ExchangeScreen` 两处 `marqueeX` 调用 `speedMs 40→60`（每像素耗时更长、约 1.5 倍慢）：表头箱子名 + 结算预览行（同步，保持观感一致）。gap=24 不变。
- 验证：`./gradlew build` ✅ 702 tests / 0 failures；`./gradlew jar` 重新打包（08:23，1,051,001 B），安装至 PCL poke（sha256 `b06244d0…` 一致）。
- 后续若仍觉得快/慢，可改 `marqueeX` 调用处的 `60`（越大越慢）。

---

## 会话 #25（2026-08-10 08:27）——钱包余额缩写（1k/1m）+ 悬停显示完整值

### 1. 本次需求

物品栏/界面的钱包数字到大额会撑出显示区域穿模/截断：改为 `1k / 1m` 缩写显示，指针悬停时用 tooltip 显示具体数字。

### 2. 改动（[CHANGED] 留痕）

- **`ExchangeUiModel` 新增 `formatWallet(long)`**（会话 #25）：钱包余额专用缩写。≥1k/1m/1b/1t 用一位小数缩写并去尾零（1000→1k、1500→1.5k、12345→12.3k、5,000,000→5m）；负数带 `-`；`<1000` 原样。**`formatAmount`（完整千分位）保持不变**——它是交易所所有金额的统一入口（价格/小计/总价），仅钱包显示切换缩写，避免其他金额语义变化。
- **`ExchangeScreen.renderLabels` 中栏钱包**（约 2956 行）：`formatAmount(bal)` → `formatWallet(bal)`；新增悬停检测 `layout.wallet().contains(x, y)` 时 `g.renderTooltip` 显示完整 `formatAmount(bal)`。
- **`TransmutationTableScreen` 顶部 PKM 余额**（renderLabels）：`FORMATTER.format(pkm)` → `formatWallet(pkm)`；记录余额文本局部矩形到字段 `pkmBalanceBox`，`renderTooltip` 悬停时显示完整千分位（`FORMATTER.format`）。`renderTooltip` 收全局坐标，比较时补 `leftPos/topPos`。
- **测试**：`ExchangeUiModelTest.formatWalletAbbreviatesLargeBalances` 新增 14 断言。

### 3. 验证

- `./gradlew build`：编译通过，**703 tests / 0 failures**（+1）。
- `./gradlew jar` 重新打包（08:26，1,051,803 B），安装至 PCL poke（sha256 `745f950b…` 一致）。

### 4. ADR

**ADR-144：钱包余额显示用独立缩写函数，不改 formatAmount 全局语义**
- 状态：已采纳（会话 #25）
- 背景：`formatAmount` 是交易所所有金额统一入口；若全局改缩写，价格/小计/总价全部变 k/m，丢失精度且不符合「仅钱包单独缩写」的诉求。
- 决策：新增 `formatWallet`（k/m/b/t、一位小数去尾零）；`formatAmount` 保留完整千分位并兼作悬停 tooltip 的完整值来源。
- 后果：钱包大数不再撑出区域穿模；其余金额显示不变；悬停可见精确值。

### 5. 遗留 / 后续

- 实机复核：交易所中栏钱包（余额 ≥1k/1m 时显示缩写，悬停出完整值）；转化桌顶部 PKM 余额（同样缩写+悬停）。
- 若后续想给商品价格也支持缩写（避免超长价格穿模），可引入统一的「宽度自适应：超宽缩写/悬停全值」工具，两个显示点复用；当前钱包为先例。
- `formatWallet` 未处理 `Long.MIN_VALUE` 的取负溢出（钱包余额现实中不可达，忽略）。

---

## 会话 #24c（2026-08-10 08:40）——跑马灯改「头追尾」传送带 + 徽标/按钮 z 轴修复

### 1. 本次需求（用户反馈 + poke 截图 2026-08-10_08.31.56.png 实证）

- 滚动动画改为「头追尾」模式：文字尾部滚出左缘时，头部立即从右缘进入，全程连续、无空档、无瞬移。
- 穿模：滚动文字盖住了旁边的展开按钮；滚到左端时盖住末影箱「末」字徽标。用户明确要求调整 z 轴。

### 2. 根因

**缺陷 1：marqueeX 为「单副本 + gap 空档」循环**
- 旧实现单副本从右缘滚到完全出左缘，再空走 gap=24px 才从右缘重新出现——空档期间右侧无文字，观感断续。
- 且单副本滚到右端时左端可达 `rightEdge=header.right()-15`（展开按钮左缘），文字覆盖按钮区域；按钮背景仅 13px 宽、滚动文字从按钮两侧露出来，视觉上「盖住箭头」。

**缺陷 2：末影箱「末」字徽标先于名称绘制（z 轴低）**
- `renderAccordionEntry` 原顺序：选中高亮 → 徽标背景+「末」字 → 名称 → 按钮背景 → 箭头。名称滚动到左端时**画在徽标之上**，「末」字被盖住。

### 3. 修复（[CHANGED] 留痕）

- **`ExchangeUiModel.marqueeX` 改为「头追尾」传送带基准算法**：`period = max(1, nameWidth+gap)`（副本节距），`phase = (nowMillis/speedMs) % period`，返回 `rightEdge - period - phase`（基准副本左端，右缘向左一个节距处）。新增 `marqueePeriod(nameWidth, gap)` 返回节距。
- **调用方循环绘制所有与可见区相交的副本**（间距 = period）：尾部出左缘时下一副本已从右缘进入，任意相位可见区内恒有文字在流动——头追尾、无空档、无相位归零瞬移。两处同步：
  - `ExchangeScreen.renderAccordionEntry` 表头（名称传送带循环，`gap=24`、speedMs 60 维持）
  - `ExchangeScreen` 结算预览行（`recordText(TOP)` 多副本循环；价格在下方后记录、z 顺序在后，滚到价格左缘的文字被价格盖住）
- **z 轴调整（绘制顺序）**：表头名称先画（底层）→ **末影箱徽标移到名称之后**（背景+「末」字后绘，盖住滚过左端的文字）→ 展开按钮背景+箭头最后（盖住滚过右端的文字）。徽标仍内联 `g.drawString`（z≈0、随手风琴 scissor 裁剪）。
- **测试**：`marqueeXScrollsLeftThenCyclesAround` 重写为 `marqueeXHeadChasesTailConveyorBelt`（节距/基准/周期/衔接断言），新增 `marqueeConveyorAlwaysCoversViewport`（0..2 节距遍历任意相位，可见区恒有副本相交）。

### 4. 验证

- `./gradlew build`：编译通过，**704 tests / 0 failures**（-1 +2）。
- `./gradlew jar` 重新打包（08:40，1,052,095 B），安装至 PCL poke（sha256 `dd32b6dd…` 一致）。

### 5. ADR

**ADR-145：跑马灯用「多副本传送带」实现头追尾，纯函数只算基准左端**
- 状态：已采纳（会话 #24c）
- 背景：单副本+gap 循环有空档、滚到两端盖住徽标/箭头。多副本传送带让文字流永远连续；副本间距 = nameWidth+gap，任何时刻可见区内恒有副本相交（period ≤ 可见区宽 + nameWidth 时严格成立）。
- 决策：`marqueeX` 返回基准副本左端，调用方以 `marqueePeriod` 步进循环绘制所有相交副本；`gap` 语义从「滚动后空档」改为「相邻副本间距」。
- 后果：滚动连续无缝；代价是同一时刻可见区内可能出现 2 个同名字副本（仅当 period ≤ 可见区宽，即超宽判定边界附近，实际滚动场景 nameWidth>avail 时单副本）。

**ADR-146：表头固定元素（徽标/展开按钮）必须后绘，滚动文字为底层**
- 状态：已采纳（会话 #24c）
- 背景：末影箱徽标先画导致名称滚过时盖住「末」字；按钮虽后绘但背景仅 13px，文字从两侧露出。
- 决策：绘制顺序 = 选中高亮 → 名称（滚动/静态）→ 徽标 → 按钮背景 → 箭头；徽标位置独立用 `header.x()+2`，名称 leftEdge 仍 `header.x()+12`。
- 后果：滚动文字从徽标/按钮下方滚过，两端固定元素永远清晰；`mx` 最小可达 `leftEdge-nameWidth` 外，被 scissor 裁剪。

### 6. 遗留 / 后续

- 实机复核：PCL 启动 poke 打开交易所——
  1) 超宽长名箱子持续连续滚动，尾部出左缘瞬间头部从右缘进入（无空档/无跳变）；
  2) 文字滚到左端从「末」字徽标下方穿过（徽标清晰），滚到右端从展开按钮下方穿过（箭头清晰）；
  3) 结算预览行同款连续滚动，价格不受影响。
- 若实机仍觉滚动快/慢，改两处 `marqueeX` 调用的 `60`（越大越慢）。

---

## 会话 #24d（2026-08-10 08:56）——表头名称 scissor 收窄（区域互斥，彻底解决名称覆盖展开按钮）

### 1. 本次需求（用户反馈）

会话 #24c 部署后用户仍反馈：**箱子的名称覆盖了仓储列表右边的展开按钮**，要求调整 z 轴。

### 2. 根因（#24c 的 z 轴方案不彻底）

- 传送带模式下文字副本左端 `xx < rightEdge`（按钮左缘），但**文字右端 `xx+nameW` 可越过 `rightEdge` 进入按钮区域**（最多 70px+，被外层 scissor 裁在 `layout.left().right()`）。
- 展开按钮背景仅 13px 宽，后绘只能盖住 13px 内的文字；文字主体在按钮背景两侧露出，视觉上仍是「名称盖住按钮」。即：**后绘 z 轴无法遮住比按钮宽得多的滚动文字流**。

### 3. 修复（[CHANGED] 留痕）

- **`ExchangeScreen.renderAccordionEntry` 表头：名称文字单独收窄 scissor 到 `[tx, rightEdge]`**（左=名称起点 `header.x()+2(+10)`，右=展开按钮左缘 `header.right()-15`），即：
  ```java
  int rightEdge = header.right() - 15;
  g.enableScissor(screenX(tx), screenY(header.y()),
          screenX(rightEdge) - screenX(tx), screenY(header.bottom()) - screenY(header.y()));
  // 滚动传送带 / 静态名称 绘制……
  g.disableScissor();
  ```
- 名称 scissor 与外层手风琴 scissor 叠加，**滚动文字两端在按钮/徽标区域外被硬裁**：右端到按钮左缘即消失（文字「钻进按钮背后」），左端到名称起点即消失——**区域互斥，比 z 轴更彻底**。
- 末影箱「末」徽标、展开按钮（背景+箭头）都在名称 scissor 之外独立绘制，**永不与文字重叠**。
- ⚠️ 名称 scissor 的第三、四参数当时误按「宽高」传了差值 `screenX(rightEdge)-screenX(tx)` —— **`enableScissor` 实为 (x1,y1,x2,y2) 右/底边界语义**，差值当右/底边界导致 scissor 负宽高 → 名称整体被裁不显示（见会话 #24e 修复）。

### 4. 验证

- `./gradlew build`：编译通过，**704 tests / 0 failures**（无算法变更，仅渲染路径；marqueeX 单测不变）。
- `./gradlew jar` 重新打包（08:55，1,052,142 B），安装至 PCL poke（sha256 `f6b8608e…` 一致）。

### 5. ADR

**ADR-147：表头名称用收窄 scissor 与按钮/徽标区域互斥，替代「后绘盖住」**
- 状态：已采纳（会话 #24d，修订 ADR-146）
- 背景：ADR-146 的后绘 z 轴只能盖住按钮背景 13px 内的文字，滚动文字从两侧露出仍像「盖住按钮」；且徽标后绘在 `[header.x(),tx]` 边缘有 2px 缝隙。
- 决策：名称绘制前 `enableScissor([tx,rightEdge]×[header.y(),header.bottom()])`，绘制后 disable；徽标、按钮在 scissor 外。滚动文字右端被裁在按钮左缘、左端被裁在名称起点。
- 后果：名称与按钮/徽标区域完全互斥，任何滚动相位都不会在按钮上出现文字；静态名称右端 `header.right()-20 < rightEdge` 不受影响；代价是名称滚到右端时在按钮左缘「硬切消失」（视觉上文字钻进按钮背后，符合预期）。

### 6. 遗留 / 后续

- 实机复核：PCL 启动 poke（确认游戏已重启加载 08:55 jar）打开交易所——长名箱子连续滚动，名称滚到右端在展开按钮左缘被裁掉，按钮/箭头始终清晰；滚到左端在名称起点被裁，末影「末」徽标清晰。
- 结算预览行（TOP 层，无 scissor）未做区域互斥：其滚动文字滚到价格左缘时被后记录的 subtotal（z 序在后）盖住，价格窄且文字从两侧露出程度低，暂不改；如实机发现同样遮挡再处理。

---

## 会话 #24e（2026-08-10 09:00）——修复 enableScissor 参数语义（名称消失回归）

### 1. 本次需求（用户反馈）

会话 #24d 部署后名称**整体不显示**（过头了）。

### 2. 根因

`GuiGraphics.enableScissor(int x, int y, int x2, int y2)` 是 **(x1,y1,x2,y2) 右/底边界语义**（内部 `new ScreenRectangle(x, y, x2-x, y2-y)`），**不是** `(x,y,width,height)`。
#24d 误把 `width = screenX(rightEdge)-screenX(tx)`、`height = screenY(header.bottom())-screenY(header.y())` 当第三、四参数传入 → 内部 `x2-x1`、`y2-y1` 变负/零 → `RenderSystem.enableScissor(Math.max(0,..))` 裁成 0 → 名称区域全被裁掉不显示。
外层既有调用（3963）`enableScissor(screenX(left.x()), screenY(listTop), screenX(left.right()), screenY(ACCORDION_BOTTOM_LIMIT))` 传的正是**绝对坐标右/底边界**，故一直正常——这原本就是 (x1,y1,x2,y2) 的用法，被误当宽高。

> 证据：反编译 neoformruntime `decompile_*.jar` 的 `GuiGraphics.java` L154-156。

### 3. 修复（[CHANGED] 留痕）

- **`ExchangeScreen.renderAccordionEntry`**：名称 scissor 第三/四参数改回绝对屏幕坐标：
  ```java
  g.enableScissor(screenX(tx), screenY(header.y()),
          screenX(rightEdge), screenY(header.bottom()));
  ```
  scissor 矩形 = `[tx, rightEdge] × [header.y(), header.bottom()]`，正确实现 ADR-147 的区域互斥（右端裁在按钮左缘、左端裁在名称起点）。

### 4. 验证

- `./gradlew build`：编译通过，**704 tests / 0 failures**。
- `./gradlew jar` 重新打包，安装至 PCL poke（09:00，sha256 `d71537d0…` 一致）。
- 反编译确认签名：`enableScissor(x1,y1,x2,y2)` → `ScreenRectangle(x1,y1,x2-x1,y2-y1)`。

### 5. ADR 修订

**ADR-147（修订）：enableScissor 是 (x1,y1,x2,y2) 边界语义，不是宽高**
- 状态：修订（会话 #24e）
- 决策补充：调用 `enableScissor` 时第三/四参数必须传**右边界/底边界的绝对屏幕坐标**（局部坐标经 `screenX/screenY` 转换），不得传 width/height 差值。
- 后果：名称区域互斥正常生效——滚动名称滚到右端在展开按钮左缘被裁掉（文字"钻进按钮背后"），左端在名称起点被裁，末影「末」徽标与展开按钮始终清晰。

### 6. 遗留 / 后续

- 实机复核：重启 poke 打开交易所——名称应正常显示且连续滚动，展开按钮/箭头与末影徽标不再被文字覆盖。

---

## 会话 #26（2026-08-10 09:10）——能量凝聚器 PKM 缓冲钱包缩写补漏

### 1. 本次需求（用户反馈）

用户反馈：**「物品栏宝可梦队伍列表下面那个钱包数值缩写没有生效」**。即钱包余额在部分界面仍显示完整千分位（如 `1,234,567`），未启用会话 #25 的 `1k/1m` 缩写。

### 2. 定位

对客户端全部钱包/余额显示点做全量盘点（grep `formatWallet|getBalance|getPkm|wallet`）：

| 界面 | 显示点 | 会话 #25 后状态 |
|---|---|---|
| `ExchangeScreen` | 中栏钱包 `bal = menu.getBalance()`（2957-2958） | ✅ 已缩写 |
| `TransmutationTableScreen` | PKM 余额（163-169） | ✅ 已缩写 |
| **`CondenserScreen`** | **PKM 缓冲（144-145）** | ❌ **仍 `FORMATTER.format` 完整千分位** |

- 其余 `getPkm/getBalance` 命中均为服务端/经济层/物品 EMC 价格（`TooltipEvents`、`PKMManager`、`ExchangeService` 等），非钱包显示，保持 `formatAmount` 完整格式（ADR-144 语义区分）。
- `PokeEMCClient` 无 HUD/overlay/`InventoryScreen` 覆盖注册；`PlayerTradeScreen` 无钱包余额渲染（其 `PKM xxx` 为报价资产条目非余额）。故**唯一漏网点即 `CondenserScreen`**。

### 3. 修复（[CHANGED] 留痕）

- **`CondenserScreen`** 三处改动，与 `TransmutationTableScreen` 会话 #25 模式完全一致：
  1. 新增字段 `private int[] pkmBalanceBox;`（局部坐标 `{x1,y1,x2,y2}`，`renderLabels` 每帧更新）。
  2. `renderLabels`：`FORMATTER.format(menu.getPkm())` → `ExchangeUiModel.formatWallet(pkm)`（1k/1m/1b/1t 一位小数去尾零）；记录文本区域。
  3. `renderTooltip`：悬停 PKM 缓冲区域显示 tooltip——标题行 + `FORMATTER.format(menu.getPkm()) + " PKM"` 完整千分位（先于列表条目悬停判断返回）。

### 4. 验证

- `./gradlew build`：编译通过，**704 tests / 0 failures / 0 errors**（JUnit XML 汇总）。
- 部署：`cp build/libs/poketrade-1.0.0.jar` → PCL poke mods（09:10），sha256 `f170c1e4…` 源/目标一致。

### 5. ADR

**ADR-144（补充）：钱包余额显示统一走 `formatWallet` 缩写**
- 状态：已采纳（会话 #25，补充于会话 #26）
- 决策补充：会话 #25 只覆盖 `ExchangeScreen` 与 `TransmutationTableScreen`；会话 #26 确认 `CondenserScreen` 的 PKM 缓冲同为**玩家 Pixelmon 钱包余额**（`menu.getPkm()` → `CondenserBlockEntity.getPkm()` → 凝聚缓冲，本质是钱包映射），一并切换到缩写 + 悬停完整值。**判定标准：数值来源为 `menu.getBalance()/getPkm()`（钱包/凝聚缓冲）时用 `formatWallet`；物品 EMC 价格（`PKMManager.getPkm(item)` 价格）用 `formatAmount`。**

### 6. 遗留 / 后续

- 实机复核：重启 poke，打开能量凝聚器——顶部「PKM 缓冲」应显示缩写（如 `1.2k`），悬停显示完整千分位。
- TODO：后续若新增钱包显示点，一律按 ADR-144 判定标准走 `formatWallet`。

---

## 会话 #27（2026-08-10 09:30）——Pixelmon 物品栏钱包数字缩写（Mixin 注入）

### 1. 本次需求（用户反馈 + 截图确认）

用户反馈：「物品栏宝可梦队伍列表下面那个钱包数值缩写没有生效」。会话 #26 误判为 `CondenserScreen`（已修）。用户提供最新截图（`screenshots/2026-08-10_09.16.47.png`）指认：**Pixelmon 创造模式物品栏左侧队伍面板（6 精灵球）底部金额 `₽5,632,000`**。

关键澄清：该数值由 **Pixelmon 原版 GUI** 渲染，非 PokeEMC 代码——会话 #25/#26 只覆盖 PokeEMC 自家 screen，从未触及 Pixelmon 原版物品栏。

### 2. 定位（反编译 Pixelmon 9.3.16 字节码，javap）

- `InventoryPixelmon.drawGuiContainerBackgroundLayer`（offset 88-144）：
  ```java
  if (ClientData.playerMoney != null) {
      ScreenHelper.drawStringRightAligned(g,
          NumberFormat.getInstance().format(ClientData.playerMoney),  // "5,632,000"（纯数字，无 ₽）
          gui.getGUILeft() - partyWidth + 42, gui.height/2f + 66f, 0xF0F0F0, false, true);
  }
  ```
- `₽` 前缀由 Pixelmon 其他位置独立绘制，非本调用。
- 生存/创造物品栏**共用**同一 `InventoryPixelmon`（`InventoryPixelmonExtendedScreen` 传 partyWidth=42、`CreativeInventoryExtendedScreen` 传 53），一处注入同时覆盖两种界面。
- 金额源：`com.pixelmonmod.pixelmon.storage.ClientData.playerMoney`（客户端静态 `BigDecimal`，服务端同步快照）。

### 3. 方案选择（架构权衡）

| 方案 | 结论 |
|---|---|
| Gradle mixin 插件（`net.neoforged.gradle.mixin`） | ❌ 需从 maven.neoforged.net 下载，本地 proxy（127.0.0.1:18080）离线且缓存无此插件，Plugin Portal 无 marker |
| `ScreenEvent.Render.Post` 覆盖重绘 | ❌ 需精确背景色填充覆盖（Pixelmon 面板半透明纹理，有可见色块瑕疵）；partyWidth 为 private 字段需硬编码两种值 |
| **手动 Mixin + `[[mixins]]` 声明（采纳）** | ✅ NeoForge 用官方映射无 remap、**refmap 非必需**；`LoadingModList.addMixinConfigs()` 从 `mods.toml` 的 `[[mixins]]` 显式注册（FML 源码确认，非自动扫描）；直接替换绘制文本，零背景覆盖、零视觉瑕疵 |

> 源码依据：反编译 `fancymodloader-loader-4.0.43-sources.jar` 的 `LoadingModList.java:79-90`（`addMixinConfigs` → `ModFileParser.getMixinConfigs` 读 `[[mixins]]` + `config` 键）、`DeferredMixinConfigRegistration.java`。

### 4. 实现（[CHANGED] 留痕）

1. **`ExchangeUiModel`**：类改 `public final`、`formatWallet(long)` 改 `public static`（跨包供 mixin 调用；纯可见性变更，无行为变化）。
2. **`neoforge.mods.toml`**：追加 `[[mixins]] config = "poketrade.mixins.json"`。
3. **`src/main/resources/poketrade.mixins.json`**：`package: com.pokeemc.mixin`、`defaultRequire: 1`（注入失败即启动失败，快速暴露问题）。
4. **新 `com.pokeemc.mixin.InventoryPixelmonMoneyMixin`**：
   - `@Mixin(targets = "...InventoryPixelmon")`（泛型类用字符串 targets）
   - `@ModifyArg(method = "drawGuiContainerBackgroundLayer", at = @At(value = "INVOKE", target = "Lcom/pixelmonmod/pixelmon/client/gui/ScreenHelper;drawStringRightAligned(Lnet/minecraft/client/gui/GuiGraphics;Ljava/lang/String;FFIZZ)V"), index = 1)`
   - handler：`original.replace(",", "")` → `BigDecimal.longValue()` → `ExchangeUiModel.formatWallet(amount)`；非数字异常兜底原样返回。不引用 Pixelmon 类型，解耦。
   - 注意：未配置 annotation processor，Mixin 注解为 CLASS retention 由 javac 写入 class（已用 `javap -v` 验证 `RuntimeInvisibleAnnotations` + `targets` 正确存在）。

### 5. 验证

- `./gradlew build`：编译通过，**704 tests / 0 failures / 0 errors**。
- jar 内容检查：`InventoryPixelmonMoneyMixin.class`、`poketrade.mixins.json`、`neoforge.mods.toml`（含 `[[mixins]]`）均在。
- 部署：cp → PCL poke mods（09:30），sha256 `9a5ff2ef…` 源/目标一致。

### 6. ADR

**ADR-148：对 Pixelmon 原版 GUI 的改动统一走「手动 Mixin + mods.toml [[mixins]] 声明」**
- 状态：已采纳（会话 #27）
- 背景：maven.neoforged.net 网络不可达 + 本地 proxy 离线，Gradle mixin 插件无法下载；NeoForge 官方映射环境 refmap 非必需。
- 决策：新建 `com.pokeemc.mixin` 包承载对 Pixelmon 类的方法级注入；mixin 配置经 `neoforge.mods.toml` 的 `[[mixins]]` 显式注册（FML `LoadingModList.addMixinConfigs`），`defaultRequire: 1` 保证注入点失效立即暴露。
- 后果：不依赖 gradle mixin 插件、无 refmap、无构建期 annotation processor；注入点方法/类名必须与 Pixelmon 字节码精确一致（以 javap 为准），Pixelmon 版本升级需复核签名。

### 7. 遗留 / 后续

- 实机复核：重启 poke → 打开物品栏（生存/创造）→ 队伍面板底部金额应显示缩写（`₽5.6m`）；若游戏启动崩溃于 `MixinApplyError` 即注入点不匹配，回退本会话改动。
- TODO：后续如 Pixelmon 更新，复核 `InventoryPixelmon.drawGuiContainerBackgroundLayer` 中 drawStringRightAligned 签名。

---

## 会话 #28（2026-08-10 09:53）——便携式转化桌（手持右键打开交易所）

### 1. 本次需求

用户要求「加入便携式转化桌」：ProjectE 风格的可手持物品，随身携带、右键随时打开转化桌界面。

已确认决策（AskUserQuestion）：
1. **打开界面 = 交易所三栏**（与转化桌方块完全一致，复用 `ModMenuTypes.EXCHANGE` + `ExchangeScreen`，零新菜单/屏幕）。
2. **获取方式 = 合成配方**（转化桌方块 + 1 炼金煤炭 → 1 便携转化桌）+ 创造标签页可拿取。

### 2. 方案选择（架构权衡）

| 方案 | 结论 |
|---|---|
| 新 MenuType + 新 Screen（复制 ExchangeScreen 改标题） | ❌ 三栏 UI 全部复制一份，维护双份；无任何差异需求 |
| **复用 ExchangeMenu/ExchangeScreen（采纳）** | ✅ `ExchangeMenu` 三参构造 `table` 本就可空（客户端构造传 null）；`StorageBrowserMenu.serverContext == null → stillValid 恒 true`；`ExchangeScreen` 无 BlockEntity/Level/pos 依赖。改动收敛到 ExchangeMenu 一处，界面逐位等价 |

**核心难点**：服务端三参构造的 `table` 决定 `ownerPlayer`（钱包读写目标）与初始化。`table=null` 时原实现 `ownerPlayer=null`、钱包永不更新、且 `resultCode` 不初始化 → 客户端 nonce 从 -1 起步比对会把 `SUCCESS`(ordinal=0) **误报为"交易成功"**。需把判定从 `table != null` 改为 `player instanceof ServerPlayer`。

### 3. 实现（[CHANGED] 留痕）

1. **`ExchangeMenu`（4 处）**：
   - `ownerPlayer` 字段：`table != null ? playerInventory.player : null` → `playerInventory.player`（客户端 LocalPlayer 不命中任何 `instanceof ServerPlayer` 守卫，行为等价原 null）。
   - 结果码初始化块：`if (table != null) { resultCode.set(-1); ... }` → `if (playerInventory.player instanceof ServerPlayer sp)`（便携必要，防误报"交易成功"；顺带合并初始钱包同步到同一守卫）。
   - 初始钱包同步：`if (table != null && player instanceof ServerPlayer sp)` → `if (player instanceof ServerPlayer sp)`。
   - `runSell` 守卫：`if (ownerPlayer == null)` → `if (!(ownerPlayer instanceof ServerPlayer))`。
   - 三参构造 Javadoc 更新：`table` 可空（便携模式无方块可达性校验）。
2. **新 `com.pokeemc.item.PortableTransmutationTableItem`**：`use()` 服务端 `serverPlayer.openMenu(new SimpleMenuProvider((id, inv, p) -> new ExchangeMenu(id, inv, (TransmutationTableBlockEntity) null), TITLE))`；`TITLE = Component.translatable("item.poketrade.portable_transmutation_table")`；返回 `InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide())`（1.21.1 `Item.use` 返回 `InteractionResultHolder<ItemStack>`，非 `InteractionResult`）。
3. **`ModItems`**：`PORTABLE_TRANSMUTATION_TABLE = ITEMS.register("portable_transmutation_table", () -> new PortableTransmutationTableItem(new Item.Properties().stacksTo(1)))`。
4. **`ModCreativeTabs`**：`displayItems` 在转化桌方块后 `output.accept(PORTABLE_TRANSMUTATION_TABLE.get())`。
5. **资源**：模型 `assets/poketrade/models/item/portable_transmutation_table.json`（item/generated + `poketrade:item/portable_transmutation_table`）；纹理复制 `block/transmutation_table_top.png` 作占位；配方 `data/poketrade/recipe/portable_transmutation_table.json`（shapeless：转化桌 + 炼金煤炭）；语言 `zh_cn`：便携式转化桌 / `en_us`：Portable Transmutation Table。
6. **不需要改**：`ModMenuTypes`（复用 EXCHANGE）、`PokeEMCClient`（ExchangeScreen 已绑 EXCHANGE）、`ModNetwork`（无新包）、任何 GameTest。

### 4. 验证

- `./gradlew build --offline`：编译通过，**主项目 704 tests / 0 failures / 0 errors**（+ 子项目 poketrade-api 62 / testkit 3 / example 1，共 770 tests 全绿，与基线一致）。
- jar 内容检查：`PortableTransmutationTableItem.class`、模型 JSON、纹理 PNG、配方 JSON 均在。
- 部署：cp → PCL poke mods（09:53），sha256 `5f47d9af…` 源/目标一致。
- 实机验证步骤：`/give @p poketrade:portable_transmutation_table` → 手持右键 → 交易所三栏出现、钱包余额显示；左栏附近仓储可浏览/出售；中栏可买卖；无"交易成功"误报。合成配方（转化桌+炼金煤炭）可用。回归：右键转化桌方块行为不变。

### 5. ADR

**ADR-149：便携菜单复用 `ExchangeMenu` 的 `table=null` 便携语义**
- 状态：已采纳（会话 #28）
- 决策：便携物品打开的就是 `ExchangeMenu`，第三参 `table=null`（无方块距离校验）。`ownerPlayer` 语义从「table 非空时的方块会话玩家」放宽为「构造时的会话玩家」，以 `instanceof ServerPlayer` 统一守卫钱包读写与结果码初始化——**方块模式与客户端构造行为逐位等价，向后兼容**。
- 后果：便携菜单 `stillValid` 恒 true（`serverContext=null`），走远菜单不关闭——与独立仓储浏览器/玩家交易一致，属既有设计，接受。仓储操作（浏览/出售）仍需箱子距玩家 ≤8 格（`StorageSellPacket` 以玩家位置为圆心）；目录/买卖不受位置限制（无新经济能力）。
- 依赖 ADR-144：钱包显示统一 `formatWallet` 缩写（便携打开的仍是 `ExchangeScreen`）。

### 6. 遗留 / 后续

- 实机复核：重启 poke → 手持右键便携转化桌 → 三栏界面 + 钱包余额正常、无"交易成功"误报；合成配方、创造标签取用正常；转化桌方块回归正常。
- TODO：便携转化桌纹理目前为转化桌顶部贴图占位，后续可换独立图标。
- TODO：如用户后续需要「快捷栏双击打开」或「副手持有也可用」，在 `use()` 中补充判断即可。

## 会话 #29（2026-08-10 10:30）——双箱破坏降级迁移 + 仓储列表 S2C 失效刷新

### 1. 本次需求

用户报告两个问题：**双箱被破坏一半变成单箱时，仓储列表不刷新、剩下的箱子失去认领**；**放置/破坏箱子后列表不同步**。

### 2. 根因

1. **双箱降级丢认领**：双箱记录键为 `vanilla_double_chest`@主半区，形成双箱时原单箱记录已被 `claim()` 的 MIGRATED 路径迁移删除。破坏任一半区后剩下箱子被原版邻居更新改为 `SINGLE`，`VanillaDoubleChestAdapter.matches()` 不再匹配 → 双箱记录被 `evaluate()` 幽灵清理删除，但剩下箱子从未有 `vanilla_chest` 单箱记录 → 从列表消失、失去认领与 BREAK 保护。陷阱箱同理（`vanilla_trapped_chest` 单双同 typeId，主半区被破坏后 remaining 键与记录键不一致 → 记录丢失）。
2. **列表不即时刷新**：`markChunkDirty` 只写 `dirtyChunks` 集合，**无任何消费方**（死代码）；列表依赖客户端定时/移动重发 `QueryStoragesPacket`，且 `querySync` 有 10 tick 限频缓存 → 明显滞后。

### 3. 方案选择

| 方案 | 结论 |
|---|---|
| 服务端回放玩家最近查询参数（`lastPackets` map + sessionId 复用）主动推送响应 | ❌ 需维护每玩家最近查询参数、sessionId 过期语义、storage→network 包环风险 |
| **S2C 失效通知（采纳，与仓库 `CatalogChangedPacket` 同款）** | ✅ 服务端广播空载荷 `StorageChangedPacket`，客户端 `BrowserHost` 收到后以**当前条件**重发查询；无参数回放、无过期问题，重置限频缓存后天然走全量扫描 |

### 4. 实现（[CHANGED] 留痕）

1. **双箱降级迁移（`StorageProtectionEvents`）**：
   - 新增 `pendingDoubleChecks` 队列与 `refreshQueued` 标志；`onBreak` 未取消且破坏前 state 为双箱成员（`isDoubleHalf`）时入队 `PendingDoubleCheck(level, brokenPos, otherPos)`（`otherPos = pos.relative(getConnectedDirection(state))`）；`onPlace`/`onBreak` 均置 `refreshQueued`。
   - `processPendingDoubleChecks()`（public，GameTest 直调）：下一 tick 检查 remaining 半区是否已降级 `SINGLE`，是则 `migrateRecord(doubleKey@primary, singleKey@remaining)` 把双箱记录换键为剩余半区单箱键，**owner/ACL 完整继承**；失败（无记录 / 主半区幸存且单双同键 / 目标单箱键已存在）保守跳过，双箱记录留待 `evaluate()` 幽灵清理。
2. **S2C 失效刷新通道**：
   - 新 `com.pokeemc.network.StorageChangedPacket`（S2C 空载荷，`StreamCodec.unit(new StorageChangedPacket())`），handle 中 `Minecraft.getInstance().screen instanceof BrowserHost` → `host.onStorageListChanged()`。
   - `BrowserHost` 新增 `default void onStorageListChanged() {}`；`StorageBrowserScreen` → `requestQuery()`、`ExchangeScreen` → `requestStorages()`（复用当前 sessionId/搜索/排序/过滤）。
   - `StorageDiscoveryService.resetQueryState(uuid)`：清 `states` 与 `lastQueryTick`，绕过 10 tick 限频。
   - `flushQueuedRefresh(server)`：遍历在线玩家 `resetQueryState` + `PacketDistributor.sendToPlayer` 广播 `StorageChangedPacket`；空玩家列表为安全空操作（GameTest 可调）。
   - `ModNetwork` 注册 `playToClient(StorageChangedPacket...)` + `PROTOCOL_VERSION` "6"→"7"。
3. **GameTest 4 条**（protection batch，`StorageProtectionGameTests`）：`doubleChestBrokenHalfDegradesToSingleClaim`、`doubleChestPrimaryHalfBrokenMigratesToSecondary`、`placingChestQueuesRefresh`、`trappedDoubleChestDegradesToSingle`。

### 5. 验证

- `./gradlew build --offline`：编译通过，**主项目 704 tests / 0 failures / 0 errors**（与基线一致）。
- `./gradlew runGameTestServer --offline`：**All 54 required tests passed**（含新增 4 条）。首轮 3 条降级测试失败：直接 `setBlock` 破坏后调 `processPendingDoubleChecks()` 时队列为空（队列由 `onBreak` 填充，从未走事件）——改为破坏前构造 `BlockEvent.BreakEvent` 调 `onBreak` 入队后通过。
- jar 检查：`StorageChangedPacket.class`、`StorageProtectionEvents$PendingDoubleCheck.class`、`BrowserHost.class` 均在。
- 部署：cp → `.minecraft/versions/poke/mods/poketrade-1.0.0.jar`，sha256 `8e761f53…` 源/目标一致。
- 实机验证步骤：①放两箱形成双箱（认领）→ 破坏一半 → 打开转化桌/交易所，剩下箱子立即以「箱子」独立身份出现在列表（原 owner、原 ACL），无幽灵双箱；②打开仓储列表时放置新箱子 → 列表立即出现新箱子；③拆掉双箱两边 → 列表无残留；④陷阱箱双箱同验。回归：单箱放置/破坏、双箱合并（异 owner 拒绝）行为不变。

### 6. ADR

**ADR-150：双箱降级迁移 + S2C 失效刷新通道**
- 状态：已采纳（会话 #29）
- 决策：双箱任一半区被破坏后，下一 tick 检查 remaining 是否降级 `SINGLE`，把双箱记录 `migrateRecord` 到剩余半区单箱键（owner/ACL 完整继承）；刷新通道采用与 `CatalogChangedPacket` 同款的 S2C 空载荷失效通知（客户端以当前条件重查），而非服务端回放。
- 后果：双箱破坏一半后箱子保持认领与 BREAK 保护，无幽灵双箱残留；放置/破坏后开着的仓储/交易所列表即时更新。新增 S2C 包使协议版本 6→7，需客户端与服务端同步升级。
- 依赖 ADR-143（双箱规范化主键 + `migrateRecord` 语义）。

### 7. 遗留 / 后续

- 实机复核：重启 poke → 双箱破坏一半 / 浏览时放置 / 拆两边 / 陷阱箱双箱全链路。
- TODO：`command claim/unclaim` 显式操作场景未接入降级检查（命令显式操作可接受，暂不覆盖）。
