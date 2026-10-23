package com.qutc.launcher.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局配置 {@code launcher.json}。
 *
 * <p>与「版本独立配置 version-config.json」构成总纲要求的双层配置：
 * <ul>
 *   <li>本类 = 全局默认值与机器级设置；</li>
 *   <li>version-config.json = 单版本覆盖，优先级高于本类；</li>
 *   <li>账号凭据独立加密存储，<b>不进入</b>以上任何一个文件。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class LauncherConfig {

    /** 内存三模式：自动推荐 / 全局手动 / 单版本独立。 */
    public enum MemoryMode {
        AUTO, GLOBAL, PER_VERSION
    }

    /** 四档下载源。 */
    public enum DownloadSource {
        OFFICIAL, BMCLAPI, MCBBS, CUSTOM
    }

    // ---- Java ----
    public String javaPath;
    public boolean autoSelectJava = true;
    public List<String> javaScanPaths = new ArrayList<>();

    // ---- 内存 ----
    public MemoryMode memoryMode = MemoryMode.AUTO;
    public int globalMemoryMb = 4096;
    public boolean jvmArgsAuto = true;

    // ---- 下载 ----
    public DownloadSource downloadSource = DownloadSource.BMCLAPI;
    public String customSourceBase;
    public int parallelDownloads = 8;

    // ---- 路径 ----
    public String gameRoot;
    public boolean isolatedByDefault = true;

    // ---- 启动参数 ----
    public List<String> jvmArgs = new ArrayList<>();
    public String gameArgs = "";
    public int resolutionWidth = 1280;
    public int resolutionHeight = 720;
    public boolean fullscreen = false;
    public boolean borderless = false;

    // ---- 外观 / 行为 ----
    public String theme = "dark";
    /** 自定义强调色（#RRGGBB）。 */
    public String themeColor = "#7C5CFF";
    /** 主页背景图文件名（存在数据目录下）；为空表示不启用。 */
    public String homeBackground;
    /** 背景显示方式：cover=填充铺满（裁边），contain=完整显示（留边）。 */
    public String backgroundMode = "cover";
    public String logLevel = "INFO";
    public String language = "zh_CN";
    public boolean closeLauncherAfterGameStart = false;
    public boolean showLogWindow = true;

    // ---- 账号 ----
    /** 微软 OAuth 的 Azure 应用 Client ID；留空则只能离线登录。 */
    public String microsoftClientId;

    // ---- 更新（阶段 12） ----
    /** 自更新清单源（静态目录，放 files.json）。留空则关闭在线检查。 */
    public String updateBaseUrl;
    /** 启动时自动检查更新。 */
    public boolean autoUpdate = true;

    // ---- 会话记忆 ----
    public String lastVersionId;
    public String lastAccountId;

    public static LauncherConfig defaults() {
        return new LauncherConfig();
    }

    /** 修正越界值，避免用户手改 JSON 后把启动器搞崩。 */
    public void normalize() {
        if (resolutionWidth < 320) {
            resolutionWidth = 854;
        }
        if (resolutionHeight < 240) {
            resolutionHeight = 480;
        }
        if (globalMemoryMb < 512) {
            globalMemoryMb = 512;
        }
        if (parallelDownloads < 1) {
            parallelDownloads = 1;
        }
        if (parallelDownloads > 32) {
            parallelDownloads = 32;
        }
        if (memoryMode == null) {
            memoryMode = MemoryMode.AUTO;
        }
        if (downloadSource == null) {
            downloadSource = DownloadSource.BMCLAPI;
        }
        if (jvmArgs == null) {
            jvmArgs = new ArrayList<>();
        }
        if (javaScanPaths == null) {
            javaScanPaths = new ArrayList<>();
        }
        if (theme == null || theme.isBlank()) {
            theme = "dark";
        }
        if (themeColor == null || !themeColor.trim().matches("#[0-9a-fA-F]{6}")) {
            themeColor = "#7C5CFF";
        }
        if (homeBackground != null && !homeBackground.matches("[A-Za-z0-9_.-]{1,64}")) {
            homeBackground = null;
        }
        if (!"contain".equalsIgnoreCase(backgroundMode) && !"cover".equalsIgnoreCase(backgroundMode)) {
            backgroundMode = "cover";
        }
        if (logLevel == null || logLevel.isBlank()) {
            logLevel = "INFO";
        }
    }
}
