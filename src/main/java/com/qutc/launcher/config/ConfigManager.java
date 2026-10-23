package com.qutc.launcher.config;

import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;

/**
 * 全局配置读写。所有改动通过 {@link #save()} 原子落盘。
 */
public final class ConfigManager {

    private static final Object LOCK = new Object();
    private static volatile LauncherConfig config = LauncherConfig.defaults();
    private static volatile boolean loaded;

    private ConfigManager() {
    }

    public static LauncherConfig get() {
        if (!loaded) {
            load();
        }
        return config;
    }

    public static synchronized void load() {
        synchronized (LOCK) {
            LauncherConfig fallback = LauncherConfig.defaults();
            LauncherConfig loadedConfig = JsonUtils.read(AppPaths.launcherConfig(), LauncherConfig.class, fallback);
            if (loadedConfig == null) {
                loadedConfig = fallback;
            }
            loadedConfig.normalize();
            config = loadedConfig;
            loaded = true;
            Log.info("Config", "全局配置已加载: " + AppPaths.launcherConfig());
        }
    }

    public static void save() {
        synchronized (LOCK) {
            try {
                config.normalize();
                JsonUtils.write(AppPaths.launcherConfig(), config);
                Log.debug("Config", "全局配置已保存");
            } catch (IOException e) {
                Log.error("Config", "全局配置保存失败", e);
            }
        }
    }

    /** 修改后立即落盘的便捷入口。 */
    public static void update(java.util.function.Consumer<LauncherConfig> mutator) {
        synchronized (LOCK) {
            mutator.accept(config);
            save();
        }
    }
}
