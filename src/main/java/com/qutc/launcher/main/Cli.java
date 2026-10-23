package com.qutc.launcher.main;

import com.qutc.launcher.auth.Account;
import com.qutc.launcher.auth.AuthException;
import com.qutc.launcher.auth.AuthService;
import com.qutc.launcher.auth.Credentials;
import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.config.LauncherConfig;
import com.qutc.launcher.core.install.InstallEngine;
import com.qutc.launcher.core.launch.GameProcess;
import com.qutc.launcher.core.launch.LaunchCommand;
import com.qutc.launcher.core.launch.LaunchService;
import com.qutc.launcher.core.launch.Preflight;
import com.qutc.launcher.core.loader.LoaderDetector;
import com.qutc.launcher.core.loader.LoaderInstaller;
import com.qutc.launcher.core.loader.LoaderKind;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.update.UpdateService;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 纯命令行入口（阶段 3 的验收载体）。
 *
 * <pre>
 *   QUTC.exe --env                                  查看环境（Java / 内存 / 数据目录）
 *   QUTC.exe --list                                 列出已安装版本
 *   QUTC.exe --install 1.20.1                       只安装
 *   QUTC.exe --dry-run 1.20.1                       只组装并打印启动命令 + 七级检查
 *   QUTC.exe --launch 1.20.1 --username Steve       真正启动游戏
 *   QUTC.exe --launch 1.20.1 --memory 4096 --width 1280 --height 720 --fullscreen
 *   QUTC.exe --login-offline Steve                  新建离线账号并设为当前
 *   QUTC.exe --login-microsoft --client-id &lt;id&gt;    微软正版登录（授权码 + PKCE）
 *   QUTC.exe --accounts                             列出账号（* 为当前账号）
 *   QUTC.exe --launch 1.20.1 --account Steve        用指定账号启动
 * </pre>
 */
public final class Cli {

    private Cli() {
    }

    public static boolean handles(String[] args) {
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            switch (arg) {
                case "--launch", "--install", "--dry-run", "--list", "--env",
                        "--accounts", "--login-offline", "--login-microsoft",
                        "--use-account", "--logout-account", "--refresh-account",
                        "--loader-versions", "--install-loader", "--loader-info",
                        "--update-check", "--update-apply", "--rollback-update" -> {
                    return true;
                }
                default -> {
                    // 继续找
                }
            }
        }
        return false;
    }

    public static int run(String[] args) {
        MirrorResolver mirrors = mirrorsFromArgs(args);
        if (has(args, "--env")) {
            System.out.println(LaunchService.describeEnvironment());
            System.out.println("数据目录: " + AppPaths.root());
            System.out.println("版本目录: " + AppPaths.versionsDir());
            System.out.println("资源目录: " + AppPaths.assetsDir());
            System.out.println("下载源: " + mirrors.describe());
            return 0;
        }
        if (has(args, "--list")) {
            listVersions();
            return 0;
        }
        if (has(args, "--accounts")) {
            return printAccounts();
        }
        if (has(args, "--login-offline")) {
            return loginOffline(args);
        }
        if (has(args, "--login-microsoft")) {
            return loginMicrosoft(args);
        }
        if (has(args, "--use-account")) {
            return useAccount(args);
        }
        if (has(args, "--logout-account")) {
            return logoutAccount(args);
        }
        if (has(args, "--refresh-account")) {
            return refreshAccount(args);
        }
        if (has(args, "--loader-versions")) {
            return loaderVersions(args, mirrors);
        }
        if (has(args, "--install-loader")) {
            return installLoader(args, mirrors);
        }
        if (has(args, "--loader-info")) {
            return loaderInfo(args);
        }
        if (has(args, "--update-check")) {
            return updateCheck(args);
        }
        if (has(args, "--update-apply")) {
            return updateApply(args, mirrors);
        }
        if (has(args, "--rollback-update")) {
            return rollbackUpdate();
        }

        boolean dryRun = has(args, "--dry-run");
        boolean installOnly = has(args, "--install");
        String versionId = firstValue(args, "--launch", "--dry-run", "--install");
        if (versionId == null) {
            System.err.println("用法: --launch <版本> | --install <版本> | --dry-run <版本> | --list | --env");
            return 2;
        }

        AuthService auth = authService();
        Account account;
        try {
            account = resolveAccount(args, auth);
            if (account != null && account.isMicrosoft()) {
                account = auth.ensureFresh(account);
            }
        } catch (AuthException error) {
            System.err.println("账号处理失败: " + error.getMessage());
            if (error.requiresRelogin()) {
                System.err.println("请执行: QUTC.exe --login-microsoft");
            }
            return 5;
        } catch (IOException error) {
            System.err.println("账号文件读写失败: " + error.getMessage());
            return 5;
        }

        String username = account != null ? account.name() : valueOr(args, "--username", "Player");
        String uuid = account != null ? account.uuid() : valueOr(args, "--uuid", null);
        String userType = account != null ? account.userType() : "legacy";
        String xuid = account != null ? account.xuid() : null;
        String accessToken = "0";
        String clientId = account != null && account.isMicrosoft()
                ? ConfigManager.get().microsoftClientId
                : null;
        if (account != null && account.isMicrosoft()) {
            Optional<Credentials> credentials = auth.credentialsFor(account);
            if (credentials.isEmpty()) {
                System.err.println("账号 " + account.name() + " 的登录凭据不可用，请执行 --login-microsoft 重新登录");
                return 5;
            }
            accessToken = credentials.get().accessToken();
            System.out.println("账号: " + account.display() + "（" + account.uuid() + "）");
        }

        LaunchService.Options options = new LaunchService.Options(
                versionId,
                username,
                uuid,
                intValue(args, "--memory", 2048),
                intValue(args, "--width", 854),
                intValue(args, "--height", 480),
                has(args, "--fullscreen"),
                !has(args, "--no-isolated"),
                true,
                !has(args, "--no-assets"),
                intValue(args, "--concurrency", 8),
                valueOr(args, "--java", null) == null ? null : Path.of(valueOr(args, "--java", null)),
                List.of(),
                List.of(),
                !has(args, "--skip-hash-check"),
                accessToken,
                userType,
                xuid,
                clientId,
                com.qutc.launcher.core.external.ExternalVersions.find(versionId)
                        .map(entry -> Path.of(entry.root)).orElse(null));

        Downloader.Listener progress = consoleProgress();
        try {
            if (installOnly) {
                InstallEngine engine = new InstallEngine(AppPaths.versionsDir(), new Downloader(), mirrors);
                System.out.println("下载源: " + mirrors.describe());
                InstallEngine.Result result = engine.install(versionId,
                        new InstallEngine.Options(options.concurrency(), options.withAssets()), progress);
                System.out.println("安装完成: 库 " + result.libraries() + "，natives " + result.nativeFiles()
                        + "，资源 " + result.assetFiles() + "，失败 " + result.failures().size());
                if (!result.failures().isEmpty()) {
                    for (Downloader.Failure failure : result.failures()) {
                        System.out.println("  失败: " + failure.task().target() + " | " + failure.reason());
                    }
                    return 4;
                }
                return 0;
            }

            LaunchService.Prepared prepared = LaunchService.prepare(options, progress, mirrors);
            printPreflight(prepared.preflight());

            if (dryRun) {
                if (prepared.command() != null) {
                    System.out.println();
                    System.out.println("启动命令:");
                    System.out.println(prepared.command().display());
                }
                return prepared.preflight().ok() ? 0 : 3;
            }

            if (!prepared.preflight().ok()) {
                System.err.println("预检未通过，已终止启动：" + prepared.preflight().summary());
                return 3;
            }

            LaunchCommand command = prepared.command();
            System.out.println("主类: " + command.mainClass());
            System.out.println("classpath 条目: " + command.classpath().split(java.io.File.pathSeparator).length);

            boolean detach = has(args, "--no-wait");
            if (detach) {
                GameProcess detachedProcess = LaunchService.start(prepared, null, true);
                System.out.println("已分离启动，pid=" + detachedProcess.pid());
                System.out.println("游戏日志: " + detachedProcess.logFile());
                return 0;
            }

            System.out.println("启动中……（Ctrl+C 结束游戏）");
            GameProcess process = LaunchService.start(prepared, line -> System.out.println(line));
            process.addListener(new GameProcess.Listener() {
                @Override
                public void onExit(int exitCode, long elapsedMillis) {
                    System.out.println("游戏退出，退出码 " + exitCode + "，运行 " + elapsedMillis / 1000 + " 秒");
                }
            });
            int code = process.waitFor();
            System.out.println("游戏日志: " + process.logFile());
            return code;
        } catch (IOException error) {
            Log.error("Cli", "启动失败: " + error.getMessage(), error);
            System.err.println("启动失败: " + error.getMessage());
            return 4;
        }
    }

    // ---- 账号子命令 ----

    private static AuthService authService() {
        return AuthService.defaults();
    }

    private static int printAccounts() {
        AuthService auth = authService();
        System.out.println("账号存储: " + auth.store().file());
        String mode;
        if (!auth.microsoftAvailable()) {
            mode = "不可用";
        } else if (auth.usesLegacyFlow()) {
            mode = "内置客户端（无需配置，浏览器登录后粘贴回调地址）";
        } else {
            mode = "自建 Azure 应用（Client ID " + auth.microsoftClientId() + "，自动回调）";
        }
        System.out.println("微软登录: " + mode);
        System.out.println("账号列表（* 为当前账号）:");
        System.out.print(auth.store().describeAll());
        return 0;
    }

    private static int loginOffline(String[] args) {
        String name = firstValue(args, "--login-offline");
        if (name == null) {
            System.err.println("用法: --login-offline <用户名>");
            return 2;
        }
        try {
            Account account = authService().loginOffline(name).account();
            System.out.println("已创建离线账号: " + account.display());
            System.out.println("UUID: " + account.uuid());
            return 0;
        } catch (IllegalArgumentException error) {
            System.err.println("用户名不合法: " + error.getMessage());
            return 2;
        } catch (IOException error) {
            System.err.println("写入账号文件失败: " + error.getMessage());
            return 5;
        }
    }

    private static int loginMicrosoft(String[] args) {
        String clientId = valueOr(args, "--client-id", null);
        if (clientId != null && !clientId.isBlank()) {
            ConfigManager.update(config -> config.microsoftClientId = clientId.trim());
            System.out.println("已保存微软 Client ID: " + clientId.trim() + "（不需要 Azure 注册时可留空，默认用内置客户端）");
        }
        AuthService auth = authService();
        if (!auth.microsoftAvailable()) {
            System.err.println("微软登录不可用：请检查 launcher.json 的 microsoftClientId");
            return 2;
        }
        try {
            AuthService.LoginResult result = auth.usesLegacyFlow()
                    ? loginLegacyInteractive(auth)
                    : auth.loginMicrosoft(Duration.ofMinutes(5));
            System.out.println("登录成功: " + result.account().display());
            System.out.println("UUID: " + result.account().uuid());
            System.out.println("令牌有效至: " + result.account().tokenExpiresAt());
            if (result.account().skinUrl() != null) {
                System.out.println("皮肤: " + result.account().skinUrl());
            }
            return 0;
        } catch (AuthException error) {
            System.err.println("微软登录失败: " + error.getMessage());
            if (error.requiresRelogin()) {
                System.err.println("提示：重新执行 --login-microsoft 完成一次浏览器登录");
            }
            return 5;
        } catch (IOException error) {
            System.err.println("账号文件读写失败: " + error.getMessage());
            return 5;
        }
    }

    /**
     * 旧版内置客户端登录：打开微软登录页，等用户把浏览器地址栏的回调地址粘回来。
     *
     * <p>对应交接包 Electron 版的 {@code core/auth/microsoft.js} 流程
     * （client_id 00000000402b5328 + redirect login.live.com/oauth20_desktop.srf）。
     */
    private static AuthService.LoginResult loginLegacyInteractive(AuthService auth)
            throws AuthException, IOException {
        java.net.URI uri = auth.legacyAuthorizeUri();
        System.out.println("请在弹出的浏览器里登录微软账号（没有弹出就手动复制下面的地址）：");
        System.out.println(uri);
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(uri);
            }
        } catch (Throwable t) {
            Log.warn("Cli", "无法自动打开浏览器: " + t.getClass().getSimpleName());
        }
        System.out.println();
        System.out.println("登录成功后浏览器会跳到 https://login.live.com/oauth20_desktop.srf?code=... 页面。");
        System.out.println("请把地址栏里的完整地址粘贴到这里并回车：");
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null || line.isBlank()) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR, "没有读到回调地址");
        }
        return auth.loginMicrosoftWithPastedUrl(line);
    }

    private static int useAccount(String[] args) {
        String key = firstValue(args, "--use-account");
        AuthService auth = authService();
        Optional<Account> account = key == null ? auth.current() : auth.store().find(key);
        if (account.isEmpty()) {
            System.err.println(key == null ? "当前没有账号" : "找不到账号: " + key);
            return 2;
        }
        try {
            auth.setCurrent(account.get());
            System.out.println("当前账号: " + account.get().display());
            return 0;
        } catch (IOException error) {
            System.err.println("写入账号文件失败: " + error.getMessage());
            return 5;
        }
    }

    private static int logoutAccount(String[] args) {
        String key = firstValue(args, "--logout-account");
        AuthService auth = authService();
        Optional<Account> account = key == null ? auth.current() : auth.store().find(key);
        if (account.isEmpty()) {
            System.err.println(key == null ? "当前没有账号" : "找不到账号: " + key);
            return 2;
        }
        try {
            auth.logout(account.get());
            System.out.println("已移除账号: " + account.get().display());
            auth.current().ifPresentOrElse(
                    remaining -> System.out.println("当前账号切换为: " + remaining.display()),
                    () -> System.out.println("已没有剩余账号"));
            return 0;
        } catch (IOException error) {
            System.err.println("写入账号文件失败: " + error.getMessage());
            return 5;
        }
    }

    private static int refreshAccount(String[] args) {
        String key = firstValue(args, "--refresh-account");
        AuthService auth = authService();
        Optional<Account> account = key == null ? auth.current() : auth.store().find(key);
        if (account.isEmpty()) {
            System.err.println(key == null ? "当前没有账号" : "找不到账号: " + key);
            return 2;
        }
        Account target = account.get();
        if (!target.isMicrosoft()) {
            System.out.println("离线账号无需续期: " + target.display());
            return 0;
        }
        try {
            Account refreshed = auth.refresh(target);
            System.out.println("续期成功: " + refreshed.display());
            System.out.println("令牌有效至: " + refreshed.tokenExpiresAt());
            return 0;
        } catch (AuthException error) {
            System.err.println("续期失败: " + error.getMessage());
            if (error.requiresRelogin()) {
                System.err.println("提示：请执行 --login-microsoft 重新登录");
            }
            return 5;
        } catch (IOException error) {
            System.err.println("账号文件读写失败: " + error.getMessage());
            return 5;
        }
    }

    /**
     * 启动时的账号选择：
     * <ul>
     *   <li>显式 {@code --account} → 必须命中存储里的账号；</li>
     *   <li>显式 {@code --username} → 临时离线身份（不落盘，兼容旧用法）；</li>
     *   <li>都没给 → 用当前账号；没有账号则返回 null（调用方回落 Player）。</li>
     * </ul>
     */
    private static Account resolveAccount(String[] args, AuthService auth) {
        String explicit = firstValue(args, "--account");
        if (explicit != null) {
            return auth.store().find(explicit).orElseThrow(
                    () -> new IllegalStateException("找不到账号: " + explicit));
        }
        if (firstValue(args, "--username") != null) {
            return null;
        }
        return auth.current().orElse(null);
    }

    // ---- 加载器子命令 ----

    /** {@code --loader-versions <kind>:<gameVersion>} */
    private static int loaderVersions(String[] args, MirrorResolver mirrors) {
        String spec = firstValue(args, "--loader-versions");
        String[] parts = spec == null ? new String[0] : spec.split(":", 3);
        if (parts.length < 2) {
            System.err.println("用法: --loader-versions <forge|neoforge|fabric|quilt>:<游戏版本>");
            return 2;
        }
        LoaderKind kind;
        try {
            kind = LoaderKind.parse(parts[0]);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            return 2;
        }
        String game = parts[1].trim();
        LoaderInstaller installer = new LoaderInstaller(mirrors);
        var versions = installer.catalog().list(kind, game);
        if (versions.isEmpty()) {
            System.out.println("没有查到 " + kind.displayName() + " 对应 Minecraft " + game + " 的版本"
                    + (kind == LoaderKind.OPTIFINE ? "（请到 optifine.net 手动下载安装器）" : ""));
            return 0;
        }
        System.out.println(kind.displayName() + " 可用版本（Minecraft " + game + "，共 " + versions.size() + " 个）:");
        int shown = 0;
        for (var version : versions) {
            if (shown++ >= 20) {
                System.out.println("  ……");
                break;
            }
            System.out.printf("  %-28s %s → %s%n", version.loaderVersion(),
                    Boolean.TRUE.equals(version.stable()) ? "[稳定]" : "      ", version.versionId());
        }
        return 0;
    }

    /** {@code --install-loader <kind>:<gameVersion>[:<loaderVersion>]} */
    private static int installLoader(String[] args, MirrorResolver mirrors) {
        String spec = firstValue(args, "--install-loader");
        String[] parts = spec == null ? new String[0] : spec.split(":", 3);
        if (parts.length < 2) {
            System.err.println("用法: --install-loader <forge|neoforge|fabric|quilt>:<游戏版本>[:<加载器版本>]");
            return 2;
        }
        LoaderKind kind;
        try {
            kind = LoaderKind.parse(parts[0]);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            return 2;
        }
        String game = parts[1].trim();
        String loaderVersion = parts.length >= 3 ? parts[2].trim() : null;
        try {
            LoaderInstaller.Outcome outcome = new LoaderInstaller(mirrors)
                    .install(kind, game, loaderVersion, consoleProgress());
            System.out.println("安装完成: " + outcome.kind().displayName() + ' '
                    + outcome.loaderVersion() + " → " + outcome.gameVersion());
            System.out.println("版本 id: " + outcome.versionId());
            System.out.println("库: " + outcome.libraries() + "，natives: " + outcome.nativeFiles()
                    + (outcome.mavenFiles() > 0 ? "，安装器 maven: " + outcome.mavenFiles() : ""));
            System.out.println("启动: QUTC.exe --launch " + outcome.versionId());
            return 0;
        } catch (IOException error) {
            Log.error("Cli", "加载器安装失败: " + error.getMessage(), error);
            System.err.println("加载器安装失败: " + error.getMessage());
            return 6;
        }
    }

    /** {@code --loader-info <versionId>}：识别原版 / 加载器 / OptiFine 混搭。 */
    private static int loaderInfo(String[] args) {
        String versionId = firstValue(args, "--loader-info");
        if (versionId == null) {
            System.err.println("用法: --loader-info <版本 id>");
            return 2;
        }
        var detection = LoaderDetector.detect(versionId);
        System.out.println(detection.summary());
        for (String note : detection.notes()) {
            System.out.println("  - " + note);
        }
        if (!detection.hasLoader() && !detection.optifine()) {
            System.out.println("  - 未识别到加载器，按原版启动");
        }
        return 0;
    }

    private static void listVersions() {
        Path versionsDir = AppPaths.versionsDir();
        if (!Files.isDirectory(versionsDir)) {
            System.out.println("暂无已安装版本（" + versionsDir + "）");
            return;
        }
        System.out.println("已安装版本:");
        try (var stream = Files.list(versionsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                boolean json = Files.isRegularFile(dir.resolve(id + ".json"));
                boolean marker = Files.isRegularFile(dir.resolve(InstallEngine.MARKER_NAME));
                boolean jar = Files.isRegularFile(dir.resolve(id + ".jar"));
                System.out.printf("  %-24s json=%s jar=%s installed=%s%n", id, yes(json), yes(jar), yes(marker));
            }
        } catch (IOException error) {
            System.err.println("读取版本目录失败: " + error.getMessage());
        }
    }

    private static void printPreflight(Preflight.Report report) {
        System.out.println("七级检查（版本 " + report.versionId() + "）:");
        for (Preflight.Check check : report.checks()) {
            System.out.println("  " + check.display());
        }
        for (String warning : report.warnings()) {
            System.out.println("  [警告] " + warning);
        }
        System.out.println(report.ok() ? "结论: 通过" : "结论: " + report.summary());
    }

    private static Downloader.Listener consoleProgress() {
        AtomicLong lastPrint = new AtomicLong();
        return progress -> {
            long now = System.currentTimeMillis();
            if (now - lastPrint.get() < 2000 && progress.doneFiles() < progress.totalFiles()) {
                return;
            }
            lastPrint.set(now);
            System.out.printf("  下载 %d/%d 文件  %d%%  %d MB / %d MB  %d KB/s  %s%n",
                    progress.doneFiles(), progress.totalFiles(), progress.percent(),
                    progress.doneBytes() / 1024 / 1024, progress.totalBytes() / 1024 / 1024,
                    progress.bytesPerSecond() / 1024, progress.currentFile());
        };
    }

    private static boolean has(String[] args, String key) {
        for (String arg : args) {
            if (key.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String valueOr(String[] args, String key, String fallback) {
        String value = firstValue(args, key);
        return value == null ? fallback : value;
    }

    private static int intValue(String[] args, String key, int fallback) {
        String value = firstValue(args, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 任一 key 后面跟的第一个非选项值。 */
    private static String firstValue(String[] args, String... keys) {
        for (int i = 0; i < args.length; i++) {
            for (String key : keys) {
                if (key.equals(args[i]) && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    return args[i + 1];
                }
            }
        }
        return null;
    }

    /** {@code --update-check <url>}：只检查不下载。 */
    private static int updateCheck(String[] args) {
        String base = valueOr(args, "--update-check", null);
        if (base == null || base.isBlank()) {
            base = ConfigManager.get().updateBaseUrl;
        }
        if (base == null || base.isBlank()) {
            System.err.println("未配置更新源：用 --update-check <url> 或在设置里填「更新源」");
            return 2;
        }
        try {
            UpdateService.CheckResult result = UpdateService.check(base);
            System.out.println(result.message());
            if (result.available() && result.manifest() != null) {
                if (result.manifest().notes != null) {
                    System.out.println("更新说明: " + result.manifest().notes);
                }
                for (UpdateService.FileEntry entry : result.manifest().files) {
                    System.out.println("  " + entry.path + "  " + entry.size + " bytes  sha256="
                            + (entry.sha256 == null ? "(无)" : entry.sha256));
                }
            }
            return 0;
        } catch (IOException error) {
            System.err.println("检查更新失败: " + error.getMessage());
            return 1;
        }
    }

    /** {@code --update-apply <url>}：下载并准备好替换文件（退出后由脚本替换）。 */
    private static int updateApply(String[] args, MirrorResolver mirrors) {
        String base = valueOr(args, "--update-apply", null);
        if (base == null || base.isBlank()) {
            base = ConfigManager.get().updateBaseUrl;
        }
        if (base == null || base.isBlank()) {
            System.err.println("未配置更新源：用 --update-apply <url> 或在设置里填「更新源」");
            return 2;
        }
        try {
            UpdateService.CheckResult result = UpdateService.check(base);
            if (!result.available()) {
                System.out.println(result.message());
                return 0;
            }
            Downloader downloader = new Downloader();
            java.nio.file.Path jar = UpdateService.download(downloader, mirrors, result.manifest());
            UpdateService.FileEntry entry = UpdateService.mainJar(result.manifest());
            if (!UpdateService.verify(jar, entry)) {
                System.err.println("下载文件 sha256 与 files.json 不一致");
                return 1;
            }
            java.nio.file.Path pending = UpdateService.prepare(jar, result.manifest());
            System.out.println("已下载 " + jar);
            System.out.println("已写入 " + pending + "，重启启动器后由 update/apply-update.ps1 完成替换");
            return 0;
        } catch (IOException error) {
            System.err.println("应用更新失败: " + error.getMessage());
            return 1;
        }
    }

    /** {@code --rollback-update}：用最近一次备份恢复 jar。 */
    private static int rollbackUpdate() {
        try {
            java.nio.file.Path restored = UpdateService.rollback();
            if (restored == null) {
                System.out.println("没有可用的备份（update/backup/ 为空）");
                return 0;
            }
            System.out.println("已回滚: " + restored);
            return 0;
        } catch (IOException error) {
            System.err.println("回滚失败: " + error.getMessage());
            return 1;
        }
    }

    private static String yes(boolean value) {
        return value ? "√" : "×";
    }

    /**
     * 解析下载源参数：
     * <pre>
     *   --source official|bmclapi|mcbbs|custom
     *   --source-base https://example.com      （隐含 custom）
     * </pre>
     * 不带参数时沿用全局配置（默认 BMCLAPI → mcbbs → 官方）。
     */
    static MirrorResolver mirrorsFromArgs(String[] args) {
        String raw = valueOr(args, "--source", null);
        String base = valueOr(args, "--source-base", null);
        if (raw == null && base == null) {
            return MirrorResolver.of(null);
        }
        LauncherConfig.DownloadSource source = base != null
                ? LauncherConfig.DownloadSource.CUSTOM
                : LauncherConfig.DownloadSource.BMCLAPI;
        if (raw != null) {
            switch (raw.trim().toLowerCase()) {
                case "official", "mojang" -> source = LauncherConfig.DownloadSource.OFFICIAL;
                case "bmclapi" -> source = LauncherConfig.DownloadSource.BMCLAPI;
                case "mcbbs" -> source = LauncherConfig.DownloadSource.MCBBS;
                case "custom" -> source = LauncherConfig.DownloadSource.CUSTOM;
                default -> Log.warn("Cli", "未知下载源 " + raw + "，按全局配置处理");
            }
        }
        return new MirrorResolver(source, base);
    }

    /** 供 SelfTest 复用的命令摘要。 */
    public static String summary() {
        return "CLI 支持 --env / --list / --install / --dry-run / --launch / --source";
    }
}
