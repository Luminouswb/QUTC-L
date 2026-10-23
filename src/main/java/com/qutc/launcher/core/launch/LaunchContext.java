package com.qutc.launcher.core.launch;

import com.qutc.launcher.core.version.LibraryCollector;
import com.qutc.launcher.core.version.Rules;
import com.qutc.launcher.core.version.VersionMeta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动所需的全部上下文。
 *
 * <p>刻意做成可变字段 + 链式 setter：启动器内部组装链路很长，
 * 用 record 会让「先建后补」的流程很别扭。
 */
public final class LaunchContext {

    // ---- 版本 ----
    public String versionId;
    public VersionMeta meta;
    public Rules.Context rules;
    public LibraryCollector.Selection selection;
    public Path versionDir;
    public Path clientJar;

    // ---- 运行时 ----
    public Path javaExecutable;
    public com.qutc.launcher.core.java.JavaRuntime javaRuntime;
    public int memoryMb = 2048;

    // ---- 目录 ----
    public Path gameDir;
    public Path librariesDir;
    /** {@code --assetsDir} 的真实取值；老版本 virtual 布局下指向 virtual 目录。 */
    public Path assetsDir;
    public String assetIndexName;
    public Path nativesDir;
    /** 外部导入版本时资源索引/对象的真实位置；为空则用启动器自己的目录。 */
    public Path assetsIndexesOverride;
    public Path assetsObjectsOverride;

    // ---- 账号 ----
    public String username = "Player";
    public String uuid;
    public String accessToken = "0";
    public String userType = "legacy";
    public String xuid;
    public String clientId;

    // ---- 显示 ----
    public int width;
    public int height;
    public boolean fullscreen;
    /** 是否把自定义分辨率写进条件参数（features.has_custom_resolution）。 */
    public boolean customResolution;

    // ---- 附加参数 ----
    public List<String> extraJvmArgs = new ArrayList<>();
    public List<String> extraGameArgs = new ArrayList<>();

    public String launcherName = "QUTC";
    public String launcherVersion = "0.1.0";

    public LaunchContext version(String id, VersionMeta meta, Rules.Context rules,
                                 LibraryCollector.Selection selection) {
        this.versionId = id;
        this.meta = meta;
        this.rules = rules;
        this.selection = selection;
        return this;
    }

    public LaunchContext java(Path executable, int memoryMb) {
        this.javaExecutable = executable;
        this.memoryMb = memoryMb;
        return this;
    }

    public LaunchContext directories(Path gameDir, Path librariesDir, Path assetsDir,
                                     String assetIndexName, Path nativesDir) {
        this.gameDir = gameDir;
        this.librariesDir = librariesDir;
        this.assetsDir = assetsDir;
        this.assetIndexName = assetIndexName;
        this.nativesDir = nativesDir;
        return this;
    }

    public LaunchContext account(String username, String uuid, String accessToken, String userType) {
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.userType = userType;
        return this;
    }

    public LaunchContext display(int width, int height, boolean fullscreen) {
        this.width = width;
        this.height = height;
        this.fullscreen = fullscreen;
        this.customResolution = width > 0 && height > 0;
        return this;
    }

    public LaunchContext extras(List<String> jvmArgs, List<String> gameArgs) {
        if (jvmArgs != null) {
            this.extraJvmArgs = new ArrayList<>(jvmArgs);
        }
        if (gameArgs != null) {
            this.extraGameArgs = new ArrayList<>(gameArgs);
        }
        return this;
    }
}
