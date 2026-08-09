# PokeEMC / PokeTrade — MOD 开发工作日志（Audit Trail）

> **铁律 #1**：所有代码变更与架构决策必须在本文件留痕。每次迭代追加以下四板块：
> `📅 会话记录` / `🎯 本次需求` / `📐 架构决策记录 (ADR)` / `⚠️ 遗留风险与待办 (TODOs)`。
> 推翻既有方案时单独标注 `[DEPRECATED]` 与 `[BREAKING CHANGES]`，严禁悄无声息覆盖。

---

## 📅 会话记录

### [2026-08-09] 会话 #1 — 重做启动：现状盘点

### 🎯 本次需求

按工业级标准**全量重做** `pokeemc`（modid `poketrade`，Pixelmon × ProjectE 联动模组）。已确认决策：

| 维度 | 决策 | 依据 |
|---|---|---|
| 目标平台 | **保持 `[1.21.1][NeoForge 21.1.248]`** | 用户明确选择，不升级 26.2，避免 Capability→DataAttachment 等迁移成本 |
| 重做范围 | **全量重写**（主模块 + `poketrade-api` 三子模块） | 用户明确选择 |
| 参考源码 | `NeoForge-26.2.x/` 官方树（仅溯源参考，不用于编译） | 环境 |

### 📐 架构决策记录 (ADR)

> 5 路探索代理全部返回，此为正式 ADR。所有决策基于事实基线（非猜测）。

| # | 决策 | 理由 | 影响范围 |
|---|---|---|---|
| ADR-1 | **平台锁定 `[1.21.1][NeoForge 21.1.248]`，不迁移 26.2** | 用户明确选择；避免 Capability→DataAttachment、网络 CustomPayload 强制迁移等成本 | 全部模块 |
| ADR-2 | **重做范围 = 全量重写**（主模块 + `poketrade-api`/`-testkit`/`-example` 三子模块） | 用户明确选择；现有代码存在已识别缺陷（见 TODO #6-#10） | 全部模块 |
| ADR-3 | **保留架构骨架**：CustomPayload 网络、DeferredRegister 构造器注册、DataSlot 菜单同步、Service 单例装配、状态机服务端主线程 | 解剖证明这些机制已合规、成熟，重写不是推倒重来而是工业级加固 | 全部模块 |
| ADR-4 | **修复已知缺陷**：① 事务提交与 revision 非原子（commit 成功但 bump 失败误报冲突且物品已移动）→ 改单临界；② SavedData 热替换（encode→decode 往返）→ 提供直接公开 API；③ `DefaultPkmValues.PIXELMON` 定义未用（数据包 `pixelmon.json` 才是生效源，两处值不一致）→ 定一为权威 | 全量重写首要价值 = 消除语义缺口 | storage / emc |
| ADR-5 | **保留两个 TradeService 的区分**（`com.poketrade.api.TradeService`=EMC 买卖 vs `com.pokeemc.trade.service.TradeService`=玩家间交易），重写时以明确命名消歧 | 解剖发现两者易混淆 | api / trade |
| ADR-6 | **API 纯 Java 硬约束保留**：`StorageApiSurfaceTest` 反射锁表面，禁止 MC/NeoForge/Pixelmon/根模组类型泄漏 | 契约测试已存在，是 API 质量护栏 | api 子模块 |
| ADR-7 | **先 git init 建立基线，再动手** | 208 个类全量重写，无版本控制 = 高风险 | 工程根 |
| ADR-8 | **保留 build 基础设施**：本地代理 `127.0.0.1:18080`、GBK/UTF-8 编码处理、GameTest 命名空间、pixelmon.jar 同步 | 环境约束（网络受限/中文 Windows），破坏即无法构建 | build.gradle |

### 被替代方案（历史决策）

- **[DEPRECATED] 旧 `NeoForge-26.2.x` 升级设想**：不采纳。现有工程 1.21.1 已稳定可运行（run-client.log 尾部 BUILD SUCCESSFUL），26.2 需全量 API 迁移，收益低于成本。

### ⚠️ 遗留风险与待办 (TODOs)

1. **【风险·高】非 git 仓库**：全量重写前必须先 `git init` 并提交基线（当前构建可运行，见 `run-client.log` 尾部 BUILD SUCCESSFUL）。
2. **【已完成】探索代理**：5 路并行解剖已全部返回，结论已并入本 ADR。
3. **【风险·环境】网络受限**：`build.gradle` 已配置本地代理 `nf-proxy.ps1`（`http://127.0.0.1:18080`）重指向 Maven 缓存 `D:\nf-mirror-cache`；重写时不得破坏该机制。
4. **【风险·环境】编码陷阱**：Gradle daemon 用 `-Dfile.encoding=GBK`（中文 Windows），`neoforge.mods.toml` 等必须显式 `-Dfile.encoding=UTF-8` 覆盖（主 build.gradle 已处理，重写须保留）。
5. **【待办】测试基线与契约**：现有 `src/test/java` 58 个测试类 + `poketrade-api-testkit` 的 `TradeServiceContract` 契约测试 + GameTest，重写时需保留/迁移。
6. **【待修复·事务原子性】** `StorageTransactionService` 槽位 commit 成功但 revision bump 失败 → 误报 REVISION_CONFLICT 而物品已移动。重写须把「槽位写 + revision + 审计」放进同一临界区或补偿。
7. **【待修复·SavedData 热替换】** unclaim/rename/repair 靠 encode→NBT→decode 往返替换存档实例。重写须直接提供 `deleteStorage`/`renameTemplate`/`rebuildIndex` 公开 API。
8. **【待决策·PIXELMON 权威源】** `DefaultPkmValues.PIXELMON`（代码内置）与 `data/poketrade/pkm/pixelmon.json`（数据包）值不一致。重写须定数据包为权威、代码内置仅作离线兜底，或反之 —— 待用户确认。
9. **【待修复·多机并发】** savedData 无跨进程锁，多实例共享存档文件有竞态（单实例本地无碍，可延期）。
10. **【待修复·自动化守卫覆盖】** 自动化守卫只覆盖内置容器，第三方容器可绕过（现有显式告警）。重写可保留告警并记录为已知边界。

---

## 会话记录存档区

### [2026-08-09 11:24] 会话 #5 — double trapped chest 复核：误判更正 + 回归测试固化

### 🎯 本次需求
核实会话 #4 遗留 TODO：「double trapped chest 仅暴露 27 槽」是否真实，真实则修复。

### 📐 架构决策记录 (ADR)
- **ADR-23（复核结论：误判，无功能缺陷）**：上一会话误以为 `TrappedChestBlock` 仅继承
  `AbstractChestBlock`（与 `ChestBlock` 平级），据此推断 `ChestPairSupport.isDoubleChest` 的
  `instanceof ChestBlock` 判定会漏掉陷阱箱、导致配对失效只暴露 27 槽。**实测推翻该假设**：
  MC 1.21.1 下 `ChestBlock.class.isAssignableFrom(TrappedChestBlock.class) == true`
  （`TrappedChestBlock extends ChestBlock`，共享 `ChestBlock.TYPE` 属性），双箱配对逻辑对陷阱箱
  同样成立。`VanillaTrappedChestAdapter` 单 typeId 单双通吃，功能完整。
- **ADR-24（复核即修复 = 回归测试固化）**：确认**无运行时代码变更需求**。本次仅新增两类回归测试
  固化行为，防止未来 MC 类结构变更引发回归：
  - 单测 `ChestPairSupportTest.trappedChestIsChestFamilyMember`：固化 `TrappedChestBlock extends ChestBlock` 继承断言（结构变更即测试失败）。
  - GameTest `StorageAdapterGameTests.doubleTrappedChestExposesAllSlots`：双陷阱箱 54 槽、双半区
    canonicalize 归一主半区、槽位写入落到真实半区容器、拆分后降级 27 槽。
  不引入 `vanilla_double_trapped_chest` 新 typeId——现有方案功能完整，加 typeId 还需额外 claim
  迁移逻辑，收益为负。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 复核完成：**无缺陷**，新增 1 单测 + 1 GameTest 回归固化；`:test` 全量 + `runGameTestServer`
      **40/40 通过**。
- [ ] **已知边界（非缺陷，记录）**：trapped 双箱 typeId 仍为 `vanilla_trapped_chest`（单双共用），
      UI label 显示 "Trapped Chest"（普通 chest 双箱显示 "Double Chest"）。纯显示差异，不影响调用
      全部格子；如玩家期望显示区分可后续立项加双变体文案。

### [2026-08-09 11:12] 会话 #4 — 三个功能 Bug 修复（大箱子兼容 / 购物车 UI / 转化桌回收价）

### 🎯 本次需求
玩家反馈三个功能 Bug，要求"严格按照 api 官方文档修复"：
1. **Bug #1 大箱子兼容**：左侧存储面板无法调用 double chest 的全部格子（27-53 格几乎不可达）。
2. **Bug #2 购物车 UI**：购物车收起态误现"买"按钮（删除）；"一键出售"下方缺"一键买入"（补上）。
3. **Bug #3 转化桌回收价**：物品已在数据包定义价格，卖入转化桌仍提示"没有回收价"。

完整验证链全绿：`compileJava/compileTestJava` → 定向单测（价格/UI/网络）→ `:test` 全量 58 测试类 → `runGameTestServer`（39/39 GameTest）→ `gradlew build --offline`。

### 📐 架构决策记录 (ADR)
- **ADR-20（Bug #1 根因 = UI 可达性，非服务端链路）**：double chest 54-slot 服务端链路经代码审读 + 既有 GameTest 确认正确（`VanillaDoubleChestAdapter` canonicalize→primary half→`DoubleContainer` 54 槽 → `MinecraftSlotStore` → `StorageHandleImpl.snapshot()` 保序返回全部非空槽；claim/discovery/withdraw/deposit 均正确）。真正缺陷在客户端 UI 可达性：accordion 网格固定 **3 行可见**（`ACCORDION_GRID_ROWS=3`，21 槽），`PeStyle.scrollbar` 纯渲染不可点击，鼠标滚轮必须精确悬停在网格上——大箱子 4-8 行的槽位玩家根本够不到。修复：`ExchangeScreen.java` 网格行数按槽位数量**自适应**（`MAX_ACCORDION_ROWS=7`，210px < 底部按钮 y=226，单箱 4 行/双箱 7 行/8 行时末排滚动），并把滚动条改为**可点击跳页**（`handleLeftClick()` 按 y 位置命中跳页）。[CHANGED] `ExchangeScreen.java`。
- **ADR-21（Bug #2 购物车 UI）**：移除收起态误渲染的"买"按钮（`cartSellCollapsed` 相关渲染分支 [REMOVED]）；在"一键出售"正下方新增"一键买入"按钮（`ExchangeUiModel.Layout.cartBuy` control + `ExchangeScreen.buyCart()` 逻辑 + zh_cn/en_us 文案 `buy.cart`/`buy.sent`）。[CHANGED] `ExchangeUiModel.java`/`ExchangeScreen.java`/`zh_cn.json`/`en_us.json`。
- **ADR-22（Bug #3 根因 = 覆盖价强制归零）**：`PriceOverrides.parse` 原先**无条件**把 `pixelmon:master_ball` 的 sell 强制归 0（"只买不卖"硬规则），导致玩家数据包即便定义了 sellPrice 也会被覆盖；`ExchangePriceService` 按官方×10 定价逻辑对 sell=0 物品判定无回收价，客户端 `sell.no_price` 误报。修复：**卖出价尊重数据包 sellPrice**（默认 0 = 不回收），仅保留 buy==5000000 硬校验（防配置破坏经济），缺失时注入默认 `(5000000, 0)`；同步把内置 `prices.json` 的 master_ball sellPrice 0 → 5000000（与 PKM 值一致，默认可回收），并新增单测 `masterBallSellRespectedWhenConfigured`。
  - **[BREAKING CHANGES]** master_ball 回收行为：由"只买不卖"变为"尊重数据包 sellPrice"。服务器若沿用旧版 `prices.json`（sell=0），行为不变（仍不可回收）；如需默认可回收需同步更新内置数据包。`buyPrice` 硬校验仍为 5000000，配置不等于该值会抛 `IllegalStateException`（不静默回退）。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 三个 Bug 全部修复并通过全量回归（`compileJava/compileTestJava` → `:test` → `runGameTestServer` 39/39 → `build`）。
- [x] ~~**已记录未修复**：double **trapped chest** 仅暴露 27 槽~~ **[REVISED] 会话 #5 复核：此为误判，无功能缺陷**——`TrappedChestBlock` 在 MC 1.21.1 继承 `ChestBlock`，`ChestPairSupport.isDoubleChest` 判定成立，双陷阱箱已正确暴露 54 槽（GameTest `doubleTrappedChestExposesAllSlots` 实证通过，见会话 #5）。已知边界：trapped 双箱 typeId 仍为 `vanilla_trapped_chest`（单双共用），UI label 显示 "Trapped Chest" 而非 "Double Trapped Chest"——纯显示差异，不影响调用全部格子。
- [ ] **行为变更提示**：[BREAKING CHANGES] master_ball sell 语义变更已记录于 ADR-22；旧存档/数据包无需迁移（尊重 sell=0 即保持原行为）。

### [2026-08-09 10:29] 会话 #3 — 官方 API 合规审计（Batch 4）：弃用/待删 API 清零（commit `3209932`）

### 🎯 本次需求
延续"严格按照官方api标准重做pokeemc"：Batch 1~3 已完成并提交（`f19d43d`、`5a6f7b9`）。
本轮执行 **Batch 4 全模块官方 API 合规审计**——以 `-Xlint:deprecation -Xlint:removal` 全量编译暴露所有弃用/待删 API，逐一按官方替代修复至零警告。完整 `:test` 套件回归 **BUILD SUCCESSFUL**。

### 📐 架构决策记录 (ADR)
- **ADR-14（审计方法）**：临时 init 脚本注入 `-Xlint:deprecation -Xlint:removal`（不改 build.gradle），对 `compileJava + compileTestJava` 全量暴露。修复后复编译验证 0 警告。
- **ADR-15（@EventBusSubscriber bus 弃用）**：NeoForge 21.1.248 中 `bus()`/`Bus` 标记 `[removal]`。官方文档（`documentation/docs/concepts/events.md` 169/175 行）确认推荐 `@EventBusSubscriber(modid=...)` 不带 `bus`——NeoForge 依 `IModBusEvent` 超接口自动路由到 mod bus。已移除 2 处 `bus = EventBusSubscriber.Bus.MOD`（`ModNetwork.java`、`PokeEMCClient.java`）。
- **ADR-16（Item.builtInRegistryHolder() 弃用）**：6 处改为 `BuiltInRegistries.ITEM.getKey(item)`（返回 `ResourceLocation`，非弃用、语义等价）。涉及 `PKMManager`×2、`PkmRecipeCalculator`×2、`CondenserScreen`、`TransmutationTableScreen`。
- **ADR-17（FastUtil Object2LongMap 装箱弃用）**：`entrySet()`/`put(K,Long)` 装箱变体弃用，改 `object2LongEntrySet()` + `getLongValue()` 原语访问（`PKMManager.clearComputed`、`ExchangePriceService.pkmFallback`），消除自动装箱。
- **ADR-18（RegistryFriendlyByteBuf 构造弃用）**：`(ByteBuf, RegistryAccess)` 弃用，Neo 补丁 javadoc 指明 "use overload with ConnectionType context"。测试改用 `(ByteBuf, RegistryAccess, ConnectionType.OTHER)`。
- **ADR-19（runGameTestServer 配置缓存兼容）**：`build.gradle` 的 `doFirst` 闭包在执行期引用 script 对象（`file()`/`copy {}`/`project`），配置缓存下报 `Cannot reference a Gradle script object from a Groovy closure`。改为配置期捕获纯 `File` 值 + `java.nio.Files.copy`，闭包内仅剩 task 自身 API（`logger` 解析到 `task.getLogger()`）。修复后配置缓存正常存储，GameTest 全量通过。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] Batch 4 提交（`3209932`）+ GameTest（`runGameTestServer` **39/39 通过**）+ `gradlew build --offline` 终验。
- [x] 58 个测试类 + GameTest 迁移核对：`:test` 全绿（BUILD SUCCESSFUL）、`runGameTestServer` 39/39。
- [ ] 后续可立项：Datagen 引入（本轮明确排除，保留手写 JSON）。

### [2026-08-09] 会话 #2 — 官方 API 标准重做：摸底完成，方案提交

### 🎯 本次需求
"严格按照官方api标准重做pokeemc"。会话 #1 已立 ADR-1~8，本轮执行摸底并提交重做执行方案。

### 📐 架构决策记录 (ADR)
- **ADR-9（基线固化）**：已 `git init` 并提交基线 commit `2b77ec5`（.gitignore 排除 run/build/.gradle/logs/*.jar）。`gradlew build --offline -x test` 验证 **BUILD SUCCESSFUL**，重做起点绿色。
- **ADR-10（缺陷 #8 实锤）**：`DefaultPkmValues.PIXELMON`（master_ball=32768）为**死代码**，全工程无人读取；运行时权威源为数据包 `data/poketrade/pkm/pixelmon.json`（master_ball=5000000）。两者相差 152 倍。重做需定一为权威，待用户确认方向。
- **ADR-11（缺陷 #7 实锤）**：`StorageCommands.replaceSavedData()` 对 rename/repair/unclaim 走 `encode→NBT改→decode→set` 热替换绕路，用硬编码序列化键（KEY_STORAGES 等）复制 encode/decode 格式，实例替换使其他服务持有的引用失效。重做改为 StorageSavedData 直接公开 API（已存在 deleteStorage/renameStorage/repairTemplateReferences，缺 rebuildChunkIndex）。
- **ADR-12（缺陷 #6 实锤）**：`StorageTransactionService.doExecute` 第 294-295 行先 `commitExtract/commitInsert`，第 310/332 行后 `bumpRevision`——bump 失败时物品已移动却报冲突。重做改为"全量校验前置 + 槽位写/revision/审计单临界"。
- **[DEPRECATED] 根目录 `docs/MOD_DEVELOPMENT_LOG.md`**：本会话误建于工程根，正确位置为 `_modref/pokeemc/docs/`，已清理并归档至此。

### ⚠️ 遗留风险与待办 (TODOs)
- [ ] **ADR-13（用户拍板）**：① 缺陷 #8 → 数据包为准，删除死代码 `DefaultPkmValues.PIXELMON`；② Datagen → 本轮不引入，保留手写 JSON（后续单独立项）。
- [ ] 待执行批次：Batch1 事务原子性 → Batch2 SavedData 热替换消除 → Batch3 PIXELMON 删死代码 → Batch4 全模块 API 合规审计 + 测试迁移。
- [ ] 基线 commit `2b77ec5` 为回退锚点。

_（后续会话按时间倒序追加于此）_

### [2026-08-09 11:55] 会话 #6 — 四 Bug 修复（价格同步 / 商品列表 / 1.21.1 新物品定价 / 容器 UI 悬停提示）

### 🎯 本次需求
玩家反馈四个运行时 Bug（按首字母编号）：
1. **Bug A 价格不同步**：服务端与客户端价格不一致，部分商品即使有价格也无法售卖。
2. **Bug B 商品列表不完整**：交易所/转化桌目录应有更多物品。
3. **Bug C 1.21.1 新增原版道具没有价格**（风击人/试炼密室/犰狳/锻造台新物品）。
4. **Bug D 转化桌 UI 内背包物品悬停不显示提示信息**。

完整验证链全绿：`compileJava/compileTestJava` → 定向单测（价格/默认值）→ `:test` 全量 → `runGameTestServer`（**40/40 GameTest**）→ `gradlew build --offline`。

### 📐 架构决策记录 (ADR)
- **ADR-25（Bug A/B 根因 = `forServer()` 单例冻结 PKM 快照时序）**：`ExchangePriceService.forServer()` 首次创建于**数据包 reload（ServerStarting 阶段）**，`pkmFallback()` 冻结当时的 `PKMManager.snapshot()`；而合成树 `PkmRecipeCalculator.computeAll()` 在 **ServerStartedEvent** 才运行，此后 `rebuild()` 仅由数据包重载触发。故服务端目录的 PKM 兜底区**永远缺全部合成推导值**（几百个可合成原版物品）→ 目录不完整（Bug B）、客户端 tooltip 本地算出的有价物品服务端 `quote()` 查不到 → `FREE_ITEM` 卖不了（Bug A）。修复三层：① `PokeEMC.onServerStarted` 在 `computeAll()` 后显式 `ExchangePriceService.forServer().rebuild()`；② `PKMManager` 新增 **VERSION 版本号**（`setManual`/`setComputed`/`clearComputed` 递增），`catalog()`/`quote()` 检测版本变化**惰性重建**（生产 live 实例），任何未来时序变化不再造成服务端 quote 与目录失配；③ `quote()` 改经 `catalog()` 取数（原直接读字段绕过检测——服务端 `sell()` 恰走 `quote()`，这是第②层里必须修的点，单测回归捕获）。[CHANGED] `PokeEMC.java`/`PKMManager.java`/`ExchangePriceService.java`。
- **ADR-26（Bug B/C 商品覆盖不足 = 默认定价缺口）**：`VANILLA_BASE` 原仅 37 项，绝大多数原版物品靠合成树推导；而**掉落物/锻造/无配方物品**（各系木材的 stripped 变体、铜、紫水晶、1.21 新物品等）永远无法推导。修复：扩充 `DefaultPkmValues.VANILLA_BASE`——木材全系（8 种 log + planks + stripped）、下界木、竹、基础石材（深板岩/花岗岩/闪长岩/安山岩/凝灰岩/方解石/滴水石/玄武岩/黑石/地狱岩/末地石）、`copper_ingot`/`amethyst_shard`/`clay_ball`/`glowstone_dust`、常见掉落/作物、以及 **1.21/1.21.1 新道具**（`breeze_rod`=2048、`wind_charge`=512、`heavy_core`=16384、`mace`=18432、`wolf_armor`=128、`armadillo_scute`=64、`trial_key`=4096、`ominous_trial_key`=8192、`ominous_bottle`=64）。`PkmRecipeCalculator` 计算类型加入 `RecipeType.SMITHING`：`mace`（breeze_rod+heavy_core）可自动推导，镶饰/下界合金升级因 template 无价自然跳过（安全）。新增单测 `DefaultPkmValuesTest` 锁定覆盖。[CHANGED] `DefaultPkmValues.java`/`PkmRecipeCalculator.java`。
  - **[BREAKING CHANGES]** 行为变更：大量此前无价的原版物品（各系木材/铜制品/1.21 新道具等）现在**有价**并可出现在交易所目录、可被转化桌存入——服务端无需迁移，旧存档直接生效；新增价格均经防套利门（buy≥sell）。
- **ADR-27（Bug D 根因 = 容器子类未调用 `renderTooltip`）**：javap 实证 1.21.1 `AbstractContainerScreen.render()` **只更新 `hoveredSlot`、从不调用 `renderTooltip()`**——vanilla 约定由每个容器子类在 `render()` 末尾显式调用（如 ChestScreen `super.render()` 后接 `this.renderTooltip()`）。`TransmutationTableScreen`/`CondenserScreen` 未覆盖 `render()`，故全 UI（含背包物品、存入/取出槽）**永远不显示悬停提示**；`ExchangeScreen`/`StorageBrowserScreen` 覆盖了 `render()` 但遗漏末尾调用，同样无提示。修复：① `TransmutationTableScreen`/`CondenserScreen` 新增 `render()` override 末尾调 `this.renderTooltip(g, mouseX, mouseY)`（原始坐标）；② `ExchangeScreen`/`StorageBrowserScreen` 在 `endScaledRender()` **之前**（缩放矩阵态）调 `this.renderTooltip(g, lmx, lmy)`（局部坐标，与 `super.render` 的 `hoveredSlot` 命中坐标一致）。[CHANGED] `TransmutationTableScreen.java`/`CondenserScreen.java`/`ExchangeScreen.java`/`StorageBrowserScreen.java`。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 四 Bug 全部修复并通过全量回归：`compileJava/compileTestJava` → `:test` 全量 → `runGameTestServer`（40/40）→ `build --offline`。
- [x] 新增回归单测：`ExchangePriceServiceTest.liveCatalogTracksPkmVersionChanges`（Bug A）、`DefaultPkmValuesTest`（Bug B/C）。
- [ ] **已知边界**：客户端目录为打开 UI 时的快照，游戏内数据包重载（`/reload`）后需重新打开交易所/转化桌才会拉到新目录；服务端 `quote()` 已实时（版本检测），不会误售失败，仅展示层短暂旧价。可后续立项为目录推送/失效广播。
- [ ] 1.20.x 旧版本物品（如 `calibrated_sculk_sensor` 等）定价由合成树覆盖，若个别掉落物仍无价可续补 `VANILLA_BASE`（可维护项）。
- [x] **崩溃缺陷（会话 #6 续）**：Bug D 修复使 `ExchangeScreen.renderTooltip` 首次真正执行，暴露既有潜在 bug——`Component.translatable("poketrade.storage.permissions", perms)` 传入 `StringBuilder`，MC 要求 args 为 Component/Number/Boolean/String 单值，悬停仓储表头即抛 `IllegalArgumentException: TranslatableContents' arguments...` 崩溃（crash-2026-08-09_12.13.12）。已改 `perms.toString()`；其余分支（source/cart）参数均合法，其他 screen 用 `.getString()` 拼接无此问题。`gradlew build --offline` 全绿。

### [2026-08-09 12:55] 会话 #7 — 三 Bug（转化桌 Shift 放入卡槽 / 交易所分类显示英文 / 交易所 tooltip 样式不一致）

### 🎯 本次需求
玩家反馈三个新问题（按首字母编号）：
1. **Bug E**：按 Shift 不能直接把道具放入转化桌（疑似不支持组合键）。
2. **Bug F**：显示「原版」二字的地方出现英文字样，疑似转码乱码，需核实真实原因。
3. **Bug G**：交易所的指针信息（tooltip）样式与背包（原版）的不一致。

### 📐 架构决策记录 (ADR)
- **ADR-28（Bug E 根因 = 输入槽被无价值/入账失败物品卡死，Shift 放入全失效）**：javap 实证 1.21.1 的 Shift 交互链路完整正常——`AbstractContainerScreen.mouseClicked` 检测左 Shift（key 340/344）→ `ClickType.QUICK_MOVE` → 服务端 `quickMoveStack` → `AbstractContainerMenu.moveItemStackTo`（整组放入用 `p_38904_.split()` 真实扣减源槽）→ `Slot.setByPlayer` → `set` → `setChanged` → 输入槽 `onInputChanged` 换算入钱包。真正的缺陷在 **`onInputChanged` 的两个静默返回分支**：物品无 PKM 价值（`value <= 0`）或 Pixelmon 钱包入账失败时物品**留在输入槽不清空**——输入槽（容量 1）被永久占用后，任何后续 Shift/拖拽放入都会因 `moveItemStackTo` 目标槽非空而失败，表现为「按 Shift 不能放入」。修复：新增 `refundToPlayer()`，无价/溢出/入账失败三种路径均把物品 `moveItemStackTo` 退还背包（失败留槽并系统消息提示，防丢物品），输入槽恒保持可用。[CHANGED] `TransmutationTableMenu.java`（onInputChanged + 新增 refundToPlayer，补充导入 `net.minecraft.network.chat.Component`）。
- **ADR-29（Bug F 根因 = 服务端固化英文分类名，非转码问题）**：全量排查 zh_cn.json/en_us.json 键完全对齐（无缺键、无 GBK 转码乱码）——「原版二字变英文」的真实原因是 `ExchangePriceService.categoryOf()` 用服务端 `tab.getDisplayName().getString()` 固化分类名：服务端无语言包，`getString()` 必然返回英文（如 "Building Blocks"），客户端直接显示。修复：① `categoryOf()` 改为提取**可翻译键**（`display.getContents() instanceof TranslatableContents tc ? tc.getKey() : display.getString()`，如 `itemGroup.buildingBlocks`），非翻译型标签（模组 literal 名）回退原字符串；② 客户端新增 `ExchangeScreen.categoryLabel()` 辅助——`Component.translatable(key)` 按客户端语言本地化（zh_cn → 「建筑方块」），`unknown` 保持字面，无语言键时 fallback 原样。tooltip 来源行与「分类：」循环按钮两处显示均改走 `categoryLabel`。[CHANGED] `ExchangePriceService.java`/`ExchangeScreen.java`。
- **ADR-30（Bug G 根因 = 自定义 tooltip 未传 ItemStack，无物品图标）**：原版背包 tooltip（基类 `super.renderTooltip` → `getTooltipFromContainerItem`）带**物品图标**；交易所自定义 tooltip 用 `g.renderTooltip(font, lines, Optional.empty(), x, y)`（无 ItemStack 重载）→ 无图标。修复：catalog 格/仓储槽/购物车格三处自定义 tooltip 改传 `ItemStack s`（`renderTooltip(font, lines, Optional.empty(), s, x, y)` 带图标重载），观感与背包一致；仓储表头/存入格提示（纯文本无具体物品）保持无图标。[CHANGED] `ExchangeScreen.java`。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 三 Bug 全部修复，验证链全绿：`compileJava/compileTestJava` → `:test` 全量 → `runGameTestServer`（**40/40**）→ `build --offline`。
- [ ] **Bug E 回归说明**：`onInputChanged` 换算依赖 Pixelmon `BankAccountProxy`，GameTest/纯 JVM 环境无 Pixelmon registry 且类加载不可达，未新增自动化单测；逻辑经 javap 反编译 `moveItemStackTo`/`Slot.setByPlayer`/`AbstractContainerScreen.mouseClicked` 三层实证 + 编译通过。待接入支持幂等钱包或 Pixelmon 可用测试环境后补测。
- [ ] **Bug F 边界**：目录分类现为翻译键（`itemGroup.*`），客户端 `Component.translatable` 对无语言包的模组 tab 会 fallback 显示 key 本身（可读性劣于原英文名）；服务端目录重建（数据包 reload）后需重开交易所 UI 生效（同 ADR-25 已知边界）。
- [ ] **Bug G 边界**：catalog/仓储/购物车 tooltip 已带图标；交易所「出售预览弹窗」行仍为 `drawString` 文本列表（非 tooltip），未纳入本次样式统一。

### [2026-08-09 13:16] 会话 #8 — 批量出售弹窗预览穿模（z 层级被物品深度剔除）

### 🎯 本次需求
1. 玩家反馈：批量出售弹窗预览的图层优先级太低，比交易物品列表、钱包、页数要低，直接导致穿模。
2. 顺带查询：仓储转移存在（疑似）自动吸附效果，核实是否正常。

### 📐 架构决策记录 (ADR)
- **ADR-31（批量出售弹窗穿模根因 = GUI 深度方向 + RenderType.gui 自带 LEQUAL 深度测试）**：反编译实证 1.21.1 GUI 主投影 `setOrtho(0, width, height, 0, 1000, 21000)` + modelview `translate(0,0,-11000)`，深度随世界 z 单调递增；`GuiGraphics.renderItem` 内部 `pose.translate(..., 150 + ...)` 把物品图标画在 **z=150（深度≈0.49）**，而 modal 背景用 `g.fill`（`RenderType.gui()`，默认 z=0 → 深度≈0.5）**比物品更远**。关键点：`RenderType.gui()` 的 CompositeState 自带 `.setDepthTestState(LEQUAL_DEPTH_TEST)` 且默认 COLOR_DEPTH_WRITE——endBatch 绘制时会用其 RenderStateShard **覆盖**外层 `RenderSystem.disableDepthTest()`，故此前 modal 块的 flush+disableDepthTest 处理无效：弹窗背景在 z=0 被物品深度（LEQUAL 0.5 ≤ 0.49 失败）**剔除不绘制**，只剩无深度测试的 text buffer（文字）悬浮在下层物品/钱包/页数之上，即"穿模"。（顺带更正：此前注释假设"renderItem 用独立 buffer"不成立——GUI 的 fill/text/物品图标全走同一个 sharedBuffer，endBatch 顺序与深度共同决定层级。）修复：modal 与右键菜单两块绘制前 `g.pose().translate(0,0,400)` 把弹窗整体提升到 **z=400（深度≈0.48，近于一切下层元素）**，LEQUAL 深度测试通过、背景得以绘制并盖住列表/钱包/页数；text 为 NO_DEPTH_TEST 不受影响。GUI 主循环每帧 `RenderSystem.clear(256)`（DEPTH_BUFFER_BIT）清深度，无跨帧残留副作用。[CHANGED] `ExchangeScreen.java`。
- **ADR-32（仓储转移"自动吸附"= 设计行为，非缺陷）**：查询确认 `StorageDepositPacket.findDepositSlot` 的自动找槽逻辑（优先同 ID 可堆叠槽 → 空槽）即"自动吸附"来源，属既定设计；已知边界：`MinecraftSlotStore.set()` 用 `new ItemStack(item, count)` 重建会丢弃 NBT 组件（有 NBT 的物品在自动找槽路径下组件丢失），维持既有语义不改代码，列入 TODO 评估。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 穿模修复完成，验证链全绿：`compileJava` → `:test` 全量 → `runGameTestServer`（**40/40**）→ `build --offline`。
- [ ] **UI 层修复依赖运行时验证**：本修复基于反编译实证（投影矩阵/深度映射/RenderType state），GameTest 无法覆盖客户端渲染；请在游戏中打开交易所→批量出售弹窗，确认弹窗背景不透明盖住中间列表/钱包/页数、无穿模，且右键菜单同样正常。
- [ ] 自动找槽 NBT 丢弃边界：如后续支持 NBT 物品自动入仓，需在 `findDepositSlot`/`simulateInsert` 增加组件比较（可维护项）。
