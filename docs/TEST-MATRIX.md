# QUTC 回归矩阵（阶段 13）

> 环境：Windows 11 x64 / 2560×1600@150% / 物理内存 15549 MB / 数据目录 `_mc-data`
> 执行时间：2026-10-22 23:42–23:45（阶段 11–13 修改之后）

## 1. 七级检查 × 全部已安装版本（`--dry-run`，退出码 0 = 全部通过）

| 版本 | 退出码 | 结果 |
|---|---|---|
| 1.20.1 | 0 | 七级检查全通过 |
| 1.20.1-forge-47.2.0 | 0 | 七级检查全通过 |
| 1.21.1 | 0 | 七级检查全通过 |
| 1.8.9 | 0 | 七级检查全通过 |
| fabric-loader-0.15.11-1.20.1 | 0 | 七级检查全通过 |
| neoforge-21.1.72 | 0 | 七级检查全通过 |
| quilt-loader-0.23.0-1.20.1 | 0 | 七级检查全通过 |
| QUTC 冒烟整合包-1.20.1（mrpack 导入实例） | 0 | 七级检查全通过 |

命令：
```powershell
java -Dqutc.home=<_mc-data> -cp 'target\classes;target\lib\*' com.qutc.launcher.main.Launcher --dry-run <versionId>
```
退出码语义（`Cli.java`）：0 = Preflight 全通过；3 = 有检查项失败。

## 2. 真机启动矩阵（进入主菜单 = 通过）

| 版本 | 加载器 | 选用 Java | 结果 | 证据 |
|---|---|---|---|---|
| 1.8.9 | 原版 | Java 8u51（Mojang jre-legacy） | ✅ 主菜单 | `latest.log`：`Created: 512x512 textures-atlas`（23:42:59） |
| 1.20.1 | 原版 | Java 17（Oracle 17.0.10） | ✅ 主菜单 | 阶段 3 日志 |
| 1.20.1 | Forge 47.2.0 | Java 17 | ✅ 主菜单 | `MinecraftForge v47.2.0 Initialized`（阶段 6） |
| 1.20.1 | Fabric 0.15.11 + Sodium 0.5.13 | Java 17 | ✅ 主菜单 | 阶段 8/10；Sodium 已加载 |
| 1.20.1 | Quilt 0.23.0 | Java 17 | ✅ 主菜单 | `with Quilt Loader 0.23.0`（阶段 6） |
| 1.21.1 | NeoForge 21.1.72 | Java 21.0.12.1 | ✅ 主菜单 | `ModLauncher 11.0.4`（阶段 6） |
| QUTC 冒烟整合包-1.20.1 | Fabric（mrpack 实例，含 overrides） | Java 17 | ✅ 主菜单 | 阶段 10 实时日志截图 `ui26-live-log3.png` |

## 3. Java 矩阵

| 游戏版本 | 推荐 Java | 验证方式 |
|---|---|---|
| 1.8–1.11 | 8 | `JavaMatrixTest` + 1.8.9 真机 |
| 1.12–1.16 | 8（17 可跑） | `JavaMatrixTest` |
| 1.17–1.20.4 | 17 | 1.20.1 全家族真机 |
| 1.20.5 / 1.21+ | 21 | NeoForge 21.1.72 真机 |

版本 JSON 声明了 `javaVersion.majorVersion` 时以声明为准（整合包实例 id 不含游戏版本号时尤其重要）。

## 4. 已修 BUG 回归清单

| # | BUG | 状态 |
|---|---|---|
| 1 | 资源索引无 `id` 字段导致 NPE | 已修（三处回填） |
| 2 | text2speech 被黑名单误杀 | 已修（删除黑名单） |
| 3 | 跨架构 natives（arm64/x86）混入 classpath | 已修（`acceptedForArch`） |
| 4 | 1.8.9 单版本重复坐标被合并后丢 lwjgl | 已修（仅父子去重） |
| 5 | lwjgl-platform 被误杀 | 已修 |
| 6 | `--no-wait` 断管导致游戏秒退 | 已修（`startDetached`） |
| 7 | jpackage NPE 掩盖真实错误 | 已定位（工具链移出工作区） |
| 8 | Quilt 被识别成 Fabric | 已修（版本 id 关键字优先） |
| 9 | `dedupeJvmArgs` 折叠重复 `--add-opens` | 已修（只去重 `-D`） |
| 10 | 现代 Forge 不跑安装器处理器无法启动 | 已修（无头安装器） |
| 11 | 安装器把原版版本目录当加载器版本 | 已修（跳过原版目录） |
| 12 | ModsPage 版本下拉显示 `Entry[...]` | 已修（StringConverter） |
| 13 | 整合包实例选错 Java（id 无版本号） | 已修（优先 JSON 声明） |
| 14 | GameLogWindow 在后台线程创建 Stage | 已修（FX 线程先建窗） |
| 15 | UI 启动弹出黑色控制台窗口 | 已修（UI 模式用 javaw.exe） |
| 16 | 设置页 ScrollPane 白底/标题被裁 | 已修（透明样式 + vvalue=0） |

## 5. 已知限制（不阻塞验收）

- OptiFine：仅做「纯净 / 混搭」识别（无公开下载入口，需用户自备安装器产物）。
- 真实 Modrinth 整合包的 `files` 下载依赖 `cdn.modrinth.com`，本机直连易超时；重试可完成。
- 自更新需要部署静态 `files.json` 服务端；本机只验证了清单解析 / 版本比较 / sha256 / 回滚逻辑（单测）。
- 干净虚拟机（未装 Java / 无 .NET 3.5）的安装包验证尚未做——需要一台干净 VM；交付包已带精简 JRE。
