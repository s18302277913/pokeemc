# PokeEMC 手动发布指南（Modrinth / CurseForge）

> 本文档为作者在 **Modrinth** 与 **CurseForge** 网页手动上传时使用的对照材料。GitHub 发布由自动化完成（建仓 / push / release / 上传 jar），此处无需操作。
>
> 发布版本：**1.0.0-beta.1** ｜ 游戏版本：**1.21.1** ｜ 加载器：**NeoForge** ｜ 许可：**MIT**

---

## 一、通用材料

### 项目名称
```
PokeEMC
```

### 一句话简介（Short description / Summary）
> 各平台限 ~80 字符，可从中选用：

```
基于等价交换理念的 Pixelmon 附属模组：EMC 经济 + 交易系统 + 远程仓储 + 购物车。
```

### 项目图标
- 文件：`release/icon-512.png`（512×512，已放大，视觉验证清晰）

### 完整描述（Description / Long description）
> 各平台描述支持 Markdown，直接复制以下整块：

```markdown
# PokeEMC

> **让宝可梦世界拥有等价交换的无限可能。**

一款基于「等价交换」理念开发、面向 **像素宝可梦重铸（Pixelmon Reforged）** 的 NeoForge 附属模组：在保留最简约 EMC 经济的同时，引入交易、远程仓储与购物车，为生存与收集流程带来全新体验。

---

## 主要特性

### 🪙 交易与购物车
- **交易所三栏界面**：左侧仓储、中间买卖、右侧价格与结算——购买、出售一目了然。
- **目录式交易**：按分类浏览可交易物品，支持**批量买入**与**背包一键出售**，明码标价。
- **钱包系统**：余额自动同步，金额智能缩写（1k / 1m / 1b），随时掌握自己的「身价」。
- **购物车结算**：多件物品集中结算，告别逐件交易的繁琐。

### ⚡ 最简约的等价交换
- **EMC 价值体系**：宝可梦物品、精灵球与方块都拥有等价价值，让「无用」变「有用」。
- **能量凝聚器**：将物品凝聚为更高价值的等价物，宝可梦世界的「点石成金」。
- **转化桌 & 便携转化桌**：从桌面炼金到随身携带，手持右键随时打开交易所与转化界面。

### 📦 远程仓储与权限
- **远程仓储浏览**：无需走到箱子前，打开界面即可查看、存取你的全部箱子、潜影盒与凝聚器。
- **独立仓储浏览器**：搜索、排序、半径扫描、批量取出/出售/转移。
- **权限分享（ACL）**：把仓储的查看/存入/取出/管理权限精确分享给朋友。
- **仓储保护**：认领后的箱子防破坏、防爆炸、防活塞搬运，双箱被拆后认领记录自动迁移。

### 🤝 玩家交易（开发中）
- 玩家间面对面交易系统已进入测试，支持物品/宝可梦报价与确认流程。当前为**测试版**，仍在完善中。

---

## 版本信息

| 项目 | 内容 |
|---|---|
| **当前状态** | 测试版（Beta），持续迭代中 |
| **平台 / 加载器** | NeoForge |
| **游戏版本** | **1.21.1** |
| **前置模组** | Pixelmon（像素宝可梦重铸） |

> ⚠️ 本模组**仅支持 NeoForge 1.21.1**，且必须安装 Pixelmon 方可运行。

---

## 安装方法

1. 安装 **NeoForge 1.21.1** 并启动一次生成必要目录；
2. 安装 **Pixelmon** 到 `mods` 文件夹；
3. 将 **PokeEMC** 的 `.jar` 放入 `mods` 文件夹；
4. 启动游戏，进入世界后即可使用。

---

## 已知问题与路线

- 本模组当前为**测试版**，可能存在少量稳定性问题；
- **玩家交易**功能尚未完善，正在持续测试与打磨；
- 版本仅覆盖 **NeoForge 1.21.1**，其他版本/加载器暂不支持；
- 更多玩法与内容将在后续版本陆续推出，**请尽情期待更新**。

---

## 致谢与声明

- 本模组的 EMC 机制致敬开源「等价交换」系列（ProjectE / Equivalent Exchange 理念），为独立开发的附属模组；
- 本模组为 Pixelmon 社区模组，与 Pixelmon 官方无隶属关系；
- 使用本模组即表示同意：测试版可能随时更新/调整，请及时备份存档。

*PokeEMC —— 在宝可梦的世界里，万物皆有等价。*
```

### 变更日志（Changelog / Release notes）
> 上传版本时复制以下整块：

```markdown
## 1.0.0-beta.1 — 2026-08-10

首个公开测试版。基于「等价交换」理念的 Pixelmon 附属模组正式面世。

### 🪙 交易系统
- 交易所三栏界面：左侧仓储、中间买卖、右侧价格与结算
- 目录式交易：分类浏览、批量买入、背包一键出售
- 钱包系统：余额自动同步，金额智能缩写（1k / 1m / 1b）
- 购物车：多件物品集中结算

### ⚡ 等价交换（EMC）
- EMC 价值体系：宝可梦物品、精灵球与方块均拥有等价价值
- 能量凝聚器：将物品凝聚为更高价值的等价物
- 转化桌 & 便携转化桌：手持右键随时打开交易所与转化界面

### 📦 远程仓储与权限
- 远程仓储浏览：无需走到箱子前即可查看、存取全部箱子、潜影盒与凝聚器
- 仓储浏览器：搜索、排序、半径扫描、批量取出/出售/转移
- 权限分享（ACL）：精确分享查看/存入/取出/管理权限
- 仓储保护：认领后防破坏、防爆炸、防活塞搬运

### 🛠️ 修复与完善
- 双箱认领迁移：双箱被拆一半后剩余箱子自动降级为独立单箱认领，所有者与权限完整继承
- 仓储列表即时刷新：放置/破坏箱子后浏览列表实时更新

### ⚠️ 已知问题
- 玩家交易功能仍为测试阶段，尚未完善
- 测试版可能存在少量稳定性问题
- 仅支持 NeoForge 1.21.1
```

---

## 二、Modrinth 上传步骤

**网址：** https://modrinth.com/

1. 登录 → 右上角头像 → **Dashboard** → 点 **Create a new project**（创建新项目）；
2. 依次填写（直接复制上文对应材料）：
   - **Project name**：`PokeEMC`
   - **Short description**：复制「一句话简介」
   - **Project icon**：上传 `release/icon-512.png`
   - **Body / Long description**：粘贴「完整描述」整块
   - **Categories**（分类）：勾选 `pixelmon`（若有）、`economy`、`storage`、`forge`（NeoForge 归到 forge 类）
   - **Tags**（可选）：`equivalency`、`emc`、`trading`、`wallet`
   - **Links**：Source code = GitHub 仓库地址（发布后补 `https://github.com/s18302277913/pokeemc`），Issues = 同仓库 Issues
   - **License**：`MIT`
   - **Team**：默认即可（仅你自己）
3. **发布第一个版本（Upload first version）**，在同一页：
   - **Version name**：`PokeEMC 1.0.0-beta.1`
   - **Version number**：`1.0.0-beta.1`
   - **Game versions**：勾选 `1.21.1`
   - **Loaders**：勾选 `NeoForge`
   - **Release channel**：选 **Beta**
   - **Dependencies**：Pixelmon（Relationship: Required）
   - **Primary file**：上传 `build/libs/poketrade-1.0.0-beta.1.jar`
   - **Changelog**：粘贴「变更日志」整块
4. 点 **Create** 发布。发布后把项目地址复制给我，我补到各文档的下载链接。

---

## 三、CurseForge 上传步骤

**网址：** https://www.curseforge.com/minecraft/mc-mods

> CurseForge 官方 API 不支持直接上传文件，必须网页操作。

1. 登录 → **My Home / Dashboard** → **Mods** → **Add Mod**（新建模组项目）；
2. 依次填写：
   - **Mod Name**：`PokeEMC`
   - **Mod Summary**：复制「一句话简介」
   - **Mod Description**：粘贴「完整描述」整块（支持 Markdown）
   - **Logo**：上传 `release/icon-512.png`
   - **Screenshots**（可选）：当前截图有 UI 文字重叠问题，可暂时不放
   - **Categories**（分类）：`Minecraft Mods` → `Economy` / `Utility & QoL` / `Storage` 等
   - **Mod License**：`MIT`
   - **Source / Links**：GitHub 仓库地址与 Issues 地址
3. 保存后进入 **Files** 页 → **Upload File**（上传文件）：
   - **Version name**：`1.0.0-beta.1`
   - **Game version**：勾选 `1.21.1`
   - **Mod loader**：勾选 **NeoForge**
   - **Release type**：**Beta**
   - **Required Dependencies**：Pixelmon
   - **Changelog**：粘贴「变更日志」
   - **File**：上传 `build/libs/poketrade-1.0.0-beta.1.jar`
4. **重要**：CurseForge 要求「第三方下载链接」/ 或选择自动托管。若提示需要 Source link，填 GitHub Release 地址（`https://github.com/s18302277913/pokeemc/releases`）。
5. 提交后项目需经审核，发布后把项目地址复制给我。

---

## 四、链接汇总（GitHub 已发布）

- GitHub 仓库：`https://github.com/s18302277913/pokeemc`
- GitHub Releases：`https://github.com/s18302277913/pokeemc/releases`
- GitHub 当前版本：`https://github.com/s18302277913/pokeemc/releases/tag/v1.0.0-beta.1`
- jar 直链：`https://github.com/s18302277913/pokeemc/releases/download/v1.0.0-beta.1/poketrade-1.0.0-beta.1.jar`
- Modrinth：`[发布后回填]`
- CurseForge：`[发布后回填]`
- MC百科（MCMOD）：作者自行申请，地址 `[待申请]`
