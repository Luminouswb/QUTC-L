package com.qutc.launcher.main;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.ui.MainApp;
import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.TempDir;
import javafx.application.Application;

/**
 * 启动器唯一入口。
 *
 * <p>JVM 系统属性由 {@code LaunchContext} 统一注入（总纲：启动参数必须通知游戏真实品牌与版本），
 * 这里只负责「目录 → 日志 → 配置 → UI」的确定性初始化顺序。
 */
public final class Launcher {

    public static final String NAME = "QUTC Launcher";
    public static final String VERSION = "0.1.0";

    /** 写入 -Dminecraft.launcher.brand 的值。 */
    public static final String LAUNCHER_BRAND = "QUTC";
    /** 写入 -Dminecraft.launcher.version 的值。 */
    public static final String LAUNCHER_VERSION = VERSION;

    private Launcher() {
    }

    public static void main(String[] args) {
        boolean selfTest = false;
        for (String arg : args) {
            if ("--self-test".equals(arg)) {
                selfTest = true;
            }
        }

        // 1. 数据目录（绿色便携优先，只读位置回落 %APPDATA%）
        AppPaths.init(null);

        // 1.5 临时目录守卫：必须在 JNA / ZIP / 断点续传之前完成，
        //     否则 %TEMP% 异常的机器会在后续环节炸出看不懂的 UnsatisfiedLinkError
        TempDir.ensureWritable(AppPaths.cacheDir());

        // 2. 日志系统（先于一切，保证后续任何失败都有记录）
        Log.init(AppPaths.logsDir(), Log.Level.INFO);
        Log.info("Launcher", NAME + " " + VERSION + " 启动，数据目录=" + AppPaths.root());
        Log.info("Launcher", "系统=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + "，JRE=" + System.getProperty("java.version")
                + "（" + System.getProperty("java.vendor") + "）");

        // 3. 全局配置（坏文件自动修复，不会因配置损坏起不来）
        ConfigManager.load();
        Log.setLevel(Log.Level.parse(ConfigManager.get().logLevel, Log.Level.INFO));

        if (selfTest) {
            int code = SelfTest.run();
            Log.info("SelfTest", code == 0 ? "全部通过" : "存在失败项，退出码=" + code);
            System.exit(code);
            return;
        }

        // 3.5 纯命令行模式（阶段 3：不启动 UI，直接跑安装/启动闭环）
        if (Cli.handles(args)) {
            int code = Cli.run(args);
            Log.info("Cli", "命令行模式结束，退出码=" + code);
            System.exit(code);
            return;
        }

        // 4. UI
        Application.launch(MainApp.class, args);
    }
}
