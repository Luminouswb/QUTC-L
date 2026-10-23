package com.qutc.launcher.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 单版本配置 {@code versions/<id>/version-config.json}。
 *
 * <p>总纲双层配置里的「版本层」：隔离开关、独立内存、独立 Java、独立分辨率、
 * 独立 JVM/游戏参数、隐藏标记、自定义显示名。全局 {@code launcher.json} 是默认值，
 * 这里的值只要不是空/0 就覆盖全局。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class VersionConfig {

    /** 版本目录名（不可变，加载器 id 与它绑定）。 */
    public String versionId;

    /** 版本隔离：存档/mods/config 放在版本目录内。默认开启（总纲要求）。 */
    public boolean isolated = true;

    /** 0 表示跟随全局/自动推荐。 */
    public int memoryMb = 0;

    /** null 表示自动选 Java。 */
    public String javaPath;

    /** 0 表示跟随全局分辨率。 */
    public int width = 0;
    public int height = 0;
    public boolean fullscreen = false;

    /** 隐藏后不在主页快速启动列表出现，版本管理页仍可见。 */
    public boolean hidden = false;

    /** 自定义显示名（重命名只改这个，不动目录，避免破坏加载器 id）。 */
    public String displayName;

    public List<String> jvmArgs = new ArrayList<>();
    public List<String> gameArgs = new ArrayList<>();

    public static VersionConfig load(String versionId) {
        VersionConfig fallback = defaults(versionId);
        VersionConfig loaded = JsonUtils.read(AppPaths.versionConfig(versionId), VersionConfig.class, null);
        if (loaded == null) {
            return fallback;
        }
        loaded.versionId = versionId;
        loaded.normalize();
        return loaded;
    }

    public static VersionConfig defaults(String versionId) {
        VersionConfig config = new VersionConfig();
        config.versionId = versionId;
        // 隔离默认值来自全局配置
        config.isolated = ConfigManager.get().isolatedByDefault;
        config.normalize();
        return config;
    }

    public void save() {
        try {
            JsonUtils.write(AppPaths.versionConfig(versionId), this);
        } catch (IOException e) {
            Log.warn("Config", "版本配置保存失败: " + versionId + " | " + e.getMessage());
        }
    }

    public void normalize() {
        if (jvmArgs == null) {
            jvmArgs = new ArrayList<>();
        }
        if (gameArgs == null) {
            gameArgs = new ArrayList<>();
        }
        if (memoryMb < 0) {
            memoryMb = 0;
        }
        if (memoryMb > 0 && memoryMb < 512) {
            memoryMb = 512;
        }
        if (width < 0) {
            width = 0;
        }
        if (height < 0) {
            height = 0;
        }
        if (displayName != null && displayName.isBlank()) {
            displayName = null;
        }
    }

    /** 用户看到的版本名：自定义名优先，否则目录名。 */
    public String label() {
        return displayName == null || displayName.isBlank() ? versionId : displayName;
    }
}
