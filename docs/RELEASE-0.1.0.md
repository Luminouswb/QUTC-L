# QUTC 启动器 0.1.0 · 发布说明与验收清单

> 构建时间：2026-10-22 23:48（`mvn clean package` + `jpackage`）
> 环境：Windows 11 x64（2560×1600@150% / 16 GB）
> 源码：`C:\Users\独钓寒江雪\Desktop\大肥鱼工作区\QUTC`

## 1. 产物

| 产物 | 路径 | 大小 | 说明 |
|---|---|---|---|
| EXE 安装包 | `target/release/QUTC-0.1.0.exe` | 68.39 MB | jpackage + WiX 3.11 生成，带开始菜单/桌面快捷方式、安装目录选择、升级 UUID |
| MSI 安装包 | `target/release/QUTC-0.1.0.msi` | 67.81 MB | Windows Installer，适合企业分发/静默安装 |
| 绿色免安装版 | `target/release/QUTC-portable-0.1.0.zip` | 66.79 MB | 解压即用，内置精简 JRE（app-image 解压后 165.5 MB，其中 runtime 150.31 MB） |

三份产物均包含 **JavaFX 21.0.5 + Corretto 21.0.12.1 精简运行时**，目标机无需安装 Java。

## 2. 已验证证据

- **打包产物自检**：`QUTC.exe --self-test` → **15/15 通过**（JDK 版本 / SHA-1 / SHA-256 / 离线 UUID / ZIP 打包解压 / ZIP 防穿越 / JSON 读写 / JSON 容错 / AES-GCM / DPAPI / 数据目录读写 / 日志目录 / 全路径构造 / 架构识别 / 物理内存探测）。
- **打包后 GUI**：`QUTC.exe` 直接开窗，日志 `主窗口已显示（透明窗口=true），JavaFX=21.0.5`，数据目录落在程序目录（便携优先）。
- **不依赖系统 Java**：用 `PATH=C:\Windows\System32;C:\Windows`、清空 `JAVA_HOME/MAVEN_HOME` 的干净环境启动 `QUTC.exe`，仍正常开窗，日志 `JRE=21.0.12.1（Amazon.com Inc.）`（走内置运行时）。
- **回归矩阵**：见 `docs/TEST-MATRIX.md`——8 个已装版本 `--dry-run` 全部退出码 0；1.8.9 / 1.20.1 / Forge / Fabric(+Sodium) / Quilt / NeoForge 1.21.1 / mrpack 导入实例全部真机进主菜单。
- **单元测试**：`mvn test` **135/135**（版本引擎、启动参数、下载器、镜像、账号（含旧版内置客户端登录）、加载器、模组/资源包/整合包、崩溃诊断、更新）。

## 3. 总纲第八章验收清单

| # | 标准 | 状态 | 证据 / 说明 |
|---|---|---|---|
| 1 | 全版本 MC 可启动（1.8 ~ 最新快照） | ✅ | 1.8.9（Java 8）与 1.20.1（Java 17）真机；1.21.1 随 NeoForge 真机；版本清单缓存 920 个版本（latest=26.3），下载中心可直接装未安装版本 |
| 2 | 全加载器兼容 | ✅ | Forge 47.2.0 / NeoForge 21.1.72 / Fabric 0.15.11 / Quilt 0.23.0 均真机进主菜单；OptiFine **仅识别**（无公开下载入口，见限制） |
| 3 | 微软登录完整、自动续期、refresh 轮换 | ✅ | 默认用旧版启动器的公开客户端（`00000000402b5328` + `login.live.com/oauth20_desktop.srf` + 手动粘贴回调地址），**无需 Azure 注册**；填了 Client ID 则切换为 PKCE + loopback 自动回调。六步链路、RefreshToken 原子轮换、DPAPI 加密落盘；`LegacyLoginTest` 6 个 + 原有 87 个账号测试。**尚未用真实微软账号完成一次登录**（需要你有正版账号配合） |
| 4 | 下载稳定、自动修复、镜像切换 | ✅ | 四档源 + 8 条镜像规则；删库重装验证自动切源；BMCLAPI 不可用时按顺序降级；断点续传 + sha1/sha256 双校验 |
| 5 | 版本隔离、切整合包不串档 | ✅ | 每版本独立 `mods/saves/config`；mrpack 实例独立目录；Sodium 只出现在 fabric 实例（mods 1） |
| 6 | 整合包导入零报错 | ✅ | synthetic `.mrpack` E2E：加载器安装 + overrides + 版本 JSON + `.qutc-installed.json` + 元数据回读，failures=0；真实 Modrinth 包受 CDN 影响（见限制） |
| 7 | 崩溃诊断精准、不臆测 | ✅ | `CrashAnalyzer` 14 条规则 + 8 个单测；UI 用合成崩溃日志实测输出 3 条命中（OOM / 缺前置 / GLFW），未命中时明确提示"未命中已知原因" |
| 8 | 内存智能分配、不超配 | ✅ | §三.5 档位 + 60% 物理内存上限 + 512 MB 对齐；超上限先确认；UI 实时显示推荐值与上限 |
| 9 | 自更新无损、可回滚 | ✅（逻辑） | 清单拉取/版本比较/主 jar/sha256/staging/pending/替换脚本/备份回滚 + 4 个单测；**端到端需部署 `files.json` 服务端**（见限制） |
| 10 | UI 流畅、无卡顿、无闪退 | ✅ | 启动/下载全在后台线程；自定义无边框窗口 + 八向缩放 + 页面动画；暗/浅双主题；实时日志窗口与日志页真机截图 |
| 11 | 干净 Windows 上安装包/绿色版能启动并给出无 Java 引导 | ✅（绿色版） | 绿色版在"无系统 Java + 干净 PATH"下正常开窗（内置 JRE）；无 Java 时设置页「Java 管理」明确提示"未检测到 Java，请安装 JDK 21 或手动添加"。**完整纯净虚拟机（含未启用 .NET 3.5）仍需一台 VM 复测** |

## 4. 使用说明

1. **安装版**：双击 `QUTC-0.1.0.exe`，可改安装目录、勾选开始菜单/桌面快捷方式；MSI 可用 `msiexec /i QUTC-0.1.0.msi` 静默部署。
2. **绿色版**：解压 `QUTC-portable-0.1.0.zip` 后运行 `QUTC.exe`；数据写在程序目录（不可写时自动回落 `%APPDATA%\QUTC`）。
3. **首次使用**：下载中心选版本安装 → 主页选版本 → 账号胶囊里离线/微软登录 → 开始游戏。
4. **下载源**：设置 → 下载与安装，默认 `BMCLAPI → mcbbs → 官方` 自动降级，可改自定义镜像。
5. **自更新**：在设置 → 更新填一个静态目录地址，提供 `files.json`（格式见 `docs/PROGRESS.md` 阶段 12），即可「检查更新并应用」；回滚用 `--rollback-update`。

## 5. 已知限制

- **OptiFine**：只做识别（`LoaderDetector` 关键字 + 混搭），不提供下载入口。
- **真实 Modrinth 整合包**：`files` 下载走 `cdn.modrinth.com`，本机直连不稳定（重试可完成）。
- **自更新端到端**：替换脚本已实现并做逻辑测试，但需要一个真实更新源才能跑通"下载→替换→重启"。
- **纯净虚拟机**：本机已完成"无系统 Java 启动"验证；未启用 .NET 3.5 的安装包行为需干净 VM 复测。
- **微软正版登录**：默认走内置客户端（浏览器登录后粘贴回调地址），已用伪造 code 验证请求被微软接受（`invalid_grant` 而非 `invalid_client`），但没有真实正版账号做完整登录验证；想要全自动回调可在设置里填自建 Azure Client ID。

## 6. 附：验证命令

```powershell
# 产物自检
target\dist-app-image\QUTC\QUTC.exe --self-test

# 全量单测
mvn -s C:\qutc-toolchain\maven-settings.xml -o test

# 七级检查（任一版本）
java -Dqutc.home=<数据目录> -cp 'target\classes;target\lib\*' com.qutc.launcher.main.Launcher --dry-run 1.20.1

# 打包
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\package.ps1 -Type installers
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\package.ps1 -Type app-image
```
