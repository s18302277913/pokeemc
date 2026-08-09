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

### [2026-08-09 13:34] 会话 #9 — 仓储搜索范围 UI 改造为点击切换按钮（16→…→648→16）

### 🎯 本次需求
玩家反馈：优化仓储搜索范围 UI，由输入框改为**点击切换按钮**——点一下范围翻倍，最大 648 后重置为默认值 16。

经确认：目标界面为交易所/转化桌界面（ExchangeScreen）；最大挡位 648，需同步提升服务端扫描预算上限。

### 📐 架构决策记录 (ADR)
- **ADR-33（范围 UI 由自由输入改为挡位循环按钮）**：移除 `ExchangeScreen` 的 `radiusBox`（EditBox 自由输入）与其全部键盘/布局/渲染接线，改为左栏「范围：」标签 + 点击切换按钮。按钮复用 `PeStyle.segmented`（selected 高亮展示当前生效值），点击区域即原输入框矩形 `layout.radiusInput()`（`left.x+34, left.y+2, 92×12`）。循环序列收敛到单一静态来源 `ExchangeUiModel.STORAGE_RADIUS_STEPS = {16, 32, 64, 128, 256, 512, 648}`，`nextStorageRadius()` 每击取下一个更大挡位、末档绕回 16，非挡位残留值（历史/未知状态）跳到下一个更大挡位保证循环不中断。[CHANGED] `ExchangeScreen.java`（移除 radiusBox 字段/接线，新增 `cycleStorageRadius()`，renderLeftPanel 用 segmented 渲染，构造中 `storage.setRadius(16)` 锁定默认挡位）、`ExchangeUiModel.java`（新增 STORAGE_RADIUS_STEPS + nextStorageRadius，替代原 16→32→64→16 死代码序列）。
- **ADR-34（为 648 挡位同步提升服务端 clamp 链，三处上限一致化）**：交易所最大挡位 648 需整条链路放行，避免服务端静默截断造成「按钮显示 648、实际扫描 128/512」的幻觉。三处上限同步 512/128→648：① `StorageConfig.MAX_PLAYER_RADIUS`/`MAX_ADMIN_RADIUS`（原 128/256，玩家与管理员上限统一为 648——大范围扫描受 `MAX_SCANNED_PER_QUERY=2000` 硬上限保护，超预算部分标记结果不完整，不会无限扫描）；② `QueryStoragesPacket.MAX_RADIUS`（原 512，服务端 `executeQuery` 仍 `Math.max(1, Math.min(radius, 648))` 二次钳制）；③ `StorageViewModel.MAX_RADIUS`（原 512，客户端展示/钳制上限）。`StorageBrowserScreen`（独立浏览器）未改 UI，仅间接允许其输入到 648。[CHANGED] `StorageConfig.java`、`QueryStoragesPacket.java`、`StorageViewModel.java`。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 改造完成，验证链全绿：`compileJava` → `:test` 全量（642 项，含新增 `storageScopeSnapsNonStepValuesToNextLargerStepOrDefault`，并修正两处引用旧上限 512/128 的断言为常量引用）→ `runGameTestServer`（**40/40**）→ `build --offline`。
- [ ] **性能风险注意**：648 半径扫描（约 1.3M 方块区）依赖区块加载与 `MAX_SCANNED_PER_QUERY=2000` 硬上限截断；实际游戏若发现大范围查询造成服务端卡顿，可将 `StorageConfig.MAX_PLAYER_RADIUS`/`MAX_ADMIN_RADIUS` 回调为 512（同时降 `StorageViewModel`/`QueryStoragesPacket` 上限，注意三者一致），或保留 UI 上限仅收紧服务端预算。
- [ ] **UI 交互依赖运行时验证**：GameTest 无法覆盖客户端渲染/点击，请在游戏中打开交易所→点击「范围」按钮确认依次 16→32→64→128→256→512→648→16 循环、每击触发重新扫描，且按钮高亮与文本正常。

### [2026-08-09 13:57] 会话 #10 — Shift 直接贩卖（物品/物品组）+ 左右 Shift 键位归属隔离配置

### 🎯 本次需求
玩家反馈：期望的「Shift 直接贩卖物品与物品组」从未生效，怀疑与左栏仓储的 Shift 交互冲突，建议加隔离与可配置项（或区分左右 Shift）。

### 📐 架构决策记录 (ADR)
- **ADR-35（"Shift 贩卖没生效"根因 = 该功能从未实现，非冲突）**：`git log -S` 与全量 grep 实证从基线提交起 `hasShiftDown()` 仅两处用途——目录拖拽数量 ×64（`ExchangeScreen:906`）、左栏仓储 Shift=取出（`:1327`）；背包区 Shift+点击落入原版 `QUICK_MOVE` → `ExchangeMenu.quickMoveStack`（`ExchangeMenu:247-274`）只做背包内部搬移，菜单层无任何出售逻辑。故按用户三项确认实现为**新增功能**：① 键位归属=左右 Shift 区分 + 客户端配置 `shiftSellHand`（OFF/LEFT/RIGHT，默认 LEFT）；② 语义=Shift+左键卖整叠、Shift+右键卖背包+副手同 ID 全部；③ 二次确认=价值 ≥ `requireConfirmValue`（默认 10 万）复用出售预览 modal 确认，否则直接发包。
- **ADR-36（左右 Shift 天然隔离 + 可配置归属；`hasShiftDown()` 无法区分左右，改用 GLFW 键位）**：新增客户端 ModConfigSpec（`PokeTradeConfig.CLIENT_SPEC`，写入 `config/poketrade-client.toml`，仅物理客户端加载，getter 带 `isLoaded()` 守卫回退 LEFT）。`Screen.hasShiftDown()` 对左右 shift 做或运算不可区分，故 `shiftSellActive()` 用 `InputConstants.isKeyDown(window, GLFW_KEY_LEFT_SHIFT/RIGHT_SHIFT)` 按配置实时查询；仓储取出改用**非贩卖键**那只（`storageWithdrawShift()`：LEFT→查右 Shift、RIGHT→查左 Shift、OFF→任意 Shift），实现"左 Shift 点背包=卖、右 Shift 点仓储=取出"的隔离。mouseClicked 拦截点选在左栏处理之后、拖卖记录段之前：条件 `shiftSellActive() && (button==0||1) && !workflow.pending() && carried.isEmpty() && inventoryRect.contains`，`return true` 短路原版 QUICK_MOVE/取一半。
- **ADR-37（统一门控 + 复用 SellPreview.scan，直卖不经 modal 无闪烁）**：整叠/整组都收敛到 `shiftSell(SourceLine)`——经 `SellPreview.scan` 过滤黑白名单/无价（黑名单/白名单拦截给出本地 `sell.blocked` 文案，避免服务端报「未知物品」的困惑）；未超阈值直接 `sendInventorySell`（新方法，`workflow.begin` 防重复，与 `sellSingleDirect` 平行、不改现有方法），超阈值才置 `sellPreview` 弹 modal（复用 confirmPreview 的"第一击置位、第二击发包"）。`SellPreview.scan` 聚合与服务端 `sellFromInventory`（`countInInventory` 含副手、无单行数量硬上限）天然匹配，卖组单行 ≤2368 直接发送。
- **ADR-38（新增纯静态可测逻辑 + 守卫断言）**：`ExchangeUiModel.groupCount`（卖组同 ID 聚合）与 `ExchangeUiModel.shiftSellNeedsConfirm`（`confirmThreshold<=0 || count<=0` 恒 false，与 scan 的 `>0` 守卫一致，保证配置 0=关闭确认生效；`multiplyExact` 溢出按需确认）均为纯函数便于 JUnit；`PokeTradeConfigTest.shiftSellHandFallsBackToLeftWhenClientSpecUnloaded` 锁定 CLIENT spec 未加载时守卫回退。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 功能完成，验证链全绿：`compileJava` → `:test` 全量（645 项，含新增 3 个测试）→ `runGameTestServer`（**40/40**）→ `build --offline`。
- [ ] **UI 交互依赖运行时验证**：GameTest 无法覆盖客户端渲染/点击，请游戏中验证——交易所界面左 Shift+左键点背包物品=卖整叠、左 Shift+右键=卖整组；右 Shift+点击仓储槽=取出（不与贩卖冲突）；价值超 10 万弹预览需二次确认；改 `config/poketrade-client.toml` 的 `shiftSellHand=OFF/RIGHT` 后行为相应变化。
- [ ] **已知边界**：① 副手独有物品无槽位可点，无法触发 Shift 贩卖（可用批量出售按钮）；② 卖组按 itemId 聚合会把不同 NBT 的同 ID 物品一起卖（与现有 `sellInventory` 一致）；③ 贩卖键按下时拖卖记录不触发、拖动存入禁用（意图内）；④ 目录拖拽 ×64 仍用 `hasShiftDown()`（任意 Shift），贩卖键兼作 ×64 修饰键（既有行为）；⑤ 服务端单行路由 `sellFromCarried`（ExchangeSellPacket:100-112）的理论风险已由客户端 `carried.isEmpty()` 保护，未改协议。
- [ ] **文案跟随配置问题**：`zh_cn.json` 的 `snapshot.hint` 现写「取出/贩卖（左右Shift区分，可在配置调整）」为静态文案，无法反映玩家实际选择的 `shiftSellHand`；如后续需要精确提示，可改为渲染时动态拼装。

### [2026-08-09 14:15] 会话 #11 — 左侧仓储三连修复（自动展开 / 双箱格子一行 / 定价）

### 🎯 本次需求
玩家反馈左侧仓储三个问题：
1. **仓储收起后每隔约 10 秒自动展开**——怀疑与自动刷新/状态继承有关。
2. **兼容双箱后格子反而减少为一行**——双箱展开后只显示 1 行格子。
3. **新道具价格显示为一元（测试价）+ 部分物品无价**——要求联网调研物品获取方式/作用性/稀有性后制定价格方案写入价格表。

### 📐 架构决策记录 (ADR)
- **ADR-39（问题 1 根因 = 10 秒自动刷新回包 × `expandedStorages.isEmpty()` 兜底误判首次打开）**：`ExchangeScreen.render()`（`ExchangeScreen:1896-1904`）有真实时间 10 秒自动刷新定时器，每次回包走 `onQueryResponse`（`:784-820`）；原 `:807-810` 的 `if (expandedStorages.isEmpty()) { expandedStorages.add(visible.get(0)...) }` 把「玩家把全部仓储收起后的空集」误判为「首次打开」，强制展开第一个仓储——全收起后每隔约 10 秒必复发。另 `:819` `accordionScroll = 0` 每次回包把手风琴滚动位置清零（次生 bug：列表滚下去后每 10 秒弹回顶部）。修复：新增可测纯类 `ExchangeUiModel.FirstQueryGate`（`received` 一次性置位门：首个回包且 visible 非空 + 全收起才返回 true 自动展开），`onQueryResponse` 改 `if (firstQueryGate.onQuery(...)) { 展开 + accordionScroll=0 } else { accordionScroll = clampScroll(...) }`——首个回包重置到顶，后续回包（自动刷新/手动刷新/换半径）保留滚动位置并安全钳制。已知边界：首包即空（远离仓储）时不自动展开，后续走近也不展开，需手动点开（记录在案）。[CHANGED] `ExchangeScreen.java`/`ExchangeUiModel.java`。
- **ADR-40（问题 2 根因 = 网格行数按快照「已占用槽数」而非「容器容量」）**：提交 `61edc06`（双箱兼容）把网格从固定 3 行改为按 `filteredSlots(snapshotsByStorage.get(id)).size()` ÷ `snapshotCols`(=7) 自适应；而服务端快照 `StorageHandleImpl.snapshot()` 只含非空槽（空槽被 `MinecraftSlotStore.itemId()` null 跳过），故箱子越空行数越少、空箱直接 `max(1, ceil(0/7))=1` 行。正确口径 `StorageDescriptor.slotCount()`（服务端正确上报 54/27）已存在于 `accordionEntries()` 的 descriptor，只是 `accordionGridRows(StorageId)` 未接收。修复引入两个量（Plan agent 核对的关键）：**未裁剪 `accordionContentRows(slotCount,cols)=max(1,ceil(slotCount/cols))`** 用于滚动范围（滚动条跳页 :1454、滚轮 :1734、渲染 scroll 钳制 :2605）——若此处也裁剪，双箱 54→8 行但可见仅 7 行时 `maxOffset=0`，第 8 排永远不可达（Bug #1 复发）；**裁剪 `accordionVisibleRows(slotCount,cols,maxRows)`** 用于展开面板高度（`accordionGridRows` 改收 descriptor）。`accordionSlotAt`（按 `index < slots.size()` 命中空位返回 null）保持不动。行为：单箱 4 行、双箱 7 可见行 + 可滚第 8 排、空/半空箱显示容量骨架；搜索/过滤只筛物品不改变网格高度；快照未加载时仍按容量铺满骨架。[CHANGED] `ExchangeScreen.java`/`ExchangeUiModel.java`。
- **ADR-41（问题 3 定价 = 分层定价 + 补无价缺口；「显示为一元」的 31 件 Pixelmon 物品重定价）**：联网调研 Pixelmon wiki 确认获取方式/作用/稀有度（shopkeeper 收购价锚点：Nugget=750、Stardust=500、Big Pearl=1000、Pearl String=1500、Comet Shard=1500、Strange Souvenir=1500、Rare Bone=1000；Relic 系列来自 Stronghold 图书馆宝箱——Band 11.1%/Crown/Statue 5.6%，官方游戏中 Crown 价值最高 $300,000）。用户确认**分层定价**：易得基础（Forage/挖掘/草丛）256~768、中等 1024~2048、稀有 3072~8192、Relic 顶级 16384~65536。`shop.json` 31 件 `value:1` 全部按此写入（如 `stardust=512`、`nugget=1024`、`comet_shard=4096`、`relic_crown=65536`）。原版 `vanilla.json` 40 件 =1 中，树叶/泥土/石头/沙子/死珊瑚/草/雪/冰等 39 件**保持 1**（ProjectE EMC 惯例合理，非测试价），仅 `minecraft:kelp` 1→8 消除与 `DefaultPkmValues` 兜底（kelp=8）的不一致。**补无价缺口**（用户确认范围）：`sculk_sensor=1024`（古城探索、无配方；`calibrated_sculk_sensor` 由 sculk_sensor+紫水晶配方自动推导无需手填）、三色马铠（宝箱掉落 `iron=1024/golden=4096/diamond=8192`）、20 种 `pottery_shard=16`（考古/沙漠神殿装饰品）。全部为**纯数据改动**：改 JSON 后 `/reload` 或重启即生效（`PkmDataLoader.setManual` → `VERSION++` → `ExchangePriceService.catalog()` 惰性重建），`exchange/prices.json`（覆盖价，仅 master_ball）无需改动。[CHANGED] `data/poketrade/pkm/shop.json`/`vanilla.json`。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 三连修复完成，验证链全绿：`compileJava` → `:test` 全量（**650 项**，含新增 accordionContentRows/accordionVisibleRows/FirstQueryGate 三个场景共 5 个测试）→ `runGameTestServer`（**40/40**）→ `build --offline`。
- [ ] **UI 交互依赖运行时验证**：GameTest 无法覆盖客户端渲染/点击，请游戏中验证——① 交易所内收起全部仓储后等待 10 秒自动刷新，确认不再自动展开、手风琴滚动位置不被弹回顶部；② 空箱/双箱展开显示容量骨架（单箱 4 行、双箱 7 可见行，物品少于整行时仍显示空槽背景），双箱物品填满时滚轮/滚动条可滚到第 8 排；③ 打开 pkm 目录确认 31 件 Pixelmon 物品已分层标价、`sculk_sensor`/陶器碎片/马铠出现在目录且可出售。
- [ ] **定价为经济平衡主观决策**：本次按用户确认的「分层定价」落地；若服务器经济实测异常（刷钱/物价过高），可在 `data/poketrade/pkm/shop.json` 单独调整（数据包为权威，重启或 `/reload` 生效）。
- [ ] **已知边界（问题 1）**：首包即空（首次打开时远离任何仓储）不会自动展开首个仓储，需手动点开——若希望"首次见到仓储才展开"，需把 `FirstQueryGate` 的 received 置位改为「首个非空回包」才消费（当前为无条件一次性置位）。

### [2026-08-09 15:05] 会话 #12 — 出售预览价格穿模修复 + 交易所文字模糊根治（屏幕空间重画）

### 🎯 本次需求
玩家反馈两个 UI 问题：
1. **批量出售预览表的价格与名称离得太远，价格超一定 x 位数时就穿模**——价格列溢出弹窗右边界。
2. **当前交易所 UI 的文字样式显得很模糊**——要求更换一种文字样式解决。

经读码确认根因，用户确认修复方向：问题 1 = **价格右对齐**（弹窗右缘锚定，名称动态截断，永不重叠）；问题 2 = **文字屏幕空间重画 + 缩放原点取整**（保留默认字体，不换字体资源，所有缩放档位下文字像素级清晰）。

### 📐 架构决策记录 (ADR)
- **ADR-42（问题 1 根因 = 价格列左对齐固定 x + 名称截断不含数量后缀）**：`renderSellPreviewLabels` 原实现 `subtotal` 左对齐固定 `x = modal.right()-24`（=311），`ExchangeUiModel.formatAmount`（`%,d` 千分位）多位（如 `5,000,000`）时宽度超过 311→335 余量而溢出右边界；名称截断 `textWidth-74` 只对 displayName，不含 `×数量` 后缀，视觉上「名称与价格相距过远」。修复：价格**右对齐**到 `modal.right()-24`（`priceX = modal.right()-24 - font.width(subtotal)`，subtotal 完整千分位、仅超可用宽度 `modal.right()-24-lines.x()`=166px 才截断兜底）；名称**整行**（名称 ×数量）按 `priceX - lines.x() - 6` 截断，与价格永不重叠、短价格时名称可延展更宽。几何收敛到可测纯函数 `ExchangeUiModel.previewRowLayout(modal, lines, subtotalWidth)`（返回 `PreviewRowLayout(priceX, nameMax)`），便于 JUnit 断言。[CHANGED] `ExchangeScreen.java`（renderSellPreviewLabels 循环体）、`ExchangeUiModel.java`。
- **ADR-43（问题 2 根因 = 缩放矩阵 float 原点 + 非整数缩放 → 字形落分数像素）**：`beginScaledRender`（`ExchangeScreen:269-283`）`scaledOriginX/Y = (window - image*uiScale)/2f` 为 **float**（奇数窗口尺寸产生 .5 像素平移），叠加 `uiScale∈{1.0,0.75,0.5}` 非整数缩放 → 字形落在分数像素被线性采样 → 模糊。根治采用**「几何照旧、文字记录后重放」**（D1，刻意不拆 renderLabels 为 Geom/Text 双胞胎——会导致按钮 enabled/selected/hovered、字符串拼装逻辑复制漂移）：矩阵内所有 `g.drawString`/`PeStyle.button` 改为记录到 `pendingText` 列表（含局部坐标 + `TextLayer{MAIN,TOP}` 层级），矩阵内只画几何；`endScaledRender` 之后以 `screenX/screenY = Math.round(scaledOriginX + local*uiScale)` 换算成**整数屏幕坐标**统一重放。`beginScaledRender` 原点改为 `Math.round(...)` 取整，与 screenX/Y 共用同一整数值 → 1.0 档 `screenX(local)-origin == local` 恒为整数（与改动前逐像素一致）、0.75/0.5 档文字落在整数像素。[CHANGED] `ExchangeScreen.java`（新增 TextLayer/TextDraw/pendingText/screenX/screenY/recordText/recordButton/recordSegmented/drawPendingText；render() 主流程拆分、矩阵外重放 + tooltip 移出）、`PeStyle.java`。
- **ADR-44（PeStyle 纯新增，不动现有方法）**：`StorageBrowserScreen`(6 处)/`TransmutationTableScreen`(1 处) 仍调 `PeStyle.button/segmented`，故保留原方法，新增 `buttonBg()`/`segmentedBg()`（画背景 + 命中，不画文字）与 `buttonText()`/`segmentedText()`（返回 `ButtonText`/`SegmentedText` record：label 截断 + 居中局部坐标 + enabled/selected 取色），排版公式与旧 `button()` 内部逐字一致，由 `ExchangeScreen.recordButton/recordSegmented` 消费。[CHANGED] `PeStyle.java`。
- **ADR-45（层级与取舍）**：① **TOP 层重放**——弹窗/右键菜单文字记录为 `TextLayer.TOP`，矩阵外以 `z=400 + disableDepthTest + flush` 同款纪律重放（MAIN 文字 z=0 在弹窗区域被 LEQUAL cull 不透出，行为与现状一致）；② **tooltip 移出矩阵**——`render()` 中 `renderTooltip` 移到 `endScaledRender` 之后传屏幕坐标 `mx/my`，方法内 `x = toLocalX(mouseX), y = toLocalY(mouseY)` 换算命中（与原 lmx/lmy 恒等），tooltip 文字/图标像素级清晰；③ **EditBox 内部文字/光标 + 2 处 hint（searchBox/storageSearchBox）刻意保留矩阵内**——vanilla `EditBox.render` 文字/背景强耦合，剥离需重写输入坐标映射，风险高收益低，且 hint 与输入文字同尺寸保持一致；④ **物品槽位数量（renderItemDecorations）与物品图标、拖拽浮动物品接受矩阵内缩放**——与槽位精灵/基类强耦合，数量多位少数。[CHANGED] `ExchangeScreen.java`（render() 全部调用点、renderTooltip 命中换算）。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 两问题修复完成，验证链全绿：`compileJava` → `:test` 全量（**652 项**，含新增 `previewRowLayoutRightAlignsPriceAndReservesNameGap`/`previewRowLayoutNameNeverOverlapsPriceAtAnyPxWidth` 两个穿模几何测试）→ `runGameTestServer`（**40/40**）→ `build --offline`。
- [ ] **UI 交互依赖运行时验证**：GameTest 无法覆盖客户端渲染/点击，请游戏中验证——① 三种窗口缩放档位（1.0/0.75/0.5）下主面板/按钮/钱包/页码/购物车统计/左栏行名/「末」徽标/流程消息/出售提示/右键菜单/出售预览弹窗文字全部清晰、无糊边，1.0 档文字与面板对齐无 0.5px 偏移；② 批量出售预览：`Master Ball ×1` 与 `5,000,000` 不重叠、超长名称截断、价格不出弹窗右缘；③ 弹窗打开时主界面文字被正确遮挡，tooltip 无弹窗时清晰、有弹窗时被抑制（早退逻辑不变）；④ 搜索框输入文字与占位提示尺寸一致（同为缩放态）。
- [ ] **已知边界（文字清晰化的剩余模糊面）**：EditBox 输入文字/光标、物品槽位数量徽标、物品图标、拖拽浮动物品仍随缩放矩阵渲染（ADR-45 取舍）；0.75/0.5 档下这些元素随 UI 缩小，属缩放开方案固有特性，非本次文字目标。若后续需要，可将 EditBox 改为自定义渲染（剥离 vanilla 内部文字）彻底像素对齐。

### [2026-08-09 15:24] 会话 #13 — 仓储五连修复（球类降级 / tooltip 价格 / 末影箱翻译 / 分类 unknown）

### 🎯 本次需求
玩家反馈仓储的 5 个 bug：
1. **大师球放进箱子变成精灵球**——怀疑 id 复用导致（球类组件丢失）。
2. **仓储指针（槽位 tooltip）不显示价格**。
3. **末影箱指针信息「所有者」列显示超长键名** `poketrade.storage.type.vanilla_ender_chest`——缺翻译键。
4. **分类点击后显示「分类: unknown」**。
5. **交易物品列表指针信息显示「[unknown]」分类**——前半段不对，模组名后半段正常。

### 📐 架构决策记录 (ADR)
- **ADR-46（Bug 1 根因 = Pixelmon 球类共用 `pixelmon:poke_ball` 注册键，球种存于 DataComponent；itemId 链路重建栈丢组件）**：Pixelmon Reforged 9.x 所有精灵球 registry id 都是 `pixelmon:poke_ball`，球种由 `PokeBall` DataComponent（`PixelmonDataComponents.POKE_BALL`）区分。旧 itemId 只取 registry key，`new ItemStack(item, count)` 重建 → 组件丢失 → 大师球降级精灵球。修复：新建 `PokeballIdentity`（`com.pokeemc.storage.adapter`）共享编解码——`encode(stack)` 对 `PokeBallItem` 输出 `pixelmon:poke_ball#master_ball`（`#` 后跟 `PokeBall.getName()`），普通物品保持原注册键；`decode(itemId, count)` 按 `#` 拆分，球类走 `PokeBallRegistry.getPokeBall(ballKey)` + `PokeBallItem.of(value.get(), count)` 还原组件，**未初始化/未知变体抛异常返回 null，绝不静默降级成普通精灵球**；`baseItem(itemId)` 取 `#` 前基础物品（供 maxStackSize）。[CHANGED] `PokeballIdentity.java`（新）、`MinecraftSlotStore.java`（itemId/maxStack/set 全走编解码）。商品出售遇 `#` id 时 `TradeItemId.parse` 抛异常 → `ExchangeService` 返回「无价格不可出售」优雅降级不崩溃（价格表无球种维度，记录为已知限制）。
- **ADR-47（Bug 1 全链路排查：除 `MinecraftSlotStore` 外，拖入存入两处服务端 itemId 也绕过了编码）**：`StorageDepositCarriedPacket.executeAtSlot`（自动找槽 + 定点拖入）与 `StorageDepositPacket.inventoryItemId` 原用 `BuiltInRegistries.ITEM.getKey(...)` 取字符串——大师球拖入同样丢组件。三处统一改 `PokeballIdentity.encode(carried/stack)`，null 时返回 `invalid_request`。GameTest 新增 `carriedDepositKeepsPokeballVariant`（真实拖入路径，40→41 项）与 `pokeballVariantSurvivesStorageRoundTrip`（容器往返，41→42 项）。[CHANGED] `StorageDepositCarriedPacket.java`/`StorageDepositPacket.java`/`StoragePacketGameTests.java`。
- **ADR-48（Bug 2 根因 = 仓储槽位 tooltip 无价格行）**：`ExchangeScreen.renderTooltip` 仓储槽位块只渲染名称 + hint，未查目录价格。修复：遍历 `catalog` 的 `EntryWire` 按 itemId 命中买价/卖价，命中追加「买价/卖价」两行（复用 `ExchangeUiModel.formatAmount`），未命中追加「暂无定价」。新增 `poketrade.exchange.storage.no.price` 翻译键（zh「暂无定价」/ en「No price set」）。[CHANGED] `ExchangeScreen.java`/`zh_cn.json`/`en_us.json`。
- **ADR-49（Bug 3 根因 = 缺翻译键，非编码问题）**：`StorageBrowserScreen` 仓储类型名按 `poketrade.storage.type.<adapterType>` 查键，`vanilla_ender_chest` 从未补键 → 兜底渲染字面键名。补 `zh_cn.json`「末影箱」/`en_us.json`「Ender Chest」。[CHANGED] `zh_cn.json`/`en_us.json`。
- **ADR-50（Bug 4/5 根因 = 服务端不构建 CreativeModeTab displayItems，分类查找恒 unknown）**：服务端只在客户端打开创造菜单时构建 `displayItems`，`tab.contains()` 查的是 `displayItemsSearchTab`（1.21.1 搜索标签内容，重建后未必填充）。因此服务端 `categoryOf` 对每物恒返回 `"unknown"`，tooltip 模板 `[%s] %s` 渲染成字面 `[unknown]`。修复：① 首次分类查找经 `CreativeModeTabs.tryRebuildTabContents(FeatureFlags.DEFAULT_FLAGS, true, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY))` 显式重建一次（`ensureTabsBuilt`，volatile 守卫）；② `computeCategory` 遍历 CATEGORY tab 的 `getDisplayItems()` 用 `ItemStack.isSameItemSameComponents` 匹配，取 `TranslatableContents` 的 key（如 `itemGroup.buildingBlocks`），非翻译型标签回退原字符串；③ 按 `Item` 建 `ConcurrentHashMap` 缓存避免每次目录重建重复扫描。分类键由客户端按语言文件本地化（MC 内置 `itemGroup.*` zh 键）。[CHANGED] `ExchangePriceService.java`。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 五连修复完成，验证链全绿：`compileJava` → `:test` 全量（**652 项**）→ `runGameTestServer`（**42/42**，含新增球类往返 + 拖入大师球回归）→ `build --offline`。
- [ ] **UI 交互依赖运行时验证**：GameTest 无法覆盖客户端渲染/点击，请游戏中验证——① 大师球（及其他球种：高级球/究极球/纪念球等）放入箱子再取出，仍是原球种不降级；拖入存入同样；② 仓储槽位 tooltip 显示买价/卖价或「暂无定价」；③ 末影箱在附近仓储列表显示「末影箱」而非键名；④ 交易所目录/交易列表分类显示真实分类（如「建筑方块」「战斗用品」等本地化文本）而非 unknown。
- [ ] **分类键本地化依赖语言文件**：`computeCategory` 返回 `itemGroup.*` 等键，客户端需持有对应语言文件才能本地化；若某模组 tab 名无语言条目，客户端回退显示原键（不崩溃）。Pixelmon 等模组的自定义 tab 分类显示效果需运行时确认。
- [ ] **球类商品价格缺口**：`#` 编码后商品出售优雅降级「无价格不可出售」（价格表按 registry id 无法表达球种）。若需大师球可出售，需引入球种感知价格键（如 `pixelmon:poke_ball#master_ball`）——记录为 future TODO。
- [ ] **构建提示**：`PokeballIdentity` 有 Pixelmon deprecated API 警告（`PokeBallItem.of` 或 `RegistryValue.get` 之一，编译通过不阻塞）；已验证 `PokeBallItem.of`/`RegistryValue.get` 签名存在于 pixelmon.jar。

### [2026-08-09 15:55] 会话 #14 — 球类价格体系修复：球种感知价格键贯穿全链路（大师球 500万生效 + 交易列表补全）

### 🎯 本次需求
玩家反馈三个联动问题：
1. **大师球有价（覆盖价 500万）却显示「暂无定价」**。
2. **交易物品列表并不全**。
3. **价格表前后端好像又不同步了**。

经读码确认：三个 bug **同源**——价格表键是幽灵 id（`pixelmon:master_ball`/`pixelmon:great_ball` 等，Pixelmon 球类共用 `pixelmon:poke_ball` 注册键、球种在 DataComponent），而仓储/目录 itemId 已是球种感知键 `pixelmon:poke_ball#master_ball`（ADR-46 引入）。用户确认修复方向：**全部球类补全**（30+ 球种幽灵键全部迁到覆盖价，键改球种感知，buy=sell=现值），大师球 500万生效、全部可买可卖。

### 📐 架构决策记录 (ADR)
- **ADR-51（根因 = 价格体系用幽灵 id，与球种感知 itemId 脱节）**：`ExchangePriceService.isObtainable`（`BuiltInRegistries.ITEM.get("pixelmon:master_ball")`）对幽灵键查注册表失败 → 覆盖价与 PKM 球类条目全部被剔除 → 目录里根本没有大师球/高级球等 → tooltip「暂无定价」、交易列表只有普通精灵球。这是数据键与运行时 itemId 两套命名体系的系统性失配。[CHANGED]（见 ADR-52~54）。
- **ADR-52（`TradeItemId` PATH 正则加 `#` —— [BREAKING CHANGES] 语义扩展）**：`[a-z0-9/._-]+` → `[a-z0-9/._#-]+`，使球种感知键 `pixelmon:poke_ball#master_ball` 可 parse。向后兼容（旧 id 全部仍合法；`RegistryIdMigrationTest` malformed 列表不含 `#`），但「含 `#` 的 id 从非法变合法」是公共 API 语义扩展，故标注 [BREAKING CHANGES]。[CHANGED] `poketrade-api/TradeItemId.java`，新增 `#` roundTrip 断言。
- **ADR-53（球类价格载体从 PKM 迁移到覆盖价）**：PKM 快照键是 `ResourceLocation`（path 非法 `#`），天然无法表达球种 → 球类兜底价必须走覆盖价 `prices.json`（buy=sell=现值，无套利）。迁移 30+ 球种：`pixelmon:poke_ball#great_ball`=512、`#ultra_ball`=1024、`#heal_ball`=384、`#quick_ball`/`#timer_ball`=384、`#premier_ball`=256、`#friend_ball`/`#love_ball`/`#lure_ball`/`#heavy_ball`/`#level_ball`/`#moon_ball`=768、`#dream_ball`=1536、`#beast_ball`/`#cherish_ball`/`#christmas_ball`=2048、`#dive_ball`/`#net_ball`/`#nest_ball`/`#repeat_ball`/`#fast_ball`/`#dusk_ball`=640、`#safari_ball`/`#park_ball`=512、`#sport_ball`/`#luxury_ball`=1024、`#ancient_poke_ball`=512、`#ancient_great_ball`=1024、`#ancient_ultra_ball`=2048、`#ancient_heavy_ball`=1536，`#master_ball`=500万（原幽灵键改球种感知）。`PriceOverrides.MASTER_BALL` 硬校验键同步改球种感知（否则幽灵键校验永不触发、默认注入落在错误键）。pixelmon.json 删除球类幽灵键，保留 `pixelmon:poke_ball`（普通精灵球仍走 PKM 兜底 256）与 `pixelmon:air_balloon`（真气球形道具）。[CHANGED] `prices.json`/`pixelmon.json`/`PriceOverrides.java`。
- **ADR-54（全链路球种感知：所有 `ResourceLocation.parse/tryBuild` 调用点统一拆 `#`）**：统一经 `PokeballIdentity.baseItem`（base 注册表）/`decode`（带组件栈，未知球种 null）/`displayName`/`encode` 处理，不手写拆串：`ExchangePriceService.isObtainable`（幽灵键剔除根因）/`categoryOf`、`ExchangeCatalogPacket.isRealItem`（decode 校验球种，球类条目保留）/`itemDisplayName`（显示名搜索命中）、`TradeMarketService.itemOf`（baseItem）/`itemIdOf`（encode 编码球种，卖出匹配命中）/`buyBatch`（交付 decode 还原组件，买大师球不降级）、`StorageBrowserScreen.findInventoryTarget`（decode 样本，大师球可合并到背包）、`StorageWithdrawCarriedPacket`（取出还原组件）、`ExchangeScreen.stackOf`/购物车格/购物车 tooltip（`#` 键图标渲染，原 tryParse null → 空气）。`ExchangeService.sell` 无需改（正则放宽即 parse，`commitExtract` 只做槽位匹配不重建物品）。[CHANGED] 上述 8 文件。
- **[DEPRECATED]** 幽灵键价格表（`pixelmon:master_ball` 等）不再生效；若旧数据包残留幽灵键，覆盖价仍会加载但 `isObtainable` 剔除、不显示（不崩溃）。

### ⚠️ 遗留风险与待办 (TODOs)
- [x] 修复完成，验证链全绿：`compileJava` → `:test` 全量（**653 项**，含新增 `ballVariantOverrideAppearsInCatalogWithBalancedPrices`）→ `runGameTestServer`（**44/44**，含新增 `masterBallBallVariantKeyIsPricedInCatalog` + `buyMasterBallKeepsVariant`）→ `build --offline`。
- [x] 会话 #13 TODO「球类商品价格缺口」已由本会话根治（球种感知价格键全链路生效）。
- [ ] **运行时人工验证**：① 交易所目录显示全部球种（含大师球 500万、图标正确，非「暂无定价」）；② 仓储槽位 tooltip 显示大师球买/卖价（500万）；③ 买入大师球到手是大师球（组件保留）；④ 大师球可出售（背包/仓储两路）；⑤ 仓储取出大师球不降级、可合并到背包大师球；⑥ 购物车格/购物车 tooltip 显示球类图标。
- [ ] **SellRules 语义边界**：黑/白名单是精确匹配 `TradeItemId`（本会话测试已改球种感知键）；黑名单 `pixelmon:poke_ball#master_ball` 只拦大师球、不拦其它球种——如需「拦全部球类」需按 `#` 前缀规则，记录为 future TODO。
- [ ] **PKM 快照键上限**：`ResourceLocation` 无法表达 `#` 球种键，故球类价格永久由覆盖价承担（非 PKM）；未来若想经 EMC 合成树给球类补值，需扩展 `PKMManager` 支持 `#` 键——记录为 future TODO。
