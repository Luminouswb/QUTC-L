# QUTC 启动器 · 会话记忆备份（2026-10-23，收工前）

> 用途：明天继续时先读这份文件，再读 `docs/PROGRESS.md`（逐阶段证据）。
> 本文件记录「从零到现在的全部编写逻辑 + 当前未完成的工作 + 踩过的坑」，避免上下文丢失后重新摸索。

---

## 0. 一句话状态

QUTC Minecraft 启动器，**Java 21 + JavaFX 21 + Maven + jpackage**，14 阶段中 1–14 已完成并出过三产物；
现在处于「**UI 重做期**」：2026-10-23 已按用户第一张参考图完成**主页第一轮重排**（顶部对齐、版本磁贴墙、右栏信息卡、底部不再死白），144/144 测试通过；用户会继续发参考图，按图逐页打磨。

---

## 1. 硬约束与来源

- 总纲：`QUTC/docs/MC-Launcher-Development-Spec.md`（优先级最高）。
- 用户口头要求（优先级次之）：简洁、无边框窗口、参考 PCL + Apple、名字 QUTC。
- 上个会话的 Electron 版（`_handoff_2026-10-22/`）**只借鉴算法，不沿用框架**。
- 已作废：Electron/JS/零依赖；`_handoff` 里 src/main、src/renderer、scripts、build/*.cs。

---

## 2. 环境与工具链（最容易踩坑的部分）

| 项 | 值 |
|---|---|
| 工具链根 | `C:\qutc-toolchain`（**必须在工作区外**，工作区内 JDK 会被沙箱限制写） |
| JDK | Amazon Corretto 21.0.12.1 → `C:\qutc-toolchain\jdk21` |
| Maven | 3.9.9 → `C:\qutc-toolchain\maven`，settings：`C:\qutc-toolchain\maven-settings.xml`（aliyun 镜像，localRepository=`C:\qutc-toolchain\m2repo`） |
| JavaFX | 21.0.5 jmods → `C:\qutc-toolchain\javafx-jmods` |
| WiX | 3.11.2.4516 → `C:\qutc-toolchain\wix` |
| 工程目录 | `C:\Users\独钓寒江雪\Desktop\大肥鱼工作区\QUTC` |
| 测试数据 | `_mc-data`（通过 `-Dqutc.home=` 指定），已装 8 个版本 |

构建/运行（Git Bash 外请用 PowerShell）：

```powershell
# 编译 + 全部测试（144 个）
mvn -s C:\qutc-toolchain\maven-settings.xml -o test
# 打包 jar
mvn -s C:\qutc-toolchain\maven-settings.xml -o clean package -DskipTests
# 运行 UI（开发态，数据目录 _mc-data）
java -Dfile.encoding=UTF-8 -Dqutc.home=...\_mc-data -cp "target\classes;target\lib\*" com.qutc.launcher.main.Launcher
# jpackage 三产物
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\package.ps1 -Type installers   # exe+msi
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\package.ps1 -Type app-image    # 绿色版
```

环境坑（已解决，勿重踩）：

1. **PowerShell 5.1 按 GBK 解析无 BOM 的 .ps1** → 所有 `scripts/*.ps1` 必须 UTF-8 **with BOM**。
2. 本机**没有 pwsh**（只有 PowerShell 5.1），脚本禁用 PS7 语法；执行策略受限，统一 `powershell -NoProfile -ExecutionPolicy Bypass -File`。
3. **GUI 进程不能用 `Start-Process -Wait`** 或管道捕获（会挂死工具调用）；用 `Invoke-CimMethod Win32_Process Create` 分离启动。
4. 启动器运行时会锁 `target/lib/*.jar` → 打包前先 `Stop-Process` 掉 java 进程，否则 maven clean 失败。
5. jpackage 报 `Path.getFileSystem() null` 是二次异常掩盖真因；用 `--temp <已存在空目录>` 看真错误。根因是 JDK 放在工作区内。
6. `%TEMP%` 8.3 短名对受限进程不可用 → 用 TempDir 兜底；`Files.createTempFile()` 会缓存默认临时目录（全项目禁止无目录参数调用）。
7. 网络：BMCLAPI 可用；`download.mcbbs.net` DNS 已死；`maven.neoforged.net` 本机不可达（NeoForge 安装器必须镜像预置）；`cdn.modrinth.com` 直连不稳（重试可完成）。

---

## 3. 代码结构与职责（读代码顺序）

```
src/main/java/com/qutc/launcher
├── main/      Launcher（入口：目录→日志→配置→UI/CLI）、Cli、SelfTest
├── config/    AppPaths、LauncherConfig、ConfigManager、VersionConfig（双层配置）
├── utils/     Log、HashUtils、ZipUtils、JsonUtils、FsUtils、OsUtils
├── download/  Http（多候选降级）、MirrorResolver（四档源+前缀规则）、Downloader（.part+Range 续传、双哈希、退避）
├── core/
│   ├── version/   VersionMeta、VersionManifestService、VersionResolver（inheritsFrom 合并）、LibraryCollector（规则+natives 架构过滤）、Rules
│   ├── install/   InstallEngine（client/libs/natives/assets/logging + .qutc-installed.json）
│   ├── launch/    LaunchService（组装）、LaunchContext、LaunchBuilder、Preflight（七级检查）、GameProcess
│   ├── java/      JavaProbe、JavaMatrix、JavaStatus
│   ├── loader/    LoaderCatalog、LoaderInstaller、ForgeInstaller、LoaderDetector
│   ├── mods/ pack/ modpack/   ModScanner、PackScanner、ModpackImporter
│   ├── modrinth/  ModrinthClient
│   ├── external/  ExternalVersions（引用式导入登记）
│   ├── memory/    MemoryAdvisor
│   ├── diagnose/  CrashAnalyzer
│   └── authlib/   （无）
├── auth/      MicrosoftAuth（legacy + Azure 两模式）、YggdrasilAuth、AuthService、Account/AccountStore/Credentials、DPAPI SecretBox、CallbackServer、Pkce
├── update/    UpdateService（files.json、sha256、PowerShell 替换脚本、回滚）
└── ui/        MainApp、theme(Ui/qutc.css/qutc-light.css)、components(TitleBar/NavBar/WindowResizer/Dialogs/GameLogWindow/AccountMenuPopup/ModManagerPane)、pages(HomePage/VersionsPage/DownloadsPage/SettingsPage/LogsPage/VersionSettingsDialog/PlaceholderPage)、service(VersionService/LaunchRunner/LaunchOptionsFactory/ModpackImportService/VersionFolderImporter)
```

关键设计（不要改坏）：

- **版本合并顺序**：`inheritsFrom` 父在前、子在后，禁止反转；单版本内部重复坐标不合并（只在父子之间去重）。
- **natives 架构筛选**：官方 JSON 对 `natives-windows-arm64/-x86` 只写 `os.name=windows`，必须按 classifier 后缀二次过滤。
- **黑名单已删除**：不再猜“哪些库下架”，缺啥由下载失败如实报告（text2speech、lwjgl-platform 都还在）。
- **旧格式启动**：支持 `minecraftArguments` 与 `arguments` 双路，classpath 必在主类前，同名 `-D` 只留启动器值。
- **令牌脱敏**：日志打印命令前 `redactToken`；账号文件只存 DPAPI 密文（`secretsPayload`）。
- **离线/微软/外置三种账号**：
  - 微软默认走**内置客户端** `00000000402b5328` + `login.live.com/oauth20_desktop.srf`，浏览器登录后**粘贴回调地址**；设置里填 Azure Client ID 则切换 PKCE+loopback 自动回调。
  - XBL 旧版优先 `t=`，401 回落 `d=`；refresh 带 redirect_uri；授权码里 `+` 不能按表单解成空格。
  - 外置登录：`YggdrasilAuth`，HTTPS only + ALI 发现（`X-Authlib-Injector-API-Location`）+ `/authserver/authenticate|validate|refresh`；**密码不落盘**；clientToken 暂存 `Credentials.refreshToken` 槽位。
- **引用式导入**（`ExternalVersions`）：只登记 `{versionId, root}`，启动时从来源读 `versions/`、`libraries/`、`assets/`；`LaunchService.Options.externalRoot` 驱动；`LaunchContext.assetsIndexesOverride/assetsObjectsOverride` 让 Preflight 校验外部资源；删除只移除登记。

---

## 4. 当前 UI 状态（第二轮：下载中心三分栏 + 主题色 + 背景图）

已完成（2026-10-23 第二轮）：

- 无边框窗口 + 自绘标题栏 + 八向缩放 + 页面切换动画；暗/浅双主题。
- **窗口缩小**：初始 1080×640 逻辑，最小 940×560。
- **左侧导航**：主页 / 版本管理 / 下载中心 / 设置 / 日志（**账号已删除**，账号只走主页胶囊）。
- **主页**：标题 + 账号胶囊 + 快速启动卡（版本选择、隔离徽标、四行详情、大按钮、状态）+ 智能内存 / Java 状态两卡；内容限宽 1040、垂直居中；支持自定义**背景图**（**cover 铺满右侧内容区**、不拉伸、超界裁掉；有图时标题转白+阴影）与**强调色**。
- **下载中心**：左侧二级分类 游戏版本 / Mod / 整合包；
  - 游戏版本 = 原清单逻辑 + 本地导入；
  - Mod = Modrinth 搜索 + 版本/加载器筛选 + 目标版本选择 + 安装到 mods；
  - 整合包 = Modrinth 搜索 + 下载 .mrpack + 导入实例。
- **设置页**：主题（dark/light）、**主题色 ColorPicker**、**主页背景 选择/清除**、下载源/内存/Java/窗口/账号/更新/路径。
- 版本管理：卡片列表 + 来源徽标 + 启动/设置/⋯；版本设置：左页签 基本设置/模组/资源包/光影，基本设置含「目录」快捷行。
- 账号弹层 `AccountMenuPopup`（暗色圆角，含第三方登录）。
- 日志页、崩溃诊断、实时日志窗。

证据：`target/ui92-downloads.png`、`ui94-mods-search.png`、`ui101-packs-result.png`、`ui99-settings-end.png`、`ui91-home-bg2.png`；`mvn -o test` 144/144。

已知待办：

1. 背景图目前只铺在主页内容区（不含左侧导航/标题栏），若用户要整窗背景需改 MainApp 外壳。
2. 下载中心的 Mod/整合包结果列表尚未做分页/加载更多（当前显示前 30）。
3. 整合包真实下载（cdn.modrinth.com 抖动）未端到端验证完。
4. 其他页面可继续按同一套密度打磨。




---

## 5. 已验证的硬指标（回归时对照）

- 单元测试 **144/144**。
- 真机启动：1.8.9(Java8)、1.20.1(Java17)、Fabric 0.15.11、Quilt 0.23.0、Forge 47.2.0、NeoForge 21.1.72 全部进主菜单。
- 下载：BMCLAPI 621MB/3598 资源，16 并发 7–9MB/s；断点续传 + 切源已实测。
- 认证：微软伪造 code 请求被微软接受（invalid_grant 非 invalid_client）；外置登录单测覆盖（真实 LittleSkin 账号未测）。
- 打包：`target/dist-exe/QUTC-0.1.0.exe` 68.4MB、`dist-msi/QUTC-0.1.0.msi` 67.8MB、绿色版 zip 66.8MB；`--self-test` 15/15。

---

## 6. 明天待办 / 已知未完成

1. **UI 按参考图重排**（首要）。
2. 账号弹层新增「第三方登录」行未截到图（程序化点击受前台限制，需手点检查）。
3. 真实 Yggdrasil（LittleSkin）账号登录 + 启动进服未测（要账号）。
4. 「导入版本文件夹」UI 手点验证；外部引用版本启动未真机验证（需一个真实外部 .minecraft）。
5. 浅色主题下新组件未截图确认。
6. 三产物需要在本轮改动后重新 jpackage。
7. 用户电脑电量问题——**明天再动手，不要主动改代码**，等参考图。

---

## 7. 操作习惯（给下一个会话的我）

- 改代码前先 `read` 目标文件；改完 `mvn -o test-compile` → `mvn -o test` 再打包。
- 截图验证 GUI：`Invoke-CimMethod` 启动 → `SetWindowPos TOPMOST` → `CopyFromScreen` 截图，结束后恢复 NOTOPMOST。
- 用户在看屏幕时**不要抢鼠标/键盘**，尽量只做后台操作；需要点击时先说明。
- 中文注释/UI 文案保持现有风格；不做与请求无关的“顺手重构”。

## 8. GitHub 仓库

- 远端：`https://github.com/Luminouswb/QUTC-L.git`（分支 `main`）
- 已推送：`51f9855`（初始 `10e2676` + UI 迭代），135 个文件、约 1 MB，不含 target/编译产物/账号数据。
- 更新流程：

```powershell
cd "C:\Users\独钓寒江雪\Desktop\大肥鱼工作区\QUTC"
git add -A
git commit -m "说明这次改了什么"
git push
```

- 凭据由 Windows 凭据管理器（Git Credential Manager）保存，推送不再需要手动登录。