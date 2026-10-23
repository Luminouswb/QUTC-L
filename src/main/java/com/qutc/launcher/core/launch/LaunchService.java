package com.qutc.launcher.core.launch;

import com.qutc.launcher.asset.AssetIndex;
import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.core.install.InstallEngine;
import com.qutc.launcher.core.java.JavaMatrix;
import com.qutc.launcher.core.java.JavaProbe;
import com.qutc.launcher.core.java.JavaRuntime;
import com.qutc.launcher.core.version.LibraryCollector;
import com.qutc.launcher.core.version.Rules;
import com.qutc.launcher.core.version.VersionMeta;
import com.qutc.launcher.core.version.VersionResolver;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.OsUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 启动服务：把「安装 → 解析 → 选 Java → 组装 → 预检 → 起进程」串成一条链。
 *
 * <p>阶段 3 的目标就是这条链能在<b>纯命令行</b>下把游戏真正拉起来；
 * UI（阶段 7）只是它的调用方。
 */
public final class LaunchService {

    private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public record Options(String versionId,
                          String username,
                          String uuid,
                          int memoryMb,
                          int width,
                          int height,
                          boolean fullscreen,
                          boolean isolated,
                          boolean installIfMissing,
                          boolean withAssets,
                          int concurrency,
                          Path javaHomeOverride,
                          List<String> extraJvmArgs,
                          List<String> extraGameArgs,
                          boolean verifyLibraries,
                          String accessToken,
                          String userType,
                          String xuid,
                          String clientId,
                          Path externalRoot) {

        public static Options of(String versionId) {
            return new Options(versionId, "Player", null, 2048, 854, 480, false,
                    true, true, true, 8, null, List.of(), List.of(), true,
                    "0", "legacy", null, null, null);
        }

        /** 是否引用外部目录（引用式导入，不复制文件）。 */
        public boolean external() {
            return externalRoot != null;
        }
    }

    public record Prepared(LaunchContext context,
                           LaunchCommand command,
                           Preflight.Report preflight,
                           InstallEngine.Result install) {
    }

    private LaunchService() {
    }

    /**
     * 完成安装与命令组装，但不启动进程（dry-run 与 UI 预检都走这里）。
     */
    public static Prepared prepare(Options options, Downloader.Listener progressListener) throws IOException {
        return prepare(options, progressListener, MirrorResolver.of(null));
    }

    /** @param mirrors 下载源；为空时按全局配置 */
    public static Prepared prepare(Options options, Downloader.Listener progressListener, MirrorResolver mirrors)
            throws IOException {
        String versionId = options.versionId();
        Path versionsDir = AppPaths.versionsDir();
        Path externalRoot = options.externalRoot();
        boolean external = externalRoot != null;
        Path versionDir = external
                ? externalRoot.resolve("versions").resolve(versionId)
                : AppPaths.versionDir(versionId);
        FsUtils.ensureDir(versionDir);

        // ---- 安装（外部引用的版本不安装，缺文件由预检如实报告） ----
        InstallEngine engine = new InstallEngine(versionsDir, new Downloader(), mirrors);
        boolean needInstall = !external && options.installIfMissing()
                && (InstallEngine.readMarker(versionId) == null || !engine.isVersionJsonPresent(versionId));
        InstallEngine.Result install = null;
        if (needInstall) {
            Log.info("Launch", "开始安装 " + versionId);
            install = engine.install(versionId, new InstallEngine.Options(options.concurrency(), options.withAssets()),
                    progressListener);
        } else if (!external) {
            engine.ensureVersionJson(versionId, false);
        } else {
            Log.info("Launch", "外部引用版本，跳过安装: " + versionId + " @ " + externalRoot);
        }

        // ---- 解析 ----
        VersionResolver resolver = new VersionResolver(external ? externalRoot.resolve("versions") : versionsDir);
        VersionMeta meta = resolver.resolve(versionId);

        boolean customResolution = options.width() > 0 && options.height() > 0;
        Rules.Context rules = Rules.Context.system(Map.of(
                "is_demo_user", false,
                "has_custom_resolution", customResolution,
                "has_quick_plays_support", false,
                "is_quick_play_singleplayer", false,
                "is_quick_play_multiplayer", false,
                "is_quick_play_realms", false));
        LibraryCollector.Selection selection = LibraryCollector.select(meta, rules);

        // ---- Java ----
        JavaRuntime runtime = pickJava(options, versionId, meta);

        // ---- 目录 ----
        Path gameDir = external ? versionDir : AppPaths.gameDir(versionId, options.isolated());
        FsUtils.ensureDir(gameDir);
        Path librariesDir = external ? externalRoot.resolve("libraries") : AppPaths.librariesDir();
        Path nativesDir = AppPaths.nativesDir(versionId);
        FsUtils.ensureDir(nativesDir);

        String jarId = meta.jar != null && !meta.jar.isBlank() ? meta.jar : meta.id;
        Path clientJar = (external ? externalRoot.resolve("versions") : versionsDir)
                .resolve(jarId).resolve(jarId + ".jar");

        Path assetsRootDir = external ? externalRoot.resolve("assets") : AppPaths.assetsDir();
        Path assetsIndexesDir = assetsRootDir.resolve("indexes");
        Path assetsObjectsDir = assetsRootDir.resolve("objects");

        AssetIndex assetIndex = null;
        String assetIndexName = meta.assets != null ? meta.assets
                : (meta.assetIndex == null ? null : meta.assetIndex.id);
        if (assetIndexName != null) {
            Path indexFile = external
                    ? assetsIndexesDir.resolve(assetIndexName + ".json")
                    : AppPaths.assetsIndexFile(assetIndexName);
            if (Files.isRegularFile(indexFile)) {
                assetIndex = JsonUtils.read(indexFile, AssetIndex.class, null);
                if (assetIndex != null && assetIndex.id == null) {
                    assetIndex.id = assetIndexName;
                }
            }
        }
        Path assetRoot = assetsRootDir;
        if (assetIndex != null && assetIndex.virtual) {
            assetRoot = "legacy".equals(assetIndex.id)
                    ? (external ? assetRoot.resolve("virtual").resolve("legacy") : AppPaths.assetsVirtualLegacyDir())
                    : assetRoot.resolve("virtual").resolve(assetIndex.id);
        }

        // ---- 上下文 ----
        String uuid = options.uuid() == null || options.uuid().isBlank()
                ? com.qutc.launcher.utils.HashUtils.offlineUuid(options.username())
                : options.uuid();

        String accessToken = options.accessToken() == null || options.accessToken().isBlank()
                ? "0" : options.accessToken();
        LaunchContext context = new LaunchContext()
                .version(versionId, meta, rules, selection)
                .java(runtime == null ? null : runtime.executable(), options.memoryMb())
                .directories(gameDir, librariesDir, assetRoot, assetIndexName, nativesDir)
                .account(options.username(), uuid, accessToken,
                        options.userType() == null ? "legacy" : options.userType())
                .display(options.width(), options.height(), options.fullscreen())
                .extras(options.extraJvmArgs(), options.extraGameArgs());
        context.xuid = options.xuid();
        context.clientId = options.clientId();
        context.javaRuntime = runtime;
        context.versionDir = versionDir;
        context.clientJar = Files.isRegularFile(clientJar) ? clientJar : null;
        if (external) {
            context.assetsIndexesOverride = assetsIndexesDir;
            context.assetsObjectsOverride = assetsObjectsDir;
        }

        Preflight.Report preflight = Preflight.run(context, options.verifyLibraries());
        LaunchCommand command = preflight.ok() ? LaunchBuilder.build(context) : null;

        return new Prepared(context, command, preflight, install);
    }

    /** 启动进程。调用前必须确认预检通过。 */
    public static GameProcess start(Prepared prepared, Consumer<String> logSink) throws IOException {
        return start(prepared, logSink, false, false);
    }

    /** @param quiet true 时用 javaw.exe 启动（UI 模式不弹控制台） */
    public static GameProcess start(Prepared prepared, Consumer<String> logSink, boolean quiet) throws IOException {
        return start(prepared, logSink, false, quiet);
    }

    /**
     * @param detached true 表示启动器不等待、也不接管游戏输出（输出直接进日志文件），
     *                 启动器退出后游戏继续运行
     */
    public static GameProcess start(Prepared prepared, Consumer<String> logSink, boolean detached, boolean quiet)
            throws IOException {
        LaunchCommand command = prepared.command();
        if (command == null) {
            throw new IOException("预检未通过，拒绝启动：" + prepared.preflight().summary());
        }
        Path logFile = AppPaths.versionLogsDir(prepared.context().versionId)
                .resolve("qutc-game-" + LocalDateTime.now().format(LOG_STAMP) + ".log");
        Log.info("Launch", "启动命令：\n" + command.display());
        Log.info("Launch", "游戏日志：" + logFile);
        if (detached) {
            return GameProcess.startDetached(command, prepared.context().gameDir, logFile);
        }
        GameProcess process = GameProcess.start(command, prepared.context().gameDir, logFile, quiet);
        if (logSink != null) {
            process.onLine(logSink);
        }
        return process;
    }

    /**
     * 选 Java：优先用版本 JSON 自带的 {@code javaVersion.majorVersion}
     * （整合包实例的 id 往往不含游戏版本号，靠 id 猜会猜错），
     * 没有声明时才退回 {@link JavaMatrix} 的版本号区间推断。
     */
    private static JavaRuntime pickJava(Options options, String versionId, VersionMeta meta) {
        List<JavaRuntime> runtimes = new ArrayList<>(JavaProbe.discover());
        if (runtimes.isEmpty()) {
            Log.warn("Launch", "未探测到任何 Java 运行时");
            return null;
        }
        if (options.javaHomeOverride() != null) {
            JavaRuntime forced = runtimes.stream()
                    .filter(runtime -> runtime.home().equals(options.javaHomeOverride()))
                    .findFirst()
                    .orElseGet(() -> JavaProbe.probe(options.javaHomeOverride(), "用户指定"));
            if (forced != null) {
                return forced;
            }
        }
        int declared = meta != null && meta.javaVersion != null ? meta.javaVersion.majorVersion : 0;
        int recommended = declared > 0 ? declared : JavaMatrix.recommendMajor(versionId);
        JavaRuntime best = JavaProbe.best(runtimes, recommended);
        if (best == null) {
            int minimum = declared > 0 ? declared : JavaMatrix.minimumMajor(versionId);
            best = JavaProbe.best(runtimes, minimum);
            if (best != null) {
                Log.warn("Launch", "未找到推荐的 Java " + recommended + "，退回 Java " + best.major());
            }
        }
        if (best != null) {
            Log.info("Launch", "为 " + versionId + " 选用 " + best.displayName()
                    + "（推荐 Java " + recommended + (declared > 0 ? "，版本 JSON 声明" : "") + "）");
        }
        return best;
    }

    /** 供 UI 与诊断展示：版本是否需要额外安装。 */
    public static boolean isInstalled(String versionId) {
        return InstallEngine.readMarker(versionId) != null;
    }

    /** 便捷：命令行直接启动。 */
    public static GameProcess launch(Options options, Downloader.Listener listener,
                                     Consumer<String> logSink) throws IOException {
        Prepared prepared = prepare(options, listener);
        if (!prepared.preflight().ok()) {
            Log.error("Launch", prepared.preflight().summary());
            return null;
        }
        return start(prepared, logSink);
    }

    /** 生成给用户看的环境摘要（无 Java 时的引导文案会用到）。 */
    public static String describeEnvironment() {
        StringBuilder sb = new StringBuilder();
        sb.append("系统: ").append(OsUtils.name()).append(' ').append(OsUtils.version())
                .append(" (").append(OsUtils.arch()).append(")\n");
        sb.append("物理内存: ").append(OsUtils.totalPhysicalMemory() / 1024 / 1024).append(" MB\n");
        List<JavaRuntime> runtimes = JavaProbe.discover();
        if (runtimes.isEmpty()) {
            sb.append("Java: 未检测到任何运行时\n");
        } else {
            sb.append("Java: 检测到 ").append(runtimes.size()).append(" 个运行时\n");
            for (JavaRuntime runtime : runtimes) {
                sb.append("  - ").append(runtime.displayName()).append(" @ ").append(runtime.home()).append('\n');
            }
        }
        return sb.toString();
    }
}
