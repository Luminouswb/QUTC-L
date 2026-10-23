package com.qutc.launcher.config;

import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.OsUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 启动器数据目录布局（对应总纲「固定目录结构」）。
 *
 * <pre>
 * launcher-root/
 *   launcher.json
 *   accounts/
 *   shared/libraries/  shared/assets/
 *   versions/&lt;id&gt;/{版本JSON, version-config.json, mods, saves, config,
 *                     resourcepacks, shaderpacks, natives, logs}
 *   logs/  cache/
 * </pre>
 *
 * <p>根目录选址规则：优先「绿色便携」——exe 同目录可写就就地存放；
 * 装在 Program Files 等只读位置时自动回落到 {@code %APPDATA%\QUTC}。
 */
public final class AppPaths {

    private static Path root;

    private AppPaths() {
    }

    /** 由入口显式初始化；未初始化时按便携规则自动推导。 */
    public static synchronized void init(Path baseDir) {
        if (root != null) {
            return;
        }
        Path candidate = baseDir == null ? detectBaseDir() : baseDir;

        // 显式环境变量优先（方便多开、便携包固定目录）
        String override = System.getenv("QUTC_HOME");
        if (override != null && !override.isBlank()) {
            candidate = Paths.get(override);
        }
        String propertyOverride = System.getProperty("qutc.home");
        if (propertyOverride != null && !propertyOverride.isBlank()) {
            candidate = Paths.get(propertyOverride);
        }

        if (!FsUtils.isWritable(candidate)) {
            Path appData = appDataFallback();
            Log.warn("Paths", "目录不可写，回落到 " + appData);
            candidate = appData;
        }
        try {
            Files.createDirectories(candidate);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建启动器数据目录: " + candidate, e);
        }
        root = candidate.toAbsolutePath().normalize();
    }

    private static Path detectBaseDir() {
        // jpackage app-image 下 <image>/QUTC.exe，app/ 子目录里是 jar
        try {
            Path located = Paths.get(AppPaths.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(located) ? located : located.getParent();
            // 开发期位于 target/classes，向上找到项目根
            if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equals("classes")) {
                Path target = dir.getParent();
                if (target != null && target.getFileName() != null
                        && target.getFileName().toString().equals("target")) {
                    return target.getParent();
                }
            }
            if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equals("app")) {
                Path imageRoot = dir.getParent();
                if (imageRoot != null) {
                    return imageRoot;
                }
            }
            return dir == null ? Paths.get(".").toAbsolutePath() : dir;
        } catch (Exception e) {
            return Paths.get(".").toAbsolutePath();
        }
    }

    private static Path appDataFallback() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "QUTC");
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData, "QUTC");
        }
        return Paths.get(System.getProperty("user.home", "."), OsUtils.isWindows() ? "QUTC" : ".qutc");
    }

    public static Path root() {
        if (root == null) {
            init(null);
        }
        return root;
    }

    public static Path launcherConfig() {
        return root().resolve("launcher.json");
    }

    public static Path accountsDir() {
        return root().resolve("accounts");
    }

    public static Path accountStore() {
        return accountsDir().resolve("accounts.dat");
    }

    public static Path sharedDir() {
        return root().resolve("shared");
    }

    public static Path librariesDir() {
        return sharedDir().resolve("libraries");
    }

    public static Path assetsDir() {
        return sharedDir().resolve("assets");
    }

    public static Path versionsDir() {
        return root().resolve("versions");
    }

    public static Path versionDir(String versionId) {
        return versionsDir().resolve(FsUtils.sanitizeName(versionId));
    }

    public static Path logsDir() {
        return root().resolve("logs");
    }

    public static Path cacheDir() {
        return root().resolve("cache");
    }

    /** 关闭版本隔离后回落的全局游戏目录。 */
    public static Path globalGameDir() {
        return root();
    }

    // ---- 版本内的固定子目录（总纲「固定目录结构」） ----

    public static Path versionJson(String versionId) {
        Path dir = versionDir(versionId);
        return dir.resolve(dir.getFileName() + ".json");
    }

    /** 单版本配置（隔离开关、内存覆盖、Java 覆盖等）。 */
    public static Path versionConfig(String versionId) {
        return versionDir(versionId).resolve("version-config.json");
    }

    /** 安装完成标记，用于判断是否需要重装/补全。 */
    public static Path installedMarker(String versionId) {
        return versionDir(versionId).resolve(".qutc-installed.json");
    }

    /**
     * 实际游戏目录（存档、mods、配置都落在这一层）。
     *
     * @param isolated 是否开启版本隔离；关闭时所有版本共用 {@link #globalGameDir()}
     */
    public static Path gameDir(String versionId, boolean isolated) {
        return isolated ? versionDir(versionId) : globalGameDir();
    }

    public static Path nativesDir(String versionId) {
        return versionDir(versionId).resolve("natives");
    }

    public static Path modsDir(String versionId, boolean isolated) {
        return gameDir(versionId, isolated).resolve("mods");
    }

    public static Path savesDir(String versionId, boolean isolated) {
        return gameDir(versionId, isolated).resolve("saves");
    }

    public static Path resourcePacksDir(String versionId, boolean isolated) {
        return gameDir(versionId, isolated).resolve("resourcepacks");
    }

    public static Path shaderPacksDir(String versionId, boolean isolated) {
        return gameDir(versionId, isolated).resolve("shaderpacks");
    }

    public static Path gameConfigDir(String versionId, boolean isolated) {
        return gameDir(versionId, isolated).resolve("config");
    }

    public static Path versionLogsDir(String versionId) {
        return versionDir(versionId).resolve("logs");
    }

    // ---- 共享资源 ----

    public static Path assetsIndexesDir() {
        return assetsDir().resolve("indexes");
    }

    public static Path assetsObjectsDir() {
        return assetsDir().resolve("objects");
    }

    /** 老版本（1.7 及更早）使用的虚拟资源目录。 */
    public static Path assetsVirtualLegacyDir() {
        return assetsDir().resolve("virtual").resolve("legacy");
    }

    public static Path assetsIndexFile(String indexId) {
        return assetsIndexesDir().resolve(indexId + ".json");
    }
}
