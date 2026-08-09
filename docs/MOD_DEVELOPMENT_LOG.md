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
