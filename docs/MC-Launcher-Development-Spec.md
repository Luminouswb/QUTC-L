# 【最终定稿】自研 MC 启动器 AI 开发任务总纲（PCL2 / HMCL 同级工业级规范）

> 来源：用户提供的 DeepSeek 分享对话定稿（https://chat.deepseek.com/share/8403rt7noh6wue0k91）
> 落盘时间：本会话收到完整版后逐字保存，作为全项目唯一最高准绳。
> 优先级：本文件 > 用户口头补充（QUTC-需求备忘.md 中不冲突部分）> 交接包 HANDOFF。

## 前言

本文档为无歧义、可直接交付 AI 全流程开发的标准化启动器开发说明书。已修复旧版在 Java 版本隔离、微软登录安全、镜像下载容错、内存智能算法、自更新容错、打包兼容性、NeoForge 适配、版本隔离逻辑、崩溃诊断体系上的漏洞。

**AI 必须严格逐条执行，禁止跳步骤、禁止简化逻辑、禁止自主改动架构。**

---

## 〇、项目定位与技术栈（定稿强制约束）

### 项目定位

- 对标 PCL2 / HMCL / BakaXL
- 轻量、高性能、低占用、无广告、开源自研
- 支持 Windows 10 / Windows 11 **x64**
- 支持 MC 1.8 ~ 最新快照全版本
- 全加载器兼容、全整合包兼容、完整正版登录体系

### 强制技术栈（禁止改动）

| 项目 | 定稿选型 | 约束说明 |
|---|---|---|
| 启动器本体运行环境 | **Java 21 LTS** | 锁定具体补丁版本（如已验证的 21.0.x），CI 中固定 |
| UI 框架 | JavaFX 21+ 完整模块 | 需同时提供对应版本的 `javafx-jmods` |
| 构建工具 | Maven 标准生命周期 | — |
| 游戏运行 Java | **独立动态匹配**，支持 Java 8 / 17 / 21 / 25 | **启动器本体 JRE ≠ 游戏 JRE，绝对解耦、不混用** |
| 打包 | JDK 21 + `jpackage` + **WiX Toolset 3.11.2.4516** | WiX 4/5 **不可用**：jpackage 会报告找不到 `candle.exe` / `light.exe` |
| 输出产物 | EXE 安装包 + MSI 安装包 + 精简 JRE 绿色免安装版 | 三种全部产出 |

**打包环境必须遵守的附加规则（堵死已知翻车路径）**：

- JDK 21 的 `jpackage --type exe` 与 WiX 3.11 存在已知的 `candle.exe` exit code 14 故障（XML 解析/架构校验失败）。**在阶段 1 完成项目骨架后，立即对空项目执行一次 `--type app-image` 和 `--type exe` 的 smoke test**，确认当前 JDK + WiX 组合可用后再进入正式开发。
- 若 `candle.exe` 持续报 code 14，**备选路线为 `jpackage --type app-image` 产出绿色版目录，再用 Inno Setup 封装 EXE/MSI**。`app-image` 不经过 WiX，不受此问题影响。
- WiX 3.11 的 Burn bootstrapper 依赖 **.NET Framework 3.5 / 2.0**，目标机器未启用该运行时时安装包无法运行，安装包内需做检测或提示。

### 强制开发约束

- 所有 UI 耗时、网络耗时、IO 耗时必须使用异步 `Task` / `Service`
- **绝对禁止阻塞 JavaFX 主线程**
- 所有配置可回滚、所有错误可捕获、所有操作可日志追溯

---

## 一、整体架构（最终强制分层）

### 五层架构（固定分包、固定职责）

1. **UI 表现层**：JavaFX FXML + 全局 CSS 主题 + 动画帧绑定 + 窗口适配
2. **业务核心层**：版本解析、启动参数组装、加载器识别、整合包解析、内存计算、崩溃分析
3. **网络引擎层**：多源镜像下载、断点续传、重试退避、哈希校验、自动 fallback
4. **数据管理层**：全局配置、版本独立配置、**加密账号存储**、缓存管理、目录管理
5. **底层工具层**：SHA-1 / SHA-256 校验、解压、文件锁、进程守护、系统 API 调用、路径适配

### 强制代码规范（AI 必须遵守）

1. 固定分包：`core / auth / download / config / utils / ui / asset / thread / main`
2. 所有命名纯英文、驼峰规范、**无拼音**
3. 所有网络请求异步、带超时、带重试、带失败日志
4. **Token / RefreshToken 禁止明文存储**：优先 Windows DPAPI（`CryptProtectData` / `CryptUnprotectData`，通过 JNA 调用），备选 AES 本地加密
5. 全局异常捕获 + 弹窗友好提示 + 本地日志落盘
6. 所有 JSON 配置格式化、可自动修复损坏文件

### 核心设计原则（启动器灵魂逻辑，必须实现）

**1. 版本隔离默认开启**

- 独立：`mods` / `saves` / `config` / `shaderpacks` / `resourcepacks` / `logs`
- 共享：`libraries` / `assets` / `versions` 核心资源
- 关闭隔离时，上述内容回落到全局 `.minecraft` 根目录

**2. 启动前七级强制检查链（任一失败终止启动，给出中文原因）**

1. Java 环境匹配检查（**必须查 §三.4 的版本矩阵，不得由 AI 自行推断**）
2. 版本 JSON 完整性检查
3. `libraries` 哈希校验
4. `assets` 资源索引检查
5. `natives` 解压完整性检查
6. 目录读写权限检查
7. JVM 参数合法性检查

**3. 双层配置体系**

- 全局：`launcher.json`
- 版本独立：`version-config.json`
- 账号凭据：独立加密存储，**不进入上述任一 JSON**

---

## 二、UI 界面规范（最终细化无遗漏）

### 全局 UI 风格强制标准

- 默认暗色主题
- 全局圆角控件、阴影层级、页面切换动画
- **Win11 自动启用亚克力磨砂；Win10 自动降级为高质感阴影**（JavaFX 不原生支持 Win10 亚克力，需通过 Win32 API 实现或直接降级，**不得因此阻塞核心功能**）
- 窗口大小自适应、控件比例适配
- 全部按钮 hover / 点击反馈动画

### 全部页面完整功能细化

**1. 主页**

- 快速启动按钮
- 版本下拉选择（显示加载器类型 + 版本号）
- 智能内存展示（推荐值 / 当前值）
- Java 匹配状态（正常 / 不兼容 / 缺失）
- 当前账号头像 + 昵称快速切换

**2. 版本管理页**

- 全盘扫描本地所有 MC 版本
- 自动识别：Forge / **NeoForge** / Fabric / Quilt / OptiFine
- 重命名、隐藏、删除、备份、导出
- **版本隔离开关（核心功能）**
- 单版本独立内存、独立 JVM 参数、独立分辨率

**3. 下载中心**

- 正式版 / 快照 / 旧版完整列表
- **四档下载源切换：官方 / BMCLAPI / mcbbs 镜像 / 自定义镜像**
- 实时速度、剩余时间、已下载 / 总大小
- 多线程并行数可配置
- 失败指数退避重试、镜像失效自动切源

**4. 模组管理**

- 读取 `mods/`
- 一键启用 / 禁用（`.disabled` 后缀）
- 解析 `mods.toml` / `fabric.mod.json`
- 显示模组版本、作者、依赖需求
- **缺失前置依赖提示、冲突模组提示**

**5. 光影 / 材质管理**

- 预览、排序、启用、禁用
- 自动识别 `shaderpacks`、`resourcepacks`

**6. 整合包导入**

- 拖拽导入
- 支持 **CurseForge `.zip`** 与 **Modrinth `.mrpack`**
- 自动解析版本、加载器、模组列表
- 自动下载、校验、覆盖 `overrides`
- **自动生成隔离版本实例**
- **同一模组新旧版本共存时替换旧版本**

**7. 账号管理**

- 离线登录（自定义昵称 + 自定义 UUID）
- 微软完整正版登录（详见 §三.3）
- 多账号快速切换
- 皮肤、披风、头像展示

**8. 设置页面**

- 全局 Java 自动扫描 + 手动指定
- 智能内存三种模式：自动 / 全局手动 / 单版本独立
- 下载源配置、并行数配置
- 自定义游戏根目录
- 自定义 JVM 参数、游戏参数
- 分辨率、全屏、无边框
- 主题切换、日志等级

**9. 日志与崩溃诊断页**

- 游戏实时日志输出，错误标红、警告黄色
- **崩溃匹配表**（见 §三.7）
- 匹配表之外的堆栈不强行诊断，只标红并建议导出日志
- 日志一键导出 / 一键复制

---

## 三、核心功能模块（最终 100% 细化）

### 1. 游戏启动核心（最高优先级）

**强制完整 JVM 系统属性（一条不能少）**：

```text
-Djava.library.path=<natives路径>
-Djna.tmpdir=<jna临时目录>
-Dorg.lwjgl.system.SharedLibraryExtractPath=<lwjgl解压目录>
-Dio.netty.native.workdir=<netty临时目录>
-Dminecraft.launcher.brand=<启动器名>
-Dminecraft.launcher.version=<启动器版本>
```

**版本 JSON 递归解析（含 inheritsFrom 库顺序陷阱）**：

- 支持**无限层** `inheritsFrom` 继承链，适配 NeoForge 多层继承
- 自动合并父版本 `libraries`、`arguments`、`rules`、`natives` 配置
- **合并后的 `libraries` 顺序必须为：父版本（原版）在前，子版本（加载器）在后。顺序反转是 PCL2 记录过的真实启动失败原因**（“带 inheritsFrom 的版本 libraries 会被放到原版的后面，导致潜在的启动失败”）
- 同名库去重时保留**子版本（后出现）的版本**，但**位置仍按上述顺序排列**
- 解析完成后执行一次**类路径稳定性检查**，确认无顺序导致的冲突
- **禁止硬编码任何版本主类、参数**

**Natives 自动处理**

- 按系统位数与版本 `classifier` 自动筛选（Windows x64 / ARM64）
- 自动解压、自动校验、**自动修复损坏 native**
- 启动前检测 native 完整性

**启动参数组装规则**

- `classpath` 严格区分 **Windows 分号 / Linux 冒号**，且必须放在主类之前
- 自动区分原版参数、加载器附加参数、用户自定义参数
- 自动去重、自动补位、兼容新旧版本参数格式

### 2. 高级下载引擎（工业级容错）

**四档下载源**：官方 / BMCLAPI / **mcbbs 镜像** / 自定义镜像。

**固定镜像替换规则（死规则）**：

| 资源 | 官方 → 镜像 |
|---|---|
| 版本清单 | `piston-meta.mojang.com` → `bmclapi2.bangbang93.com` |
| 依赖库 | `libraries.minecraft.net` → BMCLAPI |
| 资源索引 | `piston-meta.mojang.com` → BMCLAPI |
| 散列资源 | `resources.download.minecraft.net` → BMCLAPI 对应路径 |
| **Forge / NeoForge Maven** | **走 BMCLAPI 的 `/maven/` 或 `/bmclapi/` 路径前缀，不是简单域名替换** |

**mcbbs 备选规则**：mcbbs 源是 BMCLAPI 的完整等价镜像，**目录结构与 API 完全一致，只需把 `bmclapi2.bangbang93.com` 替换为 `download.mcbbs.net`**。BMCLAPI 整体不可达时切到 mcbbs，**不要直接 fallback 官方源**（国内往往更慢）。

**重试与容错机制**

- 连接超时、读取超时**分开处理**
- 指数退避：**1s → 2s → 4s** 三轮重试
- 镜像失败自动 fallback，但**按“BMCLAPI → mcbbs → 官方”的优先级**，而非直接跳到官方
- 大文件（`client.jar`、`assets`）支持 **HTTP Range 断点续传**
- 所有文件 **SHA-1 / SHA-256 双重校验**

### 3. 微软登录完整合规链路（无 BUG 标准流程）

1. **OAuth2 Authorization Code + PKCE**（loopback 重定向，`state` 参数防 CSRF）
2. 换取 Microsoft Token
3. 换取 XBL Token
4. 换取 XSTS Token
5. 换取 Minecraft AccessToken
6. 拉取玩家 Profile、皮肤、披风

**安全与生命周期规则**

- **RefreshToken 轮换必须原子化**：每次刷新成功后，**立即检查是否返回了新的 `refresh_token`；若有，原子地覆盖存储并停止使用旧 token**。旧 token 在成功刷新后预期被丢弃。继续使用旧 token 是间歇性刷新失败的已知原因
- **多设备场景**：每台设备存储自己的最新 refresh_token，不覆盖另一台设备的 token 状态
- **续期失败不静默失效**：捕获 `invalid_grant` 等错误后弹窗引导重新交互登录
- **所有凭据加密存储**：Windows 下优先 **DPAPI**（`CurrentUser` 作用域，换机器/换用户不可解密），通过 JNA 调用 `CryptProtectData` / `CryptUnprotectData`

### 4. Java 版本匹配矩阵（启动前检查链直接查表，禁止推断）

| MC 版本范围 | 最低 Java | 推荐 |
|---|---|---|
| **1.12 – 1.16.5** | **Java 8** | Java 8 |
| **1.17.x** | Java 16（实际建议直接用 17） | **Java 17** |
| **1.18 – 1.20.4** | **Java 17** | Java 17 |
| **1.20.5 – 1.21.x** | **Java 21** | Java 21 |
| **26.1+** | **Java 25** | Java 25 |

依据：Minecraft ≥ 1.12 要求 Java 8，≥ 1.18 要求 Java 17，≥ 1.20.5 和 1.21 要求 Java 21；26.1 及之后要求 Java 25。

**覆盖规则**：`launcher.json` 或 `version-config.json` 中允许用户为单个版本**手动覆盖**自动匹配结果，**覆盖优先级高于矩阵**。

### 5. 智能内存分配算法（替代简单滑块）

**自动推荐规则**

| 场景 | 推荐范围 |
|---|---|
| 纯净端 | 2–4 GB |
| 轻度模组（≤ 30 mods） | 4–6 GB |
| 中度模组 + 光影 | 6–10 GB |
| 大型整合包 | 10–16 GB |

**系统保护机制**

- **最大不超过系统可用物理内存的 60%**
- 超配弹窗警告，防止 JVM 启动失败
- 支持单版本独立内存配置

### 6. 全加载器兼容矩阵

- **Forge**：全版本
- **NeoForge**：**1.20.2+ 全新分支完整适配**
- **Fabric**：全版本
- **Quilt**：兼容适配
- **OptiFine**：纯净 / 混搭识别

### 7. 智能崩溃诊断系统（收敛为可执行匹配表）

| 优先级 | 匹配模式 | 输出建议 |
|---|---|---|
| **P0** | `OutOfMemoryError` / `Java heap space` | 调高内存或换 64 位 Java |
| **P0** | `UnsupportedClassVersionError` | 当前 Java 版本不匹配，切换 §三.4 矩阵中的版本 |
| **P0** | `Failed to load natives` / `UnsatisfiedLinkError` | natives 解压失败或路径属性错误，重新补全 natives |
| **P1** | `ModResolutionException` / `IncompatibleModsException` | 缺失前置或版本冲突，去模组管理检查依赖树 |
| **P1** | `Duplicate mods` / `DuplicateModsFoundException` | 同一模组多版本共存，清理重复 jar |
| **P1** | OpenGL / LWJGL 相关 `EXCEPTION_ACCESS_VIOLATION` | 显卡驱动或 OpenGL 版本问题 |
| **P2** | `libraries` 哈希校验失败 | 文件损坏，触发下载引擎自动修复 |

**匹配表之外的堆栈不强行诊断**，只标红并建议导出日志。

### 8. 启动器文件级增量自更新（无损伤、可回滚）

- 服务端 `files.json` 记录：**路径 + SHA-256 + 大小**
- 客户端逐文件比对，**只下载哈希不一致或缺失的文件**
- **自动清理新版本清单中已不存在的废弃文件**
- 更新前备份 `launcher.json`、账号凭据、版本配置
- **更新失败全自动回滚**到上一可执行文件，保证启动器不损坏

---

## 四、最终标准目录结构（固定不可改）

```text
launcher-root/
├── launcher.json               # 全局配置
├── accounts/                   # 加密账号数据（DPAPI / AES）
├── shared/
│   ├── libraries/
│   └── assets/
├── versions/
│   └── <version-id>/
│       ├── 版本JSON文件
│       ├── version-config.json # 单版本独立配置
│       ├── mods/
│       ├── saves/
│       ├── config/
│       ├── resourcepacks/
│       ├── shaderpacks/
│       ├── natives/
│       └── logs/
├── logs/                       # 启动器日志
└── cache/                      # 图片 / 网络缓存
```

- **版本隔离开启**：使用独立目录
- **版本隔离关闭**：自动回落全局 `.minecraft` 目录

---

## 五、打包发布最终规范（严格固定）

1. **开发环境固定**：**JDK 21（锁定具体补丁版本）+ WiX 3.11.2.4516**。WiX 4/5 不可用。
2. **输出三种产物**：EXE 安装包、MSI 安装包、带精简 JRE 的绿色免安装版。
3. **阶段 1 即做打包 smoke test**：空项目先跑 `--type app-image` 和 `--type exe`，确认组合可用；若 `candle.exe` 报 exit code 14，**立即切换 `app-image + Inno Setup` 备选路线**，不等到阶段 14。
4. 自带 ICO 高质量图标。
5. `jlink` 精简 Runtime，极致压缩体积；需提供对应版本的 `javafx-jmods`。
6. **安装包内检测 .NET Framework 3.5/2.0**（WiX Burn bootstrapper 依赖），缺失时提示或引导启用。
7. 不含冗余依赖、无报错、无闪退。

---

## 六、AI 强制开发顺序（绝对不能颠倒）

| 阶段 | 内容 | 关键约束 |
|---|---|---|
| **阶段 1** | 项目骨架 + 工具底层 + 日志系统 + 加解密工具 | **同时完成 jpackage + WiX smoke test**；不可用则切备选打包路线 |
| **阶段 2** | Java 环境探测 + 路径系统 + 版本 JSON 完整解析引擎 | **含 `inheritsFrom` 库顺序正确性验证** |
| **阶段 3** | **纯命令行完成完整 MC 启动闭环**（先能启动游戏，再写 UI） | 必须包含全部 natives 系统属性、classpath 分隔符、主类读取 |
| **阶段 4** | 工业级多源下载引擎 + 校验 + 断点续传 | **四档源 + BMCLAPI / mcbbs / 官方 fallback 优先级** |
| **阶段 5** | 离线账号 + 微软全套 OAuth2 登录系统 | **PKCE + state + RefreshToken 原子轮换 + DPAPI** |
| **阶段 6** | Forge / NeoForge / Fabric / Quilt / OptiFine 加载适配 | — |
| **阶段 7** | JavaFX 完整 UI 框架 + 主页 + 版本管理 | **版本隔离默认开启** |
| **阶段 8** | 下载中心 UI + 模组光影管理 + 整合包导入 | — |
| **阶段 9** | 智能内存算法 + 全局设置系统 | **查 §三.4 矩阵，不推断** |
| **阶段 10** | 日志实时输出 + 智能崩溃诊断系统 | **按 §三.7 匹配表，不自创“百种规则”** |
| **阶段 11** | UI 美化、动画、磨砂适配、主题系统 | Win10 磨砂可降级，不阻塞 |
| **阶段 12** | 文件级增量自更新 + 回滚系统 | — |
| **阶段 13** | 全局 BUG 闭环测试、容错补全 | — |
| **阶段 14** | 标准化打包输出三版本发布包 | 使用阶段 1 已验证的打包组合 |

---

## 七、AI 开发终极禁令（必须遵守）

1. **禁止简化任何启动参数、禁止简化 natives 逻辑**
2. **禁止混用启动器 JRE 与游戏 JRE**
3. **禁止明文存储账号令牌**
4. **禁止阻塞 UI 线程**
5. **禁止硬编码版本、路径、参数**
6. **禁止跳过哈希校验、禁止跳过启动前检查链**
7. **禁止删减 NeoForge、Quilt 适配逻辑**
8. **禁止简化镜像容错退避机制**
9. **禁止让 AI 自行“推断” Java 版本匹配，必须查 §三.4 矩阵**
10. **禁止把 `inheritsFrom` 合并后的 `libraries` 顺序反转**（父版本在前，子版本在后）

---

## 八、成品最终验收标准

1. **全版本 MC 可正常启动**（含 1.8 ~ 最新快照）
2. **全加载器无报错兼容**（Forge / NeoForge / Fabric / Quilt / OptiFine）
3. **微软正版登录完整可用、自动续期**，refresh_token 轮换无间歇性失败
4. **下载稳定、高速、自动修复损坏资源**，BMCLAPI 不可用时能切 mcbbs
5. **版本隔离完美生效**，切换整合包不串档
6. **整合包导入零报错**
7. **崩溃诊断按匹配表精准输出**，不产生幻觉建议
8. **内存智能分配合理，不因超配崩游戏**
9. **自更新无损、可回滚**
10. **UI 流畅、无卡顿、无闪退、无 BUG**
11. **在未安装 Java、未启用 .NET Framework 3.5 的干净 Windows 10/11 虚拟机上，安装包能启动、绿色版能启动，且能检测到“无 Java”并给出明确引导，而非闪退或静默失败**
