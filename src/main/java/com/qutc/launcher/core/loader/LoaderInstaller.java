package com.qutc.launcher.core.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.core.install.InstallEngine;
import com.qutc.launcher.core.java.JavaMatrix;
import com.qutc.launcher.core.java.JavaProbe;
import com.qutc.launcher.core.java.JavaRuntime;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.Http;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 加载器安装器：把「原版 + 加载器」装成一个可直接启动的版本。
 *
 * <p>两条安装路线：
 * <ul>
 *   <li>Fabric / Quilt：拉 meta API 的 profile JSON（自带 {@code inheritsFrom} 与 libraries），
 *       直接写成版本 JSON；</li>
 *   <li>Forge / NeoForge：下载官方 installer jar，无头解包出版本 JSON 与内置 maven 文件
 *       （见 {@link ForgeInstaller}）。</li>
 * </ul>
 *
 * <p>两条路线最后都交给 {@link InstallEngine} 补全依赖库 / natives / 资源，
 * 从而与总纲「全部启动参数、natives 逻辑不允许简化」保持同一套下载与校验路径。
 */
public final class LoaderInstaller {

    public record Outcome(String versionId,
                          LoaderKind kind,
                          String gameVersion,
                          String loaderVersion,
                          int mavenFiles,
                          int libraries,
                          int nativeFiles) {
    }

    private final Path versionsDir;
    private final Path librariesDir;
    private final Downloader downloader;
    private final MirrorResolver mirrors;
    private final LoaderCatalog catalog;

    public LoaderInstaller(MirrorResolver mirrors) {
        this(AppPaths.versionsDir(), AppPaths.librariesDir(), new Downloader(),
                mirrors == null ? MirrorResolver.of(null) : mirrors);
    }

    public static LoaderInstaller defaults() {
        return new LoaderInstaller(MirrorResolver.of(null));
    }

    public LoaderInstaller(Path versionsDir, Path librariesDir, Downloader downloader, MirrorResolver mirrors) {
        this.versionsDir = versionsDir;
        this.librariesDir = librariesDir;
        this.downloader = downloader == null ? new Downloader() : downloader;
        this.mirrors = mirrors == null ? MirrorResolver.officialOnly() : mirrors;
        this.catalog = new LoaderCatalog(this.mirrors);
    }

    public LoaderCatalog catalog() {
        return catalog;
    }

    /**
     * 安装加载器。
     *
     * @param loaderVersion 为空表示取最新稳定版
     * @return 安装结果（versionId 可直接交给 --launch）
     */
    public Outcome install(LoaderKind kind, String gameVersion, String loaderVersion,
                           Downloader.Listener listener) throws IOException {
        if (kind == LoaderKind.OPTIFINE) {
            throw new IOException("OptiFine 没有公开的无头安装入口：请从 optifine.net 手动下载安装器安装，"
                    + "QUTC 会自动识别并支持它与 Forge/Fabric 混搭启动");
        }
        if (gameVersion == null || gameVersion.isBlank()) {
            throw new IOException("必须指定原版游戏版本");
        }
        String resolvedLoader = loaderVersion;
        if (resolvedLoader == null || resolvedLoader.isBlank()) {
            LoaderVersion latest = catalog.latestStable(kind, gameVersion);
            if (latest == null) {
                throw new IOException("找不到 " + kind.displayName() + " 对应 Minecraft " + gameVersion + " 的可用版本");
            }
            resolvedLoader = latest.loaderVersion();
            Log.info("Loader", kind.displayName() + " 未指定版本，选用最新稳定版 " + resolvedLoader);
        }

        ensureVanilla(gameVersion, listener);

        String versionId;
        int mavenFiles = 0;
        if (kind.viaMetaProfile()) {
            versionId = installMetaProfile(kind, gameVersion, resolvedLoader);
        } else {
            Path installer = downloadInstaller(kind, gameVersion, resolvedLoader, listener);
            Extracted extracted = installForgeFamily(kind, gameVersion, resolvedLoader, installer, listener);
            versionId = extracted.versionId();
            mavenFiles = extracted.mavenFiles();
        }

        InstallEngine.Result installed = new InstallEngine(versionsDir, downloader, mirrors)
                .install(versionId, InstallEngine.Options.defaults(), listener);
        if (!installed.failures().isEmpty()) {
            Log.warn("Loader", "加载器依赖补全有 " + installed.failures().size() + " 项失败，"
                    + "首次启动可能仍需重试");
        }
        Log.info("Loader", kind.displayName() + ' ' + resolvedLoader + " 安装完成 → " + versionId
                + "（库 " + installed.libraries() + "，natives " + installed.nativeFiles() + "）");
        return new Outcome(versionId, kind, gameVersion, resolvedLoader, mavenFiles,
                installed.libraries(), installed.nativeFiles());
    }

    private record Extracted(String versionId, int mavenFiles) {
    }

    /**
     * Forge / NeoForge：优先无头执行官方安装器（会跑 installertools 处理器，
     * 生成 client-srg / client-extra / forge-*-client 等客户端必需产物），
     * 安装器不可用时退回纯解包。
     */
    private Extracted installForgeFamily(LoaderKind kind, String game, String loader, Path installer,
                                         Downloader.Listener listener) throws IOException {
        String stagingName = kind == LoaderKind.FORGE
                ? "forge-" + game + '-' + loader
                : "neoforge-" + game + '-' + loader;
        Path staging = AppPaths.cacheDir().resolve("loader-staging").resolve(stagingName);

        // 1. 先解包出版本 JSON（不跑处理器），得到版本 id 与依赖清单
        ForgeInstaller.Result extracted = ForgeInstaller.extract(installer, versionsDir, librariesDir);

        // 2. 用镜像链（BMCLAPI → mcbbs → 官方）把依赖库先补齐；安装器随后能直接复用，
        //    避免它硬连官方源超时失败（真机上就栽在 cpw.mods:securejarhandler 下载超时）
        try {
            new InstallEngine(versionsDir, downloader, mirrors)
                    .install(extracted.versionId(), InstallEngine.Options.librariesOnly(), null);
        } catch (IOException error) {
            Log.warn("Loader", "预装依赖库失败（继续尝试安装器）: " + error.getMessage());
        }

        // 3. 安装器自己要用的 installertools/neoform/universal 也经镜像链预置，
        //    否则 NeoForge 会因 maven.neoforged.net 不可达而失败
        Path stagingLibraries = staging.resolve("libraries");
        try {
            FsUtils.ensureDir(stagingLibraries);
            ForgeInstaller.seedFromInstallProfile(installer, stagingLibraries, downloader, mirrors, listener);
        } catch (IOException error) {
            Log.warn("Loader", "预置安装器依赖失败: " + error.getMessage());
        }

        // 4. 无头执行官方安装器，跑 installertools 生成客户端必需产物
        try {
            Path javaExecutable = pickInstallerJava(game);
            ForgeInstaller.HeadlessResult result = ForgeInstaller.installHeadless(
                    installer, staging, javaExecutable, versionsDir, librariesDir, librariesDir,
                    game,
                    line -> {
                        if (line.contains("Downloading") || line.contains("Installing")
                                || line.contains("Error") || line.contains("Exception")) {
                            Log.debug("Loader", line);
                        }
                    },
                    900);
            String installedId = result.versionId();
            String expected = game + "-forge-" + loader;
            if (!expected.equals(installedId)) {
                Log.info("Loader", "安装器写出的版本 id 是 " + installedId + "（预期 " + expected + "）");
            }
            return new Extracted(installedId, extracted.mavenFiles());
        } catch (IOException headlessError) {
            Log.warn("Loader", "无头安装失败，退回纯解包结果（现代 Forge 可能仍缺 installertools 产物）: "
                    + headlessError.getMessage());
            return new Extracted(extracted.versionId(), extracted.mavenFiles());
        }
    }

    private static Path pickInstallerJava(String game) throws IOException {
        List<JavaRuntime> runtimes = JavaProbe.discover();
        if (runtimes.isEmpty()) {
            throw new IOException("本机没有可用 Java，无法执行加载器安装器");
        }
        JavaRuntime runtime = JavaProbe.best(runtimes, JavaMatrix.recommendMajor(game));
        if (runtime == null) {
            runtime = JavaProbe.best(runtimes, 8);
        }
        if (runtime == null) {
            runtime = runtimes.get(0);
        }
        return runtime.executable();
    }

    /** 原版没装就先装（加载器版本依赖它做 inheritsFrom）。 */
    private void ensureVanilla(String gameVersion, Downloader.Listener listener) throws IOException {        InstallEngine engine = new InstallEngine(versionsDir, downloader, mirrors);
        boolean jsonPresent = Files.isRegularFile(AppPaths.versionJson(gameVersion));
        boolean installed = InstallEngine.readMarker(gameVersion) != null;
        if (jsonPresent && installed) {
            return;
        }
        Log.info("Loader", "先安装原版 " + gameVersion + " 作为加载器底座");
        engine.install(gameVersion, InstallEngine.Options.defaults(), listener);
    }

    private String installMetaProfile(LoaderKind kind, String game, String loader) throws IOException {
        String base = kind == LoaderKind.FABRIC ? LoaderCatalog.FABRIC_META : LoaderCatalog.QUILT_META;
        String url = base + game + '/' + loader + "/profile/json";
        String json = Http.getString(mirrors.candidates(url), 30, 60);
        JsonNode node = JsonUtils.parse(json);
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            id = kind.name().toLowerCase(Locale.ROOT) + "-loader-" + loader + '-' + game;
        }
        Path target = versionsDir.resolve(id).resolve(id + ".json");
        FsUtils.ensureDir(target.getParent());
        Files.writeString(target, json, StandardCharsets.UTF_8);
        Log.info("Loader", "已写入 " + kind.displayName() + " profile: " + id);
        return id;
    }

    private Path downloadInstaller(LoaderKind kind, String game, String loader,
                                   Downloader.Listener listener) throws IOException {
        String coordinate = kind == LoaderKind.FORGE ? game + '-' + loader : loader;
        String url;
        String fileName;
        if (kind == LoaderKind.FORGE) {
            url = LoaderCatalog.FORGE_MAVEN + coordinate + "/forge-" + coordinate + "-installer.jar";
            fileName = "forge-" + coordinate + "-installer.jar";
        } else if ("1.20.1".equals(game)) {
            String legacy = game + '-' + loader;
            url = LoaderCatalog.NEOFORGE_LEGACY_MAVEN + legacy + "/forge-" + legacy + "-installer.jar";
            fileName = "neoforge-" + legacy + "-installer.jar";
        } else {
            url = LoaderCatalog.NEOFORGE_MAVEN + coordinate + "/neoforge-" + coordinate + "-installer.jar";
            fileName = "neoforge-" + coordinate + "-installer.jar";
        }
        Path target = AppPaths.cacheDir().resolve("installers").resolve(fileName);
        FsUtils.ensureDir(target.getParent());
        Log.info("Loader", "下载安装器: " + url);
        List<Downloader.Failure> failures = downloader.download(
                List.of(Downloader.FileTask.mirrors(mirrors.candidates(url), target, null, -1)), 1, listener);
        if (!failures.isEmpty()) {
            throw new IOException("加载器安装器下载失败: " + failures.get(0).reason() + " @ " + failures.get(0).url());
        }
        return target;
    }
}
