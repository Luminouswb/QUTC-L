# QUTC 开发进度

> 对照 `docs/MC-Launcher-Development-Spec.md` 的 14 阶段推进。每阶段完成必须留下可复现证据。

---

## 阶段 0：环境与工具链 ✅ 已完成

工具链统一放在 **`C:\qutc-toolchain`（工作区之外）**，原因见文末「环境坑位」。

| 组件 | 版本 | 路径 |
|---|---|---|
| JDK（启动器本体运行时） | **Amazon Corretto 21.0.12.1 LTS** | `C:\qutc-toolchain\jdk21` |
| 构建工具 | **Apache Maven 3.9.9** | `C:\qutc-toolchain\maven` |
| JavaFX | **21.0.5**（jmods + Maven `-win` 依赖） | `C:\qutc-toolchain\javafx-jmods` |
| 打包工具 | **WiX Toolset 3.11.2.4516** | `C:\qutc-toolchain\wix` |
| Maven 本地仓库 | — | `C:\qutc-toolchain\m2repo` |
| Maven 镜像配置 | 阿里云 public | `C:\qutc-toolchain\maven-settings.xml` |

**版本锁定说明**：总纲要求「锁定具体补丁版本」。当前锁定 Corretto `21.0.12.1`；JavaFX 锁定 `21.0.5`；WiX 严格锁定 `3.11.2.4516`（总纲明令 WiX 4/5 禁用）。

**jlink 运行时**：`jpackage` 自动用 JDK 21 的 jmods 生成精简运行时，当前 app-image 的 `runtime/` 约 65 MB。

---

## 阶段 1：骨架 + 工具底层 + 日志 + 加解密 ✅ 已完成

### 已落地代码

```
com.qutc.launcher
├── main/
│   ├── Launcher.java        入口：目录 → 临时目录守卫 → 日志 → 配置 → UI
│   └── SelfTest.java        无 UI 自检（可对安装包做健康检查）
├── config/
│   ├── AppPaths.java        固定目录布局（绿色便携优先，只读位置回落 %APPDATA%）
│   ├── LauncherConfig.java  全局配置模型（内存三模式 / 四档下载源 / 分辨率等）
│   └── ConfigManager.java   原子读写 + 损坏自动修复
├── utils/
│   ├── Log.java             异步日志（环形缓冲 8000 条 + 按天切分 + 监听器）
│   ├── HashUtils.java       SHA-1 / SHA-256 / MD5 / 离线 UUID
│   ├── ZipUtils.java        解压防穿越 + 防炸弹 + 打包
│   ├── JsonUtils.java       Jackson 封装 + 损坏文件留档自愈
│   ├── FsUtils.java         原子写 / 递归删除 / 可写探测 / 路径越界判定
│   ├── OsUtils.java         OS / 架构 / 物理内存探测（含 Win11 判定、ARM64）
│   ├── TempDir.java         **临时目录守卫**（%TEMP% 异常时切自建目录）
│   ├── Dpapi.java           Windows DPAPI（JNA，CurrentUser 作用域）
│   └── AesCrypto.java       AES-GCM 兜底加密
├── thread/
│   └── Async.java           后台线程池 + 安全回 UI 线程
└── ui/
    ├── MainApp.java         骨架窗口（阶段 7 换成九页导航）
    └── FriendlyDialog.java  中文友好错误弹窗
```

### 验证证据

| 项目 | 结果 |
|---|---|
| `mvn package` | BUILD SUCCESS |
| 单元测试 `UtilsTest` | **7 / 7 通过** |
| `Launcher --self-test`（源码态） | **15 / 15 通过** |
| `QUTC.exe --self-test`（打包态） | **15 / 15 通过** |
| app-image 启动 GUI | 正常开窗，日志记录 `JavaFX=21.0.5` |
| `jpackage --type app-image` | ✅ |
| `jpackage --type exe`（WiX 3.11） | ✅ `QUTC-0.1.0.exe` 68 MB |
| `jpackage --type msi`（WiX 3.11） | ✅ `QUTC-0.1.0.msi` 67.4 MB |

产物位置：`target\dist-app-image\QUTC\`、`target\dist-exe\`、`target\dist-msi\`

> **重要**：已知的「jpackage + WiX 3.11 → candle exit 14」问题在本机**未复现**，两条安装包链路一次通过，无需启用 Inno Setup 备选方案。

---

## 环境坑位（已踩，后续别再踩）

1. **工作区内的可执行文件被沙箱限制写入**
   - 现象：JDK 放在工作区内时，`jpackage` 报「目标目录不可写」，Java 进程写 `%TEMP%` 报 `AccessDeniedException`。
   - 判定方法：把同一个 JDK 复制到工作区 vs 留在 `Program Files`，行为随之改变——**与 JDK 版本无关，与可执行文件所在位置有关**。
   - 结论：**工具链必须放在工作区之外**（`C:\qutc-toolchain`）。构建/打包脚本已按此处理。

2. **`%TEMP%` 对受限进程不可写**
   - 已用 `TempDir` 在启动最早期兜底，改写 `java.io.tmpdir` 到 `cache\tmp`。
   - **全项目禁止使用不带目录参数的 `Files.createTempFile/createTempDirectory`**：JDK 会缓存默认目录，后改属性无效。

3. **PowerShell 5.1 按 GBK 解析无 BOM 的 .ps1**
   - 所有 `scripts/*.ps1` 必须保存为 **UTF-8 with BOM**，否则中文注释导致语法错误。

4. **执行策略禁止直接运行 .ps1**
   - 统一用：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\xxx.ps1`

5. **本机只有 Windows PowerShell 5.1，没有 `pwsh`（PowerShell 7）**
   - 脚本中不要用 `pwsh`、`??`、三元运算符等 PS7 语法。

6. **GitHub 直连不可用**
   - WiX 走 `ghproxy.net` 代理最稳（约 48 KB/s）；Corretto 走 AWS CDN 可达（1.3 MB/s）。

---

## 阶段 2：Java 探测 + 路径系统 + 版本 JSON 引擎 ✅ 已完成

### 已落地代码

```
core/version/
├── VersionMeta.java            版本 JSON 模型（含 arguments / assetIndex / logging / javaVersion）
├── VersionResolver.java        inheritsFrom 无限层合并（8 层上限 + 环路检测）
├── LibraryCollector.java       规则过滤 + 下架库剔除 + natives 选择（含 ARM64）
├── Library.java                Maven 坐标解析 / 去重键 / 路径推导
├── ArgumentEntry.java          字符串与条件参数统一反序列化
├── Rule.java / Rules.java      规则求值（os.name / os.version 正则 / os.arch / features）
├── VersionManifest.java        官方清单模型
└── VersionManifestService.java 清单拉取 + 内存/磁盘双缓存（TTL 15 分钟）
core/java/
├── JavaRuntime.java            运行时描述
├── JavaProbe.java              注册表 + PATH + 常见目录 + Mojang 自带 JRE 扫描
└── JavaMatrix.java             版本 → Java 推荐矩阵
download/Http.java              最小 GET（阶段 4 换成完整下载引擎）
config/AppPaths.java            补齐版本内固定子目录（natives / mods / saves / logs / assets 索引）
```

### 关键实现约束（对应总纲铁律）

1. **继承合并**：从最底层祖先开始向上叠，`libraries` 合并时父版本库全部在前、子版本新增库在后；
   同名库（group:artifact:classifier）去重保留**子版本内容**但**仍占父版本位置**。
   有序断言封装在 `VersionResolver.isParentFirst(...)`，由单元测试守住。
2. **规则求值**：按顺序覆盖，空规则 = 允许；支持 `os.name`、`os.version` 正则、`os.arch`、
   `features`（is_demo_user / has_custom_resolution / quickPlay 系列）。
3. **natives**：优先 `<classifier>-arm64`（存在时），否则回落 `<classifier>`；
   1.19+ 的 natives 以「带 classifier 的独立库」形式出现在 classpath，不做二次解压。
4. **下架库**：`org.lwjgl.lwjgl:lwjgl-platform`、`org.twitch:twitch`、`com.mojang:text2speech`
   在指向 `libraries.minecraft.net` 时剔除，避免 1.8–1.12 卡在 404 重试。

### 验证证据

| 项目 | 结果 |
|---|---|
| 单元测试 | **15 / 15 通过**（含继承顺序、环路检测、规则、natives、Java 矩阵） |
| 真实清单拉取 | 920 个版本，latest=**26.3** |
| 真实版本解析 1.20.1 | mainClass 正确、javaVersion=17、88 库 → classpath 63、natives 0（1.19+ 走 classpath） |
| 真实版本解析 1.16.5 | javaVersion=8、34 库 → classpath 31、**natives 6**（classifier 模式） |
| 真实版本解析 1.21.4 | javaVersion=21、113 库 → classpath 83 |
| Java 探测（真机） | 找到 **6** 个运行时：JetBrains JBR 25、Mojang epsilon 25 / delta 21 / jre-legacy 8、Corretto 21.0.12.1、Oracle 17.0.10 |
| Java 选择 | best(8)=1.8.0_51、best(17)=17.0.10、best(21)=**21.0.12.1**、best(25)=25.0.2 |

> 修复记录：Java 版本排序最初用字符串比较，导致 `21.0.7` 排在 `21.0.12.1` 之前；
> 已改为数值感知比较并按段比较，EA 版排在正式版之后。

---

## 阶段 3：纯命令行完成完整 MC 启动闭环 ✅ 已完成

### 已落地代码

```
core/install/
├── InstallEngine.java      版本 JSON / client jar / 库 / natives / 资源 安装 + .qutc-installed.json 标记
└── NativesExtractor.java   natives 解压（按各库 extract.exclude，防穿越/防炸弹）
asset/
├── AssetIndex.java         资源索引模型（objects / virtual / map_to_resources）
└── AssetLayout.java        老版本 virtual 与 map_to_resources 布局铺设
download/Downloader.java    并发下载 + SHA-1 校验 + Range 断点续传 + 1s→2s→4s 退避
core/launch/
├── LaunchContext.java      启动上下文（版本/目录/账号/内存/分辨率）
├── LaunchCommand.java      组装结果 + 命令行引号 + mainClass 下标
├── LaunchBuilder.java      六项强制系统属性 + classpath 顺序 + 新旧参数双路 + 变量替换
├── GameProcess.java        进程管理 + UTF-8/GBK 逐行解码 + ANSI 剥离 + 分离启动
├── Preflight.java          七级强制检查链
└── LaunchService.java      安装→解析→选 Java→组装→预检→起进程
main/Cli.java               --env / --list / --install / --dry-run / --launch
```

### 真机验证（两种时代各跑一遍）

| 版本 | Java | 结果 | 证据 |
|---|---|---|---|
| **1.20.1**（现代） | Oracle 17.0.10 | ✅ 进入主菜单 | `Setting user: Steve`、`Backend library: LWJGL 3.3.1`、`OpenAL initialized`、17 个纹理图集全部 `Created` |
| **1.8.9**（远古） | Mojang jre-legacy 1.8.0_51 | ✅ 进入主菜单 | `LWJGL Version: 2.9.4`、`Session ID is token:0:<uuid>`、`Created: 512x512 textures-atlas` |

下载实测：1.20.1 共 621 MB / 3598 个资源，16 并发稳定 **7–9 MB/s**，全部 SHA-1 校验通过；
1.8.9 共 734 个资源 + 9 个 native dll（含 `lwjgl64.dll` / `OpenAL64.dll`）。

七级检查（1.20.1 实测输出）：

```
[通过] 1. Java 环境匹配：Java 17（推荐 Java 17）
[通过] 2. 版本 JSON 完整性：mainClass=net.minecraft.client.main.Main，库 88 个，资源索引 5
[通过] 3. libraries 哈希校验：63 个库哈希全部通过
[通过] 4. assets 资源索引检查：索引 5 共 3598 个资源，抽查 3598 个齐全
[通过] 5. natives 解压完整性：该版本 natives 直接走 classpath，无需解压
[通过] 6. 目录读写权限检查：游戏目录、版本目录、natives、资源目录均可读写
[通过] 7. JVM 参数合法性检查：内存 2048 MB，自定义 JVM 参数 0 项
```

### 本轮踩到并修掉的 6 个真 BUG

1. **资源索引 NPE**：现代 `assets/indexes/5.json` 里没有 `id` 字段，解析后 `assetIndex.id` 为 null，
   `assetsDir.resolve("virtual").resolve(null)` 抛 NPE。→ 解析后从版本 JSON 回填 id。
2. **text2speech 被误伤**：按「下架库黑名单」剔除了 `com.mojang:text2speech`，
   1.20.1 直接 `NoClassDefFoundError: com/mojang/text2speech/Narrator`。→ 该库仍在线，移出黑名单。
3. **跨架构 natives 混入 classpath**：官方 JSON 对 `natives-windows-arm64` / `-x86` 只写
   `{"os":{"name":"windows"}}`，三条同时过规则，x64 机器上塞进 3 份同名 dll。→ 按 classifier 后缀二次筛选。
4. **单版本内部重复坐标被合并**：1.8.9 同时列 `lwjgl:2.9.4-nightly`（通用）与 `2.9.2-nightly`（仅 osx），
   合并逻辑把后者顶到前者的位置，再被规则丢弃 → classpath 里没有 lwjgl →
   `NoClassDefFoundError: org/lwjgl/opengl/OpenGLException`。→ 去重只在父子版本之间做。
5. **lwjgl-platform 被误伤**：黑名单剔除了它，导致 `UnsatisfiedLinkError: no lwjgl64`。→ 移除黑名单，
   实测该包仍在官方仓库（natives 分类包含 lwjgl64.dll / OpenAL64.dll）。
6. **`--no-wait` 静默退出**：启动器退出后管道读端消失，游戏写 stdout 报错即退出（窗口一闪就没）。
   → 分离启动改为把子进程 stdout 直接重定向进日志文件，不再经过启动器管道。

> 结论写进代码注释：**不要用黑名单猜「哪些库下架了」**，官方仓库的存活情况会变；
> 缺什么由下载失败如实报告，而不是悄悄丢库。

### 命令行用法

```powershell
QUTC.exe --env                              # 环境（Java / 内存 / 目录）
QUTC.exe --list                             # 已安装版本
QUTC.exe --install 1.20.1                   # 只安装
QUTC.exe --dry-run 1.20.1 --username Steve  # 打印七级检查 + 完整启动命令（不启动）
QUTC.exe --launch 1.20.1 --username Steve --memory 4096 --width 1280 --height 720
QUTC.exe --launch 1.8.9 --no-wait           # 分离启动（启动器退出后游戏继续跑）
```

下次进入前必读：`--launch` 默认前台等待并实时转发日志；`--no-wait` 才会分离。

---

## 阶段 4：工业级多源下载引擎 ✅ 已完成

### 已落地代码

```
download/MirrorResolver.java  官方域名 → 镜像 URL 翻译 + 四档源 fallback 顺序
download/Downloader.java      重写：多候选地址、退避重试、4xx 换源、Range 续传、SHA-1/SHA-256 双校验
download/Http.java            重写：getBytes(List<String> urls, …) 多源降级取文本
```

- `MirrorResolver`：常量 `BMCLAPI_BASE=https://bmclapi2.bangbang93.com`、
  `MCBBS_BASE=https://download.mcbbs.net`、`OFFICIAL_MANIFEST`；`PREFIX_RULES` 八条；
  `order()` 四档：OFFICIAL→[官方]、BMCLAPI→[BMCLAPI,mcbbs,官方]、
  MCBBS→[mcbbs,BMCLAPI,官方]、CUSTOM→[自定义,BMCLAPI,mcbbs,官方]。
  已经指向镜像域名的 URL 原样返回（不二次镜像）；自定义 base 先去尾斜杠、只接受 http(s)。
- `Downloader`：`FileTask(List<String> urls, Path target, String sha1, String sha256, long size)`
  + `of()` / `mirrors()` 工厂；`HttpURLConnection` 连接 15s、读取 30s 分开；
  5xx/IO 退避 1s→2s→4s 三轮，同址重试；`FatalHttpException`（4xx）**不重试直接换源**；
  `.part` + `Range` 续传（服务端回 200 则截断重下）；sha1 与 sha256 给哪个校验哪个，
  通过后 `ATOMIC_MOVE` 落盘；失败返回 `Failure(task,url,reason)`；`Progress` 带 `bytesPerSecond`；
  退避序列可注入（单测用 0 延迟）。
- 接线：`VersionManifestService` 用 `mirrors.manifestCandidates()` 取清单、日志打印 `describe()`；
  `InstallEngine` 的版本 JSON / libraries / natives 包 / 资源索引 / logging 配置 / client jar
  以及全部 assets 对象一律走 `task()`→`FileTask.mirrors(...)`；
  `LaunchService.prepare(options, listener, mirrors)` 新重载；`Cli` 新增
  `--source official|bmclapi|mcbbs|custom` 与 `--source-base <url>`，`--env` 打印下载源。

### 镜像规则（真机探测 2026-10，全部 200）

| 资源 | 官方 | BMCLAPI 等价路径 |
| --- | --- | --- |
| 版本清单 | `piston-meta.mojang.com/mc/game/version_manifest_v2.json` | 同路径，只换域名 |
| 版本 JSON / 资源索引 | `piston-meta.mojang.com/v1/packages/…` | 同路径 |
| 依赖库 | `libraries.minecraft.net/<path>` | `/maven/<path>` |
| 散列资源 | `resources.download.minecraft.net/<前两位>/<hash>` | `/assets/<前两位>/<hash>` |
| Forge / NeoForge / Fabric / Quilt | 各自 Maven | 统一 `/maven/<path>` |

> 关键坑：BMCLAPI 的资源索引是**同路径** `/v1/packages/<sha1>/<id>.json`，
> `/assets/indexes/<id>.json` 与 `/assets/<id>.json` 都是 404。
> mcbbs（`download.mcbbs.net`）DNS 已不可解析，保留在降级链里只作观望，失败约 20ms 不拖慢。

### 验证证据

- `mvn -o test`：**43/43 通过**（新增 `MirrorResolverTest` 11 个、`DownloaderTest` 4 个）。
- 真机镜像下载：删掉 `_mc-data` 里 `text2speech-1.17.9.jar`，`--install 1.20.1 --source bmclapi`
  → 首行打印「下载源: BMCLAPI → mcbbs → 官方」，1/1 文件下载成功；
  删掉 `authlib-4.0.43.jar`，`--source official` → 1/1 成功，落地 121766 字节。
- 单测覆盖：500 重试后成功（命中 2 次）、404 不重试直接换源（死源 1 次 / 备用 1 次）、
  Range 续传（请求带 `Range` 头、`.part` 完成后移走、内容逐字节一致）、
  sha1 不符报失败且不落地。

### 命令行新增

```powershell
QUTC.exe --install 1.20.1 --source bmclapi      # 四档源：official / bmclapi / mcbbs / custom
QUTC.exe --install 1.20.1 --source-base https://mirror.example.com
```

---

## 阶段 5：离线账号 + 微软全套 OAuth2 登录 ✅ 已完成

### 已落地代码

```
auth/
├── Account.java         账号档案（不含令牌）+ 离线 UUID 规则
├── AccountType.java     OFFLINE / MICROSOFT
├── Credentials.java     accessToken / refreshToken / msAccessToken / expiresAt
├── SecretBox.java       DPAPI → AES 兜底，两种都失败则拒绝落盘
├── AccountStore.java    accounts/accounts.dat 原子写 + 跨进程锁 + 损坏留档
├── Pkce.java            RFC 7636 verifier / S256 challenge + state
├── CallbackServer.java  loopback 回调服务器 + state 防伪
├── OAuthHttp.java       表单 / JSON / Bearer 收发（不重试、不换源）
├── MicrosoftAuth.java   微软六步链路 + XErr 归类
├── AuthException.java   可判定原因（NETWORK / REAUTH_REQUIRED / …）
└── AuthService.java     编排：离线登录 / 交互登录 / 续期 / 当前账号
```

### 关键实现约束（对应总纲硬要求）

- **PKCE S256 + state + loopback**：回调服务器只绑 `127.0.0.1` 的随机端口，
  state 不匹配直接 `STATE_MISMATCH` 拒绝；用户点拒绝 → `REAUTH_REQUIRED`。
- **令牌禁止明文**：全部经 `SecretBox`；Windows 优先 DPAPI（CurrentUser），
  不可用才 AES-GCM + 本机随机密钥（`accounts/secret.key`），两者都失败**拒绝登录**。
- **RefreshToken 原子轮换**：`AuthService.refresh` 把「新 refresh_token + 新账号档案」
  一次性原子写盘（临时文件 + ATOMIC_MOVE），写后**回读校验**，成功后旧令牌即作废；
  微软未返回新 refresh_token 时沿用旧值（旧值仍有效）。
- **续期失败不静默**：`invalid_grant` → `REAUTH_REQUIRED`，CLI 明确提示 `--login-microsoft`。
- **XErr 映射**：2148916233 无 Xbox 账户 / 2148916235 地区限制 / 2148916227 封禁 /
  2148916236-2148916238 家庭组或成人验证。
- 多账号隔离；删除当前账号自动回落到剩余账号。

### 验证证据

- `mvn -o test`：**87/87 通过**。其中 auth 新增 44 个：Pkce 5、Account 6、
  AccountStore 7、MicrosoftAuth 14、CallbackServer 4、AuthService 8
  （含「假浏览器」端到端：从授权 URL 取 state/challenge → 回调 → 校验
  `code_verifier` 与 `code_challenge` 匹配 → 六步链路 → 密文落盘）。
- **真机 DPAPI 落盘证据**（`target/verify/AuthVerify`，直接打在 Windows DPAPI 上）：
  `scheme=dpapi`、`plaintext_leak=false`、`reload_access_ok=true`、
  `reload_refresh_ok=true`、`old_refresh_gone=true`、`old_access_gone=true`、
  `new_refresh=ROTATED-REFRESH-TOKEN`。
- 离线账号：`--login-offline Steve` → UUID `5627dd98-e6be-3c21-b8a8-e92344183641`
  （v3 版本位/变体位校验通过），`accounts.dat` 内无任何令牌字段。
- **带账号真机启动**：`--launch 1.20.1 --account Steve --no-wait` →
  日志 `Setting user: Steve`、`LWJGL version 3.3.1 build 7`、`OpenAL initialized`、
  纹理图集 `Created`，进入主菜单；dry-run 命令含
  `--username Steve --uuid 5627dd98-… --accessToken 0 --userType legacy`。
- 微软真实登录需要用户自己的 Azure Client ID + 浏览器交互，留到阶段 13 真机联调；
  本地假服务器已覆盖全部请求形状与错误归类。

### 命令行新增

```powershell
QUTC.exe --login-offline Steve                   # 离线账号
QUTC.exe --login-microsoft --client-id <id>      # 微软正版（授权码 + PKCE）
QUTC.exe --accounts                              # 列出账号（* 为当前）
QUTC.exe --use-account Steve                     # 切换当前账号
QUTC.exe --logout-account Steve                  # 移除账号
QUTC.exe --refresh-account                       # 手动续期当前账号
QUTC.exe --launch 1.20.1 --account Steve         # 用指定账号启动
```

---

## 阶段 6：四家加载器适配 ✅ 已完成

### 已落地代码

```
core/loader/
├── LoaderKind.java        Forge / NeoForge / Fabric / Quilt / OptiFine
├── LoaderVersion.java     加载器版本坐标与版本 id
├── LoaderCatalog.java     Fabric/Quilt meta API + Forge/NeoForge Maven 元数据
├── LoaderInstaller.java   安装编排：meta profile 或官方安装器 + InstallEngine 补全
├── ForgeInstaller.java    无头执行安装器 / 纯解包兜底 / install_profile 依赖预置
└── LoaderDetector.java    原版 / 加载器 / OptiFine 纯净与混搭识别
```

### 关键实现约束

- **Fabric / Quilt**：拉 meta API 的 profile JSON（自带 `inheritsFrom`、libraries、
  mainClass），直接写 `versions/<id>/<id>.json`，再交给 `InstallEngine` 补库与资源。
  Fabric 的 meta 走 BMCLAPI 的 `/fabric-meta/`；Quilt 走官方 meta。
- **Forge / NeoForge**：官方安装器**无头执行**（`java -jar installer --installClient <staging>`）。
  现代 Forge（1.13+）必须跑安装器内的 installertools 处理器，只解包 JSON 不够——
  真机报错过 `client-srg.jar / client-extra.jar / forge-*-client.jar` 缺失。
  安装器在 staging 里按 `.minecraft` 布局工作，完成后把 `versions/` 与 `libraries/`
  合并回 QUTC 布局（共享库在 `shared/libraries`），并**跳过安装器顺手写的原版版本目录**。
- **安装器依赖预置**：读 `install_profile.json` 的 `libraries`，把 installertools /
  neoform / universal 等经 BMCLAPI 镜像链提前下到 staging，安装器校验 SHA-1 命中后不再联网。
  这是 NeoForge 能在国内装成功的关键（`maven.neoforged.net` 本机不可达）。
- **纯解包兜底**：安装器跑不起来时提取 `version.json` 或 `install_profile.versionInfo`，
  并铺设安装器内置 `maven/`（含防路径穿越），古老 Forge 客户端安装足够。
- **OptiFine**：官方无公开下载入口，按总纲只做「纯净 / 混搭识别」——版本 JSON 标记、
  版本 id 关键字、`mods/OptiFine_*.jar` 三路识别。

### 验证证据（全部真机进主菜单）

| 加载器 | 版本 | 证据 |
| --- | --- | --- |
| Fabric | 0.15.11 @1.20.1 | `Loading Minecraft 1.20.1 with Fabric Loader 0.15.11`、`Setting user: Steve`、OpenAL/图集 |
| Quilt | 0.23.0 @1.20.1 | `Loading Minecraft 1.20.1 with Quilt Loader 0.23.0`、主菜单纹理图集 |
| Forge | 47.2.0 @1.20.1 | `Forge mod loading, version 47.2.0`、`MinecraftForge v47.2.0 Initialized`、OpenAL/图集 |
| NeoForge | 21.1.72 @1.21.1 | `NeoForge mod loading, version 21.1.72, for MC 1.21.1`、ModLauncher 11.0.4、Java 21 运行时、OpenAL/图集 |
| OptiFine | 识别 | `--loader-info` 输出 `OptiFine（版本 JSON）` 与 `Forge + OptiFine（mods/…）` 混搭 |

- 单测：**102/102 通过**（新增 LoaderCatalog 5、ForgeInstaller 4、LoaderDetector 5，
  以及 LaunchBuilder 的「重复 `--add-opens` 不得被去重」回归）。
- 真机进度：`1.21.1` 原版、`fabric-loader-0.15.11-1.20.1`、`quilt-loader-0.23.0-1.20.1`、
  `1.20.1-forge-47.2.0`、`neoforge-21.1.72` 五个版本目录均可直接 `--launch`。

### 本轮修掉的真 BUG

1. **参数去重折叠了重复 JVM 选项**：Forge 的 `--add-opens` 合法出现两次，
   原 `dedupeJvmArgs` 用 Set 整体去重 → JVM 把第二个值当主类，报
   `ClassNotFoundException: java.base/java.lang.invoke=...`。修法：只对 `-D` 属性去重。
2. **现代 Forge 只解包 JSON 不够**：缺 installertools 产物导致
   `Invalid paths argument`。修法：无头执行安装器。
3. **安装器官方源不可达**：NeoForge 安装器硬连 `maven.neoforged.net`（本机 000）。
   修法：install_profile 依赖经镜像链预置；`--mirror` 参数经实测无效（不改写 Maven 地址）。
4. **安装器会写原版版本目录**：合并时误把 `1.21.1` 当加载器版本并覆盖原版 JSON。
   修法：跳过原版版本目录，优先选 id 含 forge/neoforge 的目录。

### 命令行新增

```powershell
QUTC.exe --loader-versions neoforge:1.21.1          # 列可用加载器版本
QUTC.exe --install-loader fabric:1.20.1:0.15.11     # 安装（格式 kind:游戏:加载器）
QUTC.exe --install-loader forge:1.20.1:47.2.0
QUTC.exe --install-loader neoforge:1.21.1:21.1.72
QUTC.exe --loader-info 1.20.1-forge-47.2.0          # 识别加载器与 OptiFine 混搭
```

---

## 下一步：阶段 7

JavaFX 完整 UI 框架 + 主页 + 版本管理（版本隔离默认开启）。
严格按总纲「二、UI 界面规范」与用户口头要求：简洁、无边框、参考 PCL + Apple。


严格按总纲阶段 6 的范围实现；`inheritsFrom` 合并后父版本在前、子版本在后，禁止反转。



---

# 阶段 7：JavaFX 完整 UI 框架 + 主页 + 版本管理

状态：**主体完成，真机验证通过（版本设置对话框待人工交互确认）**。

## 7.1 交付物（源码，42 个 Java 文件编译通过）

| 文件 | 职责 |
|---|---|
| `ui/theme/Ui.java` | 主题注入（`qutc.css`）、按钮 hover 缩放、页面淡入上移动画、卡片弹出动画 |
| `ui/theme/qutc.css` | 暗色主题：圆角、阴影、渐变主按钮、徽标、滚动条、卡片 |
| `ui/components/TitleBar.java` | 自绘标题栏：拖拽移动、最小化/最大化/关闭、双击最大化、最大化时图标切换 |
| `ui/components/NavBar.java` | 左侧七项导航（主页/版本管理/下载中心/模组管理/账号/设置/日志），选中高亮 |
| `ui/components/WindowResizer.java` | 八向缩放（TRANSPARENT 无边框窗口补偿） |
| `ui/components/Dialogs.java` | 统一对话框样式（确认 / 输入 / 信息） |
| `ui/pages/Page.java` | 页面接口（view / onShow） |
| `ui/pages/HomePage.java` | 主页：版本下拉、开始游戏、智能内存卡、Java 状态卡、账号胶囊 |
| `ui/pages/VersionsPage.java` | 版本管理：卡片列表、加载器徽标、启动/设置/⋯ 菜单、显示隐藏版本、刷新 |
| `ui/pages/VersionSettingsDialog.java` | 版本设置：显示名、版本隔离、内存（自动/自定义）、Java 选择、分辨率、全屏、JVM/游戏参数 |
| `ui/pages/PlaceholderPage.java` | 其余页面占位（写明阶段 8/9/10 的实现范围） |
| `ui/MainApp.java` | 外壳：StageStyle.TRANSPARENT、自绘标题栏、八向缩放、页面切换动画、初始尺寸 |
| `config/VersionConfig.java` | 版本层配置：isolated 默认 true、memoryMb=0 跟随全局、javaPath、窗口、hidden、jvmArgs/gameArgs |
| `core/memory/MemoryAdvisor.java` | 智能内存：总纲 §三.5 档位、60% 物理内存上限、512MB 对齐 |
| `core/java/JavaStatus.java` | 版本 Java 状态：forVersion / countMods / hasShaders |
| `ui/service/VersionService.java` | 扫描/备份 zip/导出/删除/重命名（只改显示名）/隐藏 |
| `ui/service/LaunchOptionsFactory.java` | UI → LaunchOptions 组装（含账号 ensureFresh、内存/Java/窗口覆盖） |
| `ui/service/LaunchRunner.java` | 异步启动链：prepare → Preflight → start → 等待退出码 |

## 7.2 真机验证（_mc-data，界面 2560x1600@150%）

- 启动：`主窗口已显示（透明窗口=true），JavaFX=21.0.5`，`布局诊断：scene=1320x877，屏幕=1707x1019，缩放=1.5`。
- 主页：账号胶囊「S Steve / 离线账号」、版本下拉「neoforge-21.1.72」、版本隔离「开」、渐变开始游戏按钮、智能内存「4.0 GB（推荐）·纯净端 4096 MB·上限 9329 MB」、Java 状态「Java 21 就绪 · C:\qutc-toolchain\jdk21\bin\java.exe」。
- 版本管理：7 个版本卡片（neoforge-21.1.72 / 1.21.1 / 1.20.1-forge-47.2.0 / quilt-loader-0.23.0-1.20.1 / fabric-loader-0.15.11-1.20.1 / 1.8.9 / 1.20.1），NeoForge / Forge / Fabric / Quilt 徽标正确，每行「启动 / 设置 / ⋯」，页脚「共 7 个版本 · 已完整安装 7」。
- **UI 启动链路真机通过**：点 1.8.9 行的「启动」→ 回主页并选中 1.8.9 → 点「开始游戏」→ 日志 `为 1.8.9 选用 Java 8 (未知, x64)（推荐 Java 8）` → `Preflight 七级检查全部通过` → 游戏进程 PID 生成 → 1.8.9 进主菜单（`Created: 512x512 textures-atlas`）。
- 截图：`target/ui-home.png`（主页）、`target/ui6-max.png`（版本管理最大化）。

## 7.3 修掉的 BUG

1. **Quilt 被识别成 Fabric**：Quilt Loader 内嵌 Fabric 兼容层，库列表里含 fabric-loader。修法：`LoaderDetector` 先按版本 id 关键字判定（`quilt`/`fabric`/`neoforge`/`forge`/`optifine`），无关键字再按库依赖优先级 NEOFORGE > FORGE > QUILT > FABRIC > OPTIFINE。新增单测后 103/103 通过。
2. 编译期小问题：`HBox` 无 `setTooltip` → 用 `Tooltip.install`；`stage::setIconified` 需要 boolean 参数 → `() -> stage.setIconified(true)`；`HomePage.buildOptions` 里 `ensureFresh` 抛 `AuthException` → 包成 `IOException`。

## 7.4 已知待办（阶段 8 之前补）

- 「版本设置」对话框的鼠标交互尚未自动化点击验证（代码已编译、字段绑定已审阅），下次人工点一次即可确认。
- 主页下拉目前只显示「已安装版本」，阶段 8 下载中心完成后再加「未安装版本可直接下载」。
- 窗口初始尺寸：本机 2560x1600@150% 下取 `min(1320, 86% 宽)` 后物理 1980px 宽，未超出屏幕；低分辨率机器需再验一次。

## 下一步：阶段 8

下载中心（正式版/快照/旧版全列表 + 四档源切换 + 实时速度/剩余时间 + 并行数配置 + 失败退避与镜像失效切源）、
模组管理（读取 mods/、启用禁用、mods.toml / fabric.mod.json 解析、依赖与冲突提示）、
光影 / 材质 / 整合包导入。

---

# 阶段 8：下载中心 + 模组/资源包/光影/整合包

状态：**主体完成，真机验证通过**（真实 Modrinth 整合包的完整文件下载待网络稳定时补验）。

## 8.1 交付物

| 文件 | 职责 |
|---|---|
| `core/modrinth/ModrinthClient.java` | Modrinth v2 API：搜索（facets 过滤）、版本列表、依赖查询；分页上限 100、User-Agent 必填 |
| `core/modrinth/ModrinthModels.java` | SearchHit / ProjectVersion / Dependency / File DTO |
| `core/mod/ModScanner.java` + `ModFile` | 扫描 `mods/`（`.jar` 与 `.jar.disabled`），解析 `fabric.mod.json` / `quilt.mod.json` / `META-INF/mods.toml`，读 loader/版本/作者/依赖，缺失前置检测，启用禁用 |
| `core/pack/PackScanner.java` + `PackFile` | 扫描 `resourcepacks/` 与 `shaderpacks/`，读 `pack.mcmeta` 描述与格式版本，启用禁用 |
| `core/modpack/ModpackImporter.java` | `.mrpack` 导入：解析 `modrinth.index.json` → 装原版/加载器 → files 下载（sha1/sha512 校验）→ 解压 overrides/client-overrides → 写实例版本 JSON + `.qutc-installed.json` + `modpack.json` 元数据 |
| `ui/pages/DownloadsPage.java` | 版本全列表 + 类型筛选/搜索 + 已安装徽标 + 一键安装（进度/速度/剩余） |
| `ui/pages/ModsPage.java` | 版本选择 + 四个 Tab（模组 / 资源包 / 光影 / 整合包） |
| `ui/service/ModService.java` | 模组/资源包/光影/整合包目录与操作封装 |

## 8.2 真机验证（_mc-data）

- **下载中心**（截图 `target/ui9-downloads.png`）：下载源「BMCLAPI → mcbbs → 官方」、`103 个版本 · 已安装 7`、正式版徽标 + 每行安装按钮。
- **模组页**（截图 `target/ui11-mods-sodium.png`）：版本下拉 `fabric-loader-0.15.11-1.20.1 · fabric-loader-0.15…`；Sodium 卡片显示 `0.5.13+mc1.20.1`、fabric 徽标、`已启用`、作者 JellySquid (jellysquid3)、文件名；提示「⚠ 缺失前置: fabric-rendering-data-attachment-v1 >=0.1, fabric-rendering-fluids-v1 >=0.1」（未装 Fabric API，判断正确）；状态栏「1 个模组（启用 1）· 资源包 0 · 光影 0 · ⚠ 缺失前置 2 项」。
- **启用/禁用真机**：点「禁用」→ 文件变 `sodium-fabric-0.5.13+mc1.20.1.jar.disabled`；点「启用」→ 恢复 `.jar`。
- **整合包导入 E2E**：用 PowerShell ZipArchive 造合成 `target/qutc-test-pack.mrpack`（index: minecraft 1.20.1 + fabric-loader 0.15.11，files 为空；overrides 含 `options.txt` 与 `config/qutc-smoke.txt`），用 jshell 调 `ModpackImporter.importMrpack`（先 `-Dqutc.home` 指向 _mc-data 并 `AppPaths.init(null)`）：产出实例 `QUTC 冒烟整合包-1.20.1`，`<id>.json` / `options.txt` / `config/qutc-smoke.txt` / `.qutc-installed.json` 全部存在，`modpack.json` 元数据可读回（name/summary/minecraft/loader/loaderVersion），failures=0。
- **导入实例 dry-run**：主类 `net.fabricmc.loader.impl.launch.knot.KnotClient`、classpath 含实例 jar、`inheritsFrom` 链正确。
- **单测**：117/117 通过（`ModScannerTest` 5、`PackScannerTest` 4、`ModpackImporterTest` 5、`ModrinthClientTest`、`MirrorResolverTest` 11 等）。

## 8.3 修掉的 BUG

1. **ModsPage 版本下拉显示 record 默认 toString**（`Entry[versionId=...]`）：给 ChoiceBox 加 `javafx.util.StringConverter<VersionService.Entry>`，显示「displayName · versionId」。
2. **整合包实例选错 Java**：实例 id 不含游戏版本号，`JavaMatrix.recommendMajor` 猜成 21（1.20.1 应为 17）。修法：`LaunchService.pickJava` 与 `Preflight.javaCheck` 优先用 `meta.javaVersion.majorVersion`，版本 JSON 没声明才退回版本号区间推断。

## 8.4 已知待办

- 真实 Modrinth 整合包（含 files 真实下载）的完整导入未跑通：`cdn.modrinth.com` 直连易超时（curl 28，`--retry 3` 可下完）；synthetic pack 已覆盖 index 解析 / 加载器安装 / overrides / 元数据链路。
- 模组「搜索 + 一键安装」UI 尚未接（当前模组页只做本地文件管理；Modrinth 客户端代码层已可用）。

## 下一步：阶段 9

全局设置页：Java 管理（扫描/新增/下载）、智能内存三模式、下载源与并行数、路径、启动器偏好、清理缓存。
---

# 阶段 9：全局设置

状态：**完成，真机验证通过**。

## 9.1 交付物

| 文件 | 职责 |
|---|---|
| `ui/pages/SettingsPage.java` | 七个分区：下载与安装（源/自定义地址/并行数/隔离默认）、内存（三模式 + 滑块 + 上限提示）、Java 管理（自动匹配/默认 Java/重新扫描/手动添加）、游戏窗口（分辨率/全屏/无边框）、账号（微软 Client ID）、行为与日志（关闭启动器/日志窗口/日志等级/主题）、路径与清理（打开目录/缓存占用/清理缓存）；保存/恢复默认 |

- 保存走 `ConfigManager.update` 原子落盘 `launcher.json`；内存超过物理内存 60% 会先弹确认框。
- Java 扫描结果来自 `JavaProbe.discover()`，默认选中 Java 21（本机 Corretto）；「添加 Java 路径」用 `DirectoryChooser` + `JavaProbe.probe` 校验 `bin/java.exe`。
- 清理缓存会递归删除 `cache/`（已装版本、存档不受影响）。

## 9.2 真机验证

- 截图：`target/ui19-set-2.png`（Java/窗口/账号/行为分区）、`target/ui21-settings-end.png`（路径与清理 + 保存按钮）、`target/ui22-settings-saved.png`（保存确认）。
- 点「保存设置」后 `launcher.json` 立即被重写（23:23:32 → 23:31:13，794 → 723 字节），读回内容正确：
  `downloadSource=BMCLAPI, parallelDownloads=8, memoryMode=AUTO, globalMemoryMb=4096, javaPath=C:\qutc-toolchain\jdk21, resolutionWidth=1280, resolutionHeight=720, logLevel=INFO, lastVersionId 保留`。
- Java 下拉显示「Java 21 (Amazon.com Inc., x64) · C:\qutc-toolchain\jdk21」，提示「检测到 6 个运行时」，与 CLI `--env` 一致。

## 9.3 修掉的问题

1. 设置页放进 `ScrollPane` 后白底/标题被裁：补 `.scroll-pane / .viewport` 透明样式，并在 `onShow` 里 `setVvalue(0)`。
2. 默认 Java 下拉为空：`rescanJava()` 在没有 `javaPath` 时用 `JavaProbe.best(runtimes, 21)` 预选。

## 9.4 已知待办

- `gameRoot`（自定义游戏根目录）UI 未暴露，仅支持 `launcher.json` 手改；阶段 12/13 视需要补。
- 主题只有暗色（阶段 11 补浅色）。

---

# 阶段 10：日志 + 崩溃诊断

状态：**完成，真机验证通过**。

## 10.1 交付物

| 文件 | 职责 |
|---|---|
| `ui/pages/LogsPage.java` | 日志浏览：启动器日志 / 各版本游戏日志 / `crash-reports`；右侧内容（等宽字体、尾部 768KB 截断）；自动跑诊断；复制、导出 |
| `ui/components/GameLogWindow.java` | 游戏实时日志窗口：ERROR 红 / WARN 黄 / 普通灰白，最多 5000 行，复制全部、清空、底部状态与诊断行 |
| `core/diagnose/CrashAnalyzer.java` | 崩溃匹配表 14 条规则（JVM 崩溃 / OOM / class 版本 / 缺前置 / 重复模组 / Mixin 冲突 / NoSuchMethod / 缺类 / natives / OpenGL / 端口占用 / 登录失效 / 主类缺失 / 权限）；命中片段自动脱敏 accessToken/refresh_token |
| `ui/pages/HomePage.java` | 按 `showLogWindow` 打开实时日志；游戏异常退出时读日志尾部跑诊断，弹窗 + 日志窗口底部展示结论 |

## 10.2 真机验证

- 日志页（截图 `target/ui23-logs.png`）：16 个日志文件（含历史游戏日志与 `1.20.1 · crash/crash-2026-10-22_21.42.01-client.txt`）；选中合成崩溃日志后同时给出三条诊断——「内存不足 (OutOfMemoryError)」「缺少模组前置（依赖未安装）：sodium requires fabric-api >=0.83.0」「显卡驱动 / OpenGL 不支持：GLFW error 65542」，每条带建议。
- 实时日志（截图 `target/ui26-live-log3.png`）：启动 `QUTC 冒烟整合包-1.20.1` 后弹出「游戏日志 · QUTC 冒烟整合包-1.20.1」，滚动显示 WARN（黄色）与 ERROR（红色）行，底部「游戏运行中 · …qutc-game-20261022-233744.log」，游戏成功进主菜单。
- 修掉的 BUG：
  1. `GameLogWindow` 在后台线程（`Async.run`）里创建 Stage → `IllegalStateException: Not on FX application thread`。改为在点击「开始游戏」的 FX 线程里先建窗口。
  2. UI 启动用 `java.exe` 会弹出黑色控制台窗口：`GameProcess.start(..., quiet)` 在 UI 模式下自动换成 `javaw.exe`（CLI 保持原样），并同步新增 `LaunchService.start(prepared, sink, quiet)` 重载。

## 10.3 单测

`CrashAnalyzerTest` 8 个：OOM、class 版本、缺前置 + 重复模组、GLFW、JVM 崩溃 severity、令牌脱敏、干净日志零误报、崩溃判定。全量 **125/125**。

## 下一步：阶段 11

UI 美化收尾：浅色主题、主题切换持久化、列表/徽标细节打磨、页面切换与按钮反馈统一。
---

# 阶段 11：UI 美化收尾

状态：**完成，真机验证通过**。

## 11.1 交付物

- `ui/theme/qutc-light.css`：浅色主题（在暗色之后加载、只覆盖颜色），覆盖窗口外壳、导航、卡片、按钮、输入框、列表、徽标、进度条、对话框、滚动条。
- `ui/theme/Ui.java`：`applyTheme(scene)` 按 `launcher.json` 的 `theme` 自动叠加暗色/浅色；新增 `reapplyTheme(scene)` 供运行时切换。
- `ui/pages/SettingsPage.java`：新增主题下拉（暗色 / 浅色），保存后立即重新套用。

## 11.2 真机验证

- 浅色主题截图：`target/ui27-light.png`（主页：浅色外壳、白色卡片、蓝色主按钮、Java 状态卡）。
- 暗色主题保持原样（阶段 7–10 全部截图）。

## 11.3 已知待办

- 浅色主题下分享图/部分徽标颜色对比度可在阶段 14 发布前再微调。

---

# 阶段 12：增量更新 + 回滚

状态：**代码完成、单测通过；端到端替换脚本需真实更新源（服务端 URL 待部署后验证）**。

## 12.1 交付物

| 文件 | 职责 |
|---|---|
| `update/UpdateService.java` | `files.json` 清单拉取/解析、版本号数字段比较、主 jar 选择、staging 下载（sha256 校验）、`pending.json`、生成并启动 PowerShell 替换脚本（等进程退出 → 备份旧 jar → 覆盖 → 重启 → 删 pending）、`--rollback-update` 回滚、当前 jar 定位（`CodeSource`） |
| `config/LauncherConfig.java` | 新增 `updateBaseUrl`、`autoUpdate`（默认 true） |
| `ui/pages/SettingsPage.java` | 「更新」分区：更新源输入框、自动检查开关、「检查更新并应用」按钮（下载 → 校验 → 准备 → 自动重启） |
| `ui/MainApp.java` | 启动时后台检查更新，有新版弹窗引导到设置页 |
| `main/Cli.java` | `--update-check <url>` / `--update-apply <url>` / `--rollback-update` |

## 12.2 `files.json` 约定

```json
{
  "version": "0.2.0",
  "notes": "更新说明",
  "files": [
    {"path": "qutc-launcher.jar", "url": "https://…/qutc-launcher-0.2.0.jar", "sha256": "…", "size": 1234567}
  ]
}
```

## 12.3 单测

`UpdateServiceTest` 4 个：版本号数字比较（0.10.0 > 0.9.9、v 前缀）、清单解析与主 jar 选择、无 jar 报错、sha256 校验（一致/不一致/缺失）。全量 **129/129**。

## 12.4 已知待办

- 替换脚本只在「以 jar 运行」时可用；以 classes 目录运行（开发态）会明确报错而不是静默失败。
- 重启命令取当前进程 exe：打包后为 `QUTC.exe`，开发态为 `javaw.exe`（开发态重启不可用属预期）。

---

# 阶段 13：BUG 闭环测试

状态：**完成，回归矩阵见 `docs/TEST-MATRIX.md`**。

- 七个已安装版本 + 1 个 mrpack 导入实例，`--dry-run` 全部退出码 0（七级检查全通过）。
- 真机启动矩阵：1.8.9 / 1.20.1 / Forge / Fabric(+Sodium) / Quilt / NeoForge 1.21.1 / mrpack 实例全部进主菜单。
- 阶段 1–13 累计修掉 16 个真 BUG（清单见 `TEST-MATRIX.md` 第 4 节），本轮新增 3 个：实时日志窗口线程违规、UI 启动弹控制台、设置页 ScrollPane 样式。
- 单测 129/129。

## 已知限制

- OptiFine 仅识别不下载；真实 Modrinth 整合包 files 下载受 CDN 影响；自更新端到端需真实更新源；干净虚拟机未验证。

## 下一步：阶段 14

三产物打包（EXE / MSI / 精简 JRE 绿色版）+ 产物自检 + 验收清单。
---

# 阶段 14：三产物发布

状态：**完成**。发布说明与验收清单见 `docs/RELEASE-0.1.0.md`。

## 14.1 产物

| 产物 | 路径 | 大小 |
|---|---|---|
| EXE 安装包 | `target/release/QUTC-0.1.0.exe` | 68.39 MB |
| MSI 安装包 | `target/release/QUTC-0.1.0.msi` | 67.81 MB |
| 绿色免安装版 | `target/release/QUTC-portable-0.1.0.zip` | 66.79 MB（解压 165.5 MB，runtime 150.31 MB） |

## 14.2 验证

- `QUTC.exe --self-test` → 15/15 通过。
- 打包后 GUI：开窗日志 `主窗口已显示（透明窗口=true），JavaFX=21.0.5`；数据目录便携优先。
- 干净环境（PATH 仅 System32、无 JAVA_HOME）启动 `QUTC.exe`：正常开窗，`JRE=21.0.12.1（内置 Corretto）`。
- 全量单测 129/129；回归矩阵 `docs/TEST-MATRIX.md`。
- 收尾打磨：主页空状态文案去掉"阶段 8"开发措辞，改为「去下载中心挑一个版本安装即可」。

## 14.3 遗留验证（不阻塞交付）

- 真实微软账号登录（需用户 Azure Client ID）。
- 真实 Modrinth 整合包 files 下载（CDN 不稳）。
- 自更新端到端替换（需部署 `files.json` 服务端）。
- 纯净 Windows VM / 未启用 .NET 3.5 的安装包复测。
---

# 正版登录改版：切到旧版启动器的内置客户端方式（2026-10-23）

用户要求「正版账号登录用原先启动器的正版登录方式」，已把微软登录默认方式换成交接包 Electron 版的方案：
用微软公开的 Minecraft 启动器客户端 **`00000000402b5328`** + 固定回调地址
`https://login.live.com/oauth20_desktop.srf`，无需注册 Azure 应用。

## 改动

| 文件 | 改动 |
|---|---|
| `auth/MicrosoftAuth.java` | 新增 `LEGACY_CLIENT_ID` / `LEGACY_REDIRECT_URI` / `LEGACY_SCOPE` 常量、`legacy()` 工厂、`legacyAuthorizeUri()`、`parseLegacyRedirect()`、`exchangeLegacyCode()`；`refresh()` 在旧版模式下带 `redirect_uri` 与 MBI_SSL scope；XBL 认证旧版优先 `t=` 前缀（401 时回落 `d=`） |
| `auth/AuthService.java` | `defaults()`：没填 Client ID 就用 `MicrosoftAuth.legacy()`（**默认可用**）；新增 `usesLegacyFlow()` / `legacyAuthorizeUri()` / `loginMicrosoftWithPastedUrl()` / `effectiveClientId()`；`loginMicrosoft()` 在旧版模式下明确拒绝（提示用粘贴流程） |
| `ui/pages/HomePage.java` | 账号菜单「微软登录」：旧版流程 → 打开浏览器登录页 → 弹输入框粘贴回调地址 → 后台换票；自建 Azure 应用仍走 PKCE + loopback |
| `ui/pages/SettingsPage.java` | 「微软 Client ID（可选）」文案改为「留空 = 内置客户端，无需注册」 |
| `main/Cli.java` | `--login-microsoft` 旧版模式打印授权链接并在终端等待粘贴回调地址；`--accounts` 打印当前登录方式 |
| `ui/service/LaunchOptionsFactory.java`、`HomePage.java` | 启动参数 `--clientId` 改用 `AuthService.effectiveClientId()`（旧版走公开客户端 ID） |

## 验证

- **单测 135/135**：新增 `LegacyLoginTest` 6 个（授权 URL 参数、回调解析、`+` 不被吃成空格、错误参数透出、换票表单无 PKCE、refresh 带 redirect_uri、旧版链 `t=` 前缀）。
- **真机（CLI）**：`--login-microsoft` 打印
  `https://login.live.com/oauth20_authorize.srf?client_id=00000000402b5328&response_type=code&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL&prompt=select_account`，
  粘贴伪造 code 后真实请求到 `login.live.com/oauth20_token.srf`，微软返回
  `invalid_grant: The provided value for the 'code' parameter is not valid.` —— 证明请求格式被微软接受（不是 invalid_client）。
- **真机（UI，截图 `target/ui33-account-menu.png`）**：主页头像菜单点「微软登录」→ 自动打开微软登录页 → 弹出「微软正版登录」输入框，提示粘贴 `https://login.live.com/oauth20_desktop.srf` 开头的完整地址。
- `--accounts` 输出「微软登录: 内置客户端（无需配置，浏览器登录后粘贴回调地址）」。

## 注意

- 旧版客户端登录成功后，回调页是空白页，**code 在地址栏 URL 里**；用户复制整条地址即可，启动器只从中取 code。
- 只有「用户主动填了 Azure Client ID」才会走 PKCE + loopback 自动回调。
- 令牌轮换语义不变：refresh_token 仍在拿到后立即原子落盘（DPAPI 加密）。
---

# UI 改版：模组并入版本设置 + 主页限宽 + 账号弹层 + 下载中心本地导入（2026-10-23）

## 1. 模组管理并入版本设置

- 新增 `ui/components/ModManagerPane.java`：单版本维度的「模组 / 资源包 / 光影」面板（扫描、启用禁用、缺失前置提示、打开目录）。
- `VersionSettingsDialog` 改为左侧竖排页签：**基本设置 / 模组 / 资源包 / 光影**（`TabPane.setSide(Side.LEFT)`）。
- 删除独立页面 `ui/pages/ModsPage.java`，`NavBar` 去掉「模组管理」入口（现在导航为：主页 / 版本管理 / 下载中心 / 账号 / 设置 / 日志）。

## 2. 下载中心新增本地导入

- 「导入整合包…」（Modrinth `.mrpack`，导入后成为独立实例）。
- 「导入版本文件夹…」：**引用式导入**，不复制文件。
  - 新增 `core/external/ExternalVersions.java`：`<root>/external-versions.json` 登记 `{versionId, root, importedAt}`。
  - 新增 `ui/service/VersionFolderImporter.java`：识别整个 `.minecraft`、`versions/` 目录或单个 `versions/<id>/`；`register()` 跳过已存在的本启动器版本。
  - 新增 `ui/service/ModpackImportService.java`：整合包导入逻辑从旧 ModsPage 抽出复用。
- 启动链路支持外部引用：`LaunchService.Options` 增加 `externalRoot`；外部版本跳过安装，从来源目录读 `versions/`、`libraries/`、`assets/`；`LaunchContext` 增加 `assetsIndexesOverride/assetsObjectsOverride`，`Preflight` 的索引/对象校验走覆盖路径。
- 版本管理列表加分来源徽标：「本启动器下载」(蓝) / 「外部导入」(橙) / 「外部导入 · 来源不可用」(红)；路径行显示来源目录；删除外部版本只移除登记，不动外部文件。

## 3. UI 观感

- 主页内容区限宽 1360 并居中，快速启动卡片高度改为按内容收缩（不再随窗口最大化被拉满），主卡片加渐变/描边/阴影（`.hero-card`）。
- 账号菜单从 JavaFX 白色 `ContextMenu` 换成自绘暗色弹层 `ui/components/AccountMenuPopup.java`：头像 + 当前账号 + 账号列表（当前项高亮打勾）+ 离线登录 / 微软正版登录 / 刷新三行操作，hover 变色，跟随暗/浅主题。
- `qutc.css` 与 `qutc-light.css` 追加 `.account-popup*`、`.badge-local/.badge-imported/.badge-danger`、`.hero-card` 样式。

## 4. 验证

- 单元测试 **139/139**（新增 `VersionFolderImporterTest` 4 个：整个 .minecraft / versions 目录 / 单版本目录 / 非法目录）。
- 真机截图：`target/ui48-account.png`（主页 + 新账号弹层）、`target/ui47-home-max.png`（最大化限宽）、`target/ui40-home.png`。
- 编译与 `mvn clean package` 通过。

## 5. 待办

- 「导入版本文件夹」的 UI 真机点击验证（需要手动选一次目录）。
- 外部引用版本的启动真机验证（需要一个真实的外部 `.minecraft`）。
- 浅色主题下的账号弹层截图确认。
---

# 主页垂直居中 + 第三方外置登录（2026-10-23 续）

## 1. 主页布局

- 内容列 `maxWidth=1180`，宽屏不再拉伸；`HBox` 垂直居中（真正居中，不是顶部对齐）。
- 快速启动卡片高按内容收缩、侧栏 330px，整体比例不再“过宽/过高/压扁”。
- 真机截图：`target/ui51-home.png`。

## 2. 第三方外置登录（Yggdrasil / authlib-injector）

新增 `auth/YggdrasilAuth.java`（对应交接包 `core/auth/yggdrasil.js`）：

- 地址规范化：自动补 `https://`、去尾斜杠、**拒绝明文 HTTP**、支持 `authlib-injector:yggdrasil-server:` 拖拽前缀。
- ALI 服务发现：GET 根地址读 `X-Authlib-Injector-API-Location`，再读 `meta.serverName` 校验是 Yggdrasil 服务器。
- 登录：`POST /authserver/authenticate`（username/password/clientToken/agent/requestUser），取 `selectedProfile` 或 `availableProfiles[0]`，UUID 转标准带连字符格式。
- 续期：`POST /authserver/validate` 校验，失效走 `POST /authserver/refresh`；clientToken 存在 `Credentials.refreshToken` 槽位（内部约定，已注释）。
- 密码只在登录请求里用一次，**不落盘**。

配套改动：

| 文件 | 改动 |
|---|---|
| `auth/AccountType.java` | 新增 `YGGDRASIL` |
| `auth/Account.java` | 增加 `authServer/authServerName` 字段、`yggdrasil()` 工厂、`isYggdrasil()`、`online()`、storageKey 按「服务器+UUID」、display 显示服务器名 |
| `auth/AccountStore.java` | Entry 增加 `authServer/authServerName` 读写；storageKey 支持 YGGDRASIL |
| `auth/AuthService.java` | `loginYggdrasil()`、`refreshYggdrasil()`、`ensureFresh()` 对 YGGDRASIL 先 validate 再续期 |
| `auth/OAuthHttp.java` | 新增 `getFull()`（保留响应头，ALI 发现用） |
| `ui/components/AccountMenuPopup.java` | 操作区新增「第三方登录…」 |
| `ui/pages/HomePage.java` | 新增 `loginYggdrasil()` 三字段对话框（服务器/账号/密码）；启动参数组装支持外置登录令牌；账号胶囊显示「第三方登录 · 服务器名」 |
| `ui/service/LaunchOptionsFactory.java` | 外置登录也走在线令牌（`userType=mojang`） |

## 3. 验证

- 单元测试 **144/144**（新增 `YggdrasilAuthTest` 5 个：地址规范化/ALI 解析规则/登录取角色/UUID 连字符/clientToken、validate 真假、refresh 轮换、无角色报错）。
- 编译与 `mvn clean package` 通过。

## 4. 待办

- 用真实外置服务器（如 LittleSkin）做一次真机登录 + 启动验证（需要账号）。
- 账号弹层新增行需要再截图确认（程序化点击受前台窗口限制）。
---

# UI 改版（参考图第一轮）：主页重排

用户标注的问题：顶部大片空白、账号胶囊悬空在右上、主卡片边界怪异、底部大片死白。

## 改动

- `HomePage`：内容列限宽 1160、**顶部对齐**（去掉垂直居中）；头部 = 标题/副标题 + 账号胶囊。
- 左列 = 「快速启动」紧凑卡片（版本选择 + 隔离徽标 + 加载器/隔离/模组/来源四行详情 + 大按钮 + 状态）+ 「版本库」磁贴墙。
- 版本库：全部已安装版本渲染成 172×88 磁贴（加载器徽标 + 名称 + id/加载器），点击即切换启动版本，选中磁贴高亮；底部页脚「共 N 个版本 · 在版本管理可设置、备份、删除」。
- 右列 = 智能内存 / Java 状态 / 快捷操作（打开游戏/版本/日志目录）/ 启动器信息（版本 + 数据目录）。
- CSS：新增 `.version-tile` / `.version-tile:hover` / `.version-tile-selected`，暗色与浅色主题同步。

## 修掉的 BUG

- 版本库渲染时 `badgeClass(null)` 抛 NPE（原版版本没有加载器），导致扫描回调中断、磁贴只画到第一个原版版本就停 —— 已加 null → `badge-vanilla`。
- 磁贴从 ScrollPane 改为直接 FlowPane，避免视口高度把第二行裁掉。

## 证据

- 截图：`target/ui78-home-final.png`（8 个磁贴三行、选中高亮、页脚计数）。
- `mvn -o test`：**144/144 通过**。
---

# UI 改版（参考图第一轮·修正版）

用户澄清「红框 = 缩小窗口、把快速启动/智能内存/账号/Java 状态整体缩小，删掉版本库与右下角启动器信息，快捷操作挪到单版本设置里」。

## 改动

- 窗口：初始 1080×640（`min(1080, 屏幕 76%)` × `min(640, 屏幕 66%)`），最小 940×560；内容列限宽 1040、**垂直居中**（窗口变小后上下留白均衡）。
- 主页只保留：标题/副标题 + 账号胶囊 + 「快速启动」卡（版本选择、隔离徽标、加载器/隔离/模组/来源四行、大按钮、状态）+ 右侧「智能内存」「Java 状态」两张小卡。
- 删除：版本库磁贴墙、启动器信息卡、主页快捷操作卡。
- 「快捷操作」移到 `VersionSettingsDialog` 基本设置里的「目录」行：游戏目录 / 版本目录 / 日志目录三个按钮（用 `AppPaths.gameDir(versionId, isolated)` / `versionDir` / `versionLogsDir`）。
- 卡片整体缩小：主页边距 16/20、卡片内边距 18、版本下拉 46、按钮 48、右侧卡内边距 14。

## 证据

- 截图：`target/ui82-home-centered.png`（1620×960 物理 = 1080×640 逻辑）。
- 全量测试待确认（本轮跑 `mvn -o test`）。
---

# UI 改版（第二轮）：账号导航移除 + 下载中心三分栏 + 主题色 + 主页背景

## 改动

- 左侧导航删除「账号」项与对应占位页（账号操作统一走主页右上角胶囊）。
- 下载中心改为「左侧二级分类 + 右侧内容」：
  - **游戏版本**：原版全版本清单、搜索/类型筛选、一键安装、导入整合包/版本文件夹。
  - **Mod**：Modrinth 搜索，支持 游戏版本 / 加载器 筛选 + 目标已安装版本选择；安装时按目标版本推导游戏版本与加载器，下载主文件（SHA-1 校验）到该版本 `mods/`。
  - **整合包**：Modrinth 搜索整合包，`下载并导入` 直接下载 `.mrpack` 并走 `ModpackImporter` 解压成独立实例。
- 自定义主题色：新增 `ui/theme/ThemeManager.java`，把强调色相关规则拼成 CSS 并用 `data:text/css;base64` 挂到 Scene；设置页新增颜色选择器，保存后立即生效（按钮/高亮/进度条/卡片描边）。
- 主页背景图：设置页可上传 PNG/JPG/WEBP（复制到数据目录 `background.<ext>`，写 `homeBackground`）；主页用 `BackgroundSize(contain)` **等比缩放、完整显示、不裁剪**，叠加 42% 压暗层保证文字可读，可一键清除。
- 窗口内边距/侧栏等随新布局微调。

## 证据

- 截图：`target/ui92-downloads.png`（下载分类）、`target/ui94-mods-search.png`（Modrinth 模组搜索，77560 结果）、
  `target/ui101-packs-result.png`（整合包搜索与「下载并导入」）、`target/ui99-settings-end.png`（主题色 + 主页背景）、
  `target/ui91-home-bg2.png`（背景图 contain + 蓝色强调色）。
- `mvn -o test`：**144/144 通过**。
## 背景图修正（用户反馈：要铺满右侧内容区）

- `HomePage.applyBackground` 的 `BackgroundSize` 从 contain 改为 **cover**：等比放大铺满整个内容区（含标题区、右侧卡片区），不拉伸不变形，超出部分裁掉。
- 背景启用时给根节点加 `has-bg` 样式：标题/副标题转为白色 + 阴影，浅色主题下也可读；压暗层改为上深下深的渐变。
- 截图：`target/ui103-home-bg-cover2.png`。
## 背景填充模式 + 桌面开发快捷方式

- `LauncherConfig.backgroundMode`（cover / contain）新增；设置页「背景方式」：填充（铺满、边缘裁切）/ 完整显示（等比缩放、不裁边）。
- `HomePage.applyBackground` 按模式选择 `BackgroundSize(cover)` 或 `BackgroundSize(contain)`；两种模式都不拉伸变形。
- 新增 `scripts/run-dev.cmd`（纯 ASCII，CRLF，用 `%~dp0` 定位项目）：class 缺失时先 `mvn -o -q compile`，然后用 `javaw` 启动开发态（数据目录 `..\_mc-data`）。**注意：.cmd 必须 CRLF + 不用中文路径字面量**，否则 cmd 按 ANSI 解析会报 “'xxx' is not recognized”。
- 桌面创建快捷方式「QUTC 启动器（开发版）」→ `cmd /c run-dev.cmd`，图标 `build/resources/QUTC.ico`，最小化窗口。未打包（项目未完成）。
- 截图：`target/ui104-contain-mode.png`（contain 模式）。
## 字体层级搭配（2026-10-23）

目标：不同字体分工，形成层次（展示/正文/数值/等宽/导航/按钮各司其职）。

| 角色 | 字体 | 说明 |
|---|---|---|
| 页面大标题（开始你的冒险） | **幼圆** 加粗 26px | 圆润、友好，白色 + 轻光晕 |
| 副标题 | **等线 Light** 13px | 纤细，与大标题形成粗细对比 |
| 卡片标题 / 正文 / 版本名 | **微软雅黑 UI**（标题加粗） | 标准 UI 字体，保证可读 |
| 数值（4.0 GB / Java 21） | **Segoe UI Semibold** 23px | 西文数字更硬朗 |
| 元信息 / 路径 | **等线 Light** 12px | 弱化为辅助信息 |
| 左侧主导航 | 雅黑常规 13.5px（提亮） | 选中项切换为**幼圆加粗白色** |
| 二级分类（下载中心） | 雅黑常规（选中幼圆加粗） | 同主导航逻辑 |
| 主按钮（开始游戏） | **幼圆** 加粗 16px | CTA 更圆润醒目 |
| 徽标 badge | 雅黑加粗 11px | 小号清晰 |
| 日志 / 参数文本 | **Consolas** 12px | 等宽，和 UI 字体拉开层次 |

- 截图：`target/ui110-fonts.png`（暗色）、`target/ui111-fonts-light.png`（浅色日志页）、`target/ui112-fonts-dark.png`（最终暗色主页）。
- 全部通过 CSS 覆盖实现（`qutc.css` 末尾），浅色主题自动继承字体、仅覆盖颜色。
## 字体强化（标题/主按钮）

- `.page-title` → 幼圆 31px、weight 900、白色 + 白色光晕（`dropshadow` 0 偏移）；有背景图时用更强白色发光（rgba(255,255,255,0.50), 16px）。
- `.button-primary` → 微软雅黑 UI Bold 17px（比幼圆更粗）+ 白色发光，hover 光晕更大（18px）。
- 说明：幼圆没有独立 Bold 字重，用「字号 + 光晕」模拟加粗；按钮改用雅黑 Bold 以获得真实粗体。
- 截图：`target/ui113-title-glow.png`。
## 字体选择（用户自选）

- `LauncherConfig` 新增 `navFont` / `homeFont` / `labelFont`（空=主题默认），`normalize()` 做 CSS 注入清洗。
- `ThemeManager.fontCss()`：把三类选择合成 CSS（`nav-button/sub-nav-button`、`page-title/page-subtitle/button-primary`、`card-title/version-name/version-meta/badge/value-big/tab-label`），与强调色一起挂到一个 data URI 样式表，保存后立即生效。
- 设置页新增「字体」卡：左侧导航 / 主页标题与按钮 / 标注与说明 三个下拉，选项来自 `Font.getFamilies()`（系统已装字体），带「默认（主题字体）」项。
- 真机验证：选 等线(导航)+微软雅黑(主页)+等线(标注) 后重启，标题/导航/按钮字形全部变化；截图 `target/ui125-home-custom-font.png`、字体卡 `target/ui122-font-picker.png`。
- 测试：144/144 通过；字体为空时回退主题默认搭配。
## 账号皮肤头像 + 导航栏 3D 皮肤预览

- 新增 `core/skin/Skins.java`：皮肤解码、旧版 64×32 → 64×64 兼容、Slim 判断（臂宽透明列）、程序生成的默认皮肤、JavaFX Image 转换。
- 新增 `ui/service/SkinService.java`：读取当前账号 → 命中缓存 `cache/skins/<accountKey>.png` 或从 `skinUrl` 下载 → 通知监听器（FX 线程）。
- 新增 `ui/components/SkinHeadView.java`：从皮肤裁 8×8 脸 + 帽子层（40,8），最近邻放大到 34px，圆角裁切，替换账号胶囊里的字母头像。
- 新增 `ui/components/SkinMesh.java`：按 6 面 UV 构建长方体 MeshView（三角形必须是「点/纹理」成对索引）。
- 新增 `ui/components/SkinPreview3D.java`：SubScene + PerspectiveCamera（z=-262, FOV 32），头/帽/身/双臂/双腿六个长方体，像素尺寸直接乘 4.6 缩放（避免节点 scale 以包围盒中心为 pivot 的错位），绕 Y 轴 -18°~18° 缓慢摆动，鼠标拖动可手动旋转；导航栏底部 168×150 展示，带半透明圆角底。
- 修掉的坑：① MeshView 的 faces 数组必须每三角形 6 个索引（point/texCoord 成对），否则整个网格不渲染；② JavaFX 节点 scale 以布局包围盒中心为 pivot，改用「几何尺寸×缩放 + translate」烘焙。
- 截图：`target/ui156-skin-done.png`；测试 144/144；`mvn -o compile` 通过。