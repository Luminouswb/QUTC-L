package com.qutc.launcher.core.install;

import com.qutc.launcher.asset.AssetIndex;
import com.qutc.launcher.asset.AssetLayout;
import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.core.version.Library;
import com.qutc.launcher.core.version.LibraryCollector;
import com.qutc.launcher.core.version.Rules;
import com.qutc.launcher.core.version.VersionManifest;
import com.qutc.launcher.core.version.VersionManifestService;
import com.qutc.launcher.core.version.VersionMeta;
import com.qutc.launcher.core.version.VersionResolver;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本安装引擎：把「版本 JSON + 客户端 jar + 依赖库 + natives + 资源」铺到磁盘。
 *
 * <p>安装顺序刻意固定为：
 * <ol>
 *   <li>版本 JSON（缺失才下）</li>
 *   <li>客户端 jar、依赖库、natives 包、资源索引、日志配置（第一批并发下载）</li>
 *   <li>解析资源索引后，下载散列资源（第二批）</li>
 *   <li>解压 natives</li>
 *   <li>老版本 virtual / map_to_resources 布局</li>
 *   <li>写 {@code .qutc-installed.json} 标记</li>
 * </ol>
 */
public final class InstallEngine {

    /** 安装完成标记文件名（与总纲一致）。 */
    public static final String MARKER_NAME = ".qutc-installed.json";

    public record Options(int concurrency, boolean withAssets) {
        public static Options defaults() {
            return new Options(8, true);
        }

        public static Options librariesOnly() {
            return new Options(4, false);
        }
    }

    public record Result(String versionId,
                         Path clientJar,
                         Path nativesDir,
                         Path assetRoot,
                         int libraries,
                         int nativesJars,
                         int nativeFiles,
                         int assetFiles,
                         long downloadedBytes,
                         List<Downloader.Failure> failures,
                         Path marker) {

        public boolean isClean() {
            return failures.isEmpty();
        }
    }

    /** 标记文件内容；阶段 10 的完整性诊断与阶段 12 的自更新都会读它。 */
    public static final class Marker {
        public String versionId;
        public String installedAt;
        public String clientJar;
        public int libraries;
        public int nativeFiles;
        public int assets;
        public String assetIndex;
    }

    private final Downloader downloader;
    private final VersionManifestService manifests;
    private final VersionResolver resolver;
    private final MirrorResolver mirrors;

    public InstallEngine() {
        this(AppPaths.versionsDir());
    }

    public InstallEngine(Path versionsDir) {
        this(versionsDir, new Downloader(), MirrorResolver.of(null));
    }

    public InstallEngine(Path versionsDir, Downloader downloader, MirrorResolver mirrors) {
        this.mirrors = mirrors == null ? MirrorResolver.officialOnly() : mirrors;
        this.downloader = downloader;
        this.manifests = new VersionManifestService(AppPaths.cacheDir().resolve("version_manifest_v2.json"),
                this.mirrors);
        this.resolver = new VersionResolver(versionsDir);
    }

    public InstallEngine(Path versionsDir, Downloader downloader, VersionManifestService manifests,
                         MirrorResolver mirrors) {
        this.downloader = downloader;
        this.manifests = manifests;
        this.resolver = new VersionResolver(versionsDir);
        this.mirrors = mirrors == null ? MirrorResolver.officialOnly() : mirrors;
    }

    public MirrorResolver mirrors() {
        return mirrors;
    }

    /** 把一个官方 URL 展开成「多源候选」的下载任务。 */
    private Downloader.FileTask task(String officialUrl, Path target, String sha1, long size) {
        return Downloader.FileTask.mirrors(mirrors.candidates(officialUrl), target, sha1, size);
    }

    /** 版本 JSON 是否已在本地且能解析。 */
    public boolean isVersionJsonPresent(String versionId) {
        Path json = AppPaths.versionJson(versionId);
        if (!Files.isRegularFile(json)) {
            return false;
        }
        try {
            VersionMeta meta = JsonUtils.read(json, VersionMeta.class, null);
            return meta != null && meta.id != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 下载版本 JSON（带 SHA-1 校验）。 */
    public void ensureVersionJson(String versionId, boolean forceRefresh) throws IOException {
        Path target = AppPaths.versionJson(versionId);
        if (!forceRefresh && isVersionJsonPresent(versionId)) {
            return;
        }
        VersionManifest manifest = manifests.get(forceRefresh);
        VersionManifest.Entry entry = manifest.find(versionId);
        if (entry == null || entry.url == null) {
            throw new IOException("版本清单中找不到 " + versionId);
        }
        Log.info("Install", "下载版本 JSON: " + versionId + "（下载源：" + mirrors.describe() + "）");
        List<Downloader.Failure> failures = downloader.download(
                List.of(task(entry.url, target, entry.sha1, -1)), 1, null);
        if (!failures.isEmpty()) {
            throw new IOException("版本 JSON 下载失败: " + failures.get(0).reason());
        }
    }

    /**
     * 安装一个版本。
     *
     * @param listener 进度回调，可为 null
     */
    public Result install(String versionId, Options options, Downloader.Listener listener) throws IOException {
        long startedAt = System.currentTimeMillis();
        Options effective = options == null ? Options.defaults() : options;

        Path versionDir = AppPaths.versionDir(versionId);
        FsUtils.ensureDir(versionDir);

        ensureVersionJson(versionId, false);

        VersionMeta meta = resolver.resolve(versionId);
        Rules.Context context = Rules.Context.system();
        LibraryCollector.Selection selection = LibraryCollector.select(meta, context);

        Log.info("Install", "开始安装 " + versionId + "：classpath " + selection.classpath().size()
                + " 个库，natives " + selection.natives().size() + " 个包");

        Path librariesDir = AppPaths.librariesDir();
        List<Downloader.FileTask> firstBatch = new ArrayList<>();
        List<NativesExtractor.NativeJar> nativeJars = new ArrayList<>();

        // ---- 1. 客户端 jar ----
        Path clientJar = prepareClientJar(meta, firstBatch);

        // ---- 2. 依赖库 + natives 包 ----
        for (Library lib : selection.classpath()) {
            Library.Artifact artifact = lib.downloads == null ? null : lib.downloads.artifact;
            String relative = lib.resolvePath(artifact);
            if (relative == null) {
                Log.warn("Install", "无法推导库路径，跳过: " + lib.name);
                continue;
            }
            firstBatch.add(task(artifactUrl(lib, artifact, relative),
                    librariesDir.resolve(relative),
                    artifact == null ? null : artifact.sha1,
                    artifact == null ? -1 : artifact.size));
        }
        for (LibraryCollector.NativeLib nativeLib : selection.natives()) {
            Library.Artifact artifact = nativeLib.artifact();
            String relative = nativeLib.library().resolvePath(artifact);
            if (relative == null) {
                continue;
            }
            Path jar = librariesDir.resolve(relative);
            firstBatch.add(task(artifactUrl(nativeLib.library(), artifact, relative), jar,
                    artifact.sha1, artifact.size));
            nativeJars.add(NativesExtractor.NativeJar.of(nativeLib, jar));
        }

        // ---- 3. 资源索引与日志配置 ----
        Path assetsDir = AppPaths.assetsDir();
        AssetIndex assetIndex = null;
        if (effective.withAssets() && meta.assetIndex != null && meta.assetIndex.url != null) {
            Path indexFile = AppPaths.assetsIndexFile(meta.assetIndex.id);
            firstBatch.add(task(meta.assetIndex.url, indexFile,
                    meta.assetIndex.sha1, meta.assetIndex.size));
        }
        if (meta.logging != null && meta.logging.client != null && meta.logging.client.file != null
                && meta.logging.client.file.url != null) {
            VersionMeta.FileInfo file = meta.logging.client.file;
            Path target = assetsDir.resolve("log_configs").resolve(file.id);
            firstBatch.add(task(file.url, target, file.sha1, file.size));
        }

        // ---- 第一批下载 ----
        List<Downloader.Failure> failures = new ArrayList<>(downloader.download(firstBatch, effective.concurrency(), listener));

        // ---- 4. 散列资源 ----
        int assetFiles = 0;
        if (effective.withAssets() && meta.assetIndex != null && meta.assetIndex.id != null) {
            Path indexFile = AppPaths.assetsIndexFile(meta.assetIndex.id);
            if (Files.isRegularFile(indexFile)) {
                assetIndex = JsonUtils.read(indexFile, AssetIndex.class, null);
            }
            if (assetIndex != null && assetIndex.id == null) {
                // 现代索引文件里没有 id 字段，id 来自版本 JSON 与文件名
                assetIndex.id = meta.assetIndex.id;
            }
            if (assetIndex != null) {
                assetFiles = installAssets(assetsDir, assetIndex, effective.concurrency(), listener, failures);
            } else {
                Log.warn("Install", "资源索引无法解析，跳过资源下载: " + indexFile);
            }
        }

        // ---- 5. natives 解压 ----
        int nativeFiles = 0;
        Path nativesDir = AppPaths.nativesDir(versionId);
        if (!nativeJars.isEmpty()) {
            try {
                NativesExtractor.Result extracted = NativesExtractor.extract(nativesDir, nativeJars);
                nativeFiles = (int) NativesExtractor.countNativeFiles(nativesDir);
                Log.debug("Install", "natives: " + extracted.jars() + " 包 / " + nativeFiles + " 文件");
            } catch (IOException error) {
                failures.add(new Downloader.Failure(
                        Downloader.FileTask.mirrors(List.of("natives:" + nativesDir), nativesDir, null, -1),
                        "natives:" + nativesDir,
                        error.getMessage()));
            }
        }

        // ---- 6. 老版本布局 ----
        Path assetRoot = assetsDir;
        if (assetIndex != null) {
            Path virtualDir = "legacy".equals(assetIndex.id)
                    ? AppPaths.assetsVirtualLegacyDir()
                    : assetsDir.resolve("virtual").resolve(assetIndex.id);
            if (assetIndex.virtual) {
                try {
                    AssetLayout.applyVirtual(assetsDir, assetIndex, virtualDir);
                } catch (IOException error) {
                    Log.warn("Install", "virtual 布局铺设失败", error);
                }
                assetRoot = virtualDir;
            } else {
                assetRoot = assetsDir;
            }
        }

        // ---- 7. 标记 ----
        Path marker = AppPaths.installedMarker(versionId);
        Marker record = new Marker();
        record.versionId = versionId;
        record.installedAt = Instant.now().toString();
        record.clientJar = clientJar == null ? null : clientJar.toString();
        record.libraries = selection.classpath().size();
        record.nativeFiles = nativeFiles;
        record.assets = assetFiles;
        record.assetIndex = meta.assetIndex == null ? null : meta.assetIndex.id;
        try {
            JsonUtils.write(marker, record);
        } catch (IOException error) {
            Log.warn("Install", "写入安装标记失败", error);
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        Log.info("Install", "安装完成 " + versionId + "：库 " + selection.classpath().size()
                + "、natives " + nativeFiles + "、资源 " + assetFiles
                + "，耗时 " + elapsed / 1000 + "s，失败 " + failures.size());

        return new Result(versionId, clientJar, nativesDir, assetRoot,
                selection.classpath().size(), nativeJars.size(), nativeFiles, assetFiles,
                0L, List.copyOf(failures), marker);
    }

    private Path prepareClientJar(VersionMeta meta, List<Downloader.FileTask> tasks) {
        String jarId = meta.jar != null && !meta.jar.isBlank() ? meta.jar : meta.id;
        Path target = AppPaths.versionsDir().resolve(jarId).resolve(jarId + ".jar");
        Library.Artifact client = meta.downloads == null ? null : meta.downloads.get("client");
        if (client != null && client.url != null) {
            tasks.add(task(client.url, target, client.sha1, client.size));
            return target;
        }
        if (Files.isRegularFile(target)) {
            return target;
        }
        Log.warn("Install", "版本 " + meta.id + " 未声明 client 下载地址，且本地无 " + target.getFileName());
        return null;
    }

    private int installAssets(Path assetsDir, AssetIndex index, int concurrency,
                              Downloader.Listener listener, List<Downloader.Failure> failures) {
        Map<String, AssetIndex.AssetObject> objects = index.safeObjects();
        if (objects.isEmpty()) {
            return 0;
        }
        Path objectsDir = assetsDir.resolve("objects");
        List<Downloader.FileTask> tasks = new ArrayList<>(objects.size());
        for (Map.Entry<String, AssetIndex.AssetObject> entry : objects.entrySet()) {
            AssetIndex.AssetObject object = entry.getValue();
            String path = object.objectPath();
            if (path == null) {
                continue;
            }
            tasks.add(task("https://resources.download.minecraft.net/" + path,
                    objectsDir.resolve(path), object.hash, object.size));
        }
        Log.info("Install", "资源索引 " + index.id + " 共 " + tasks.size() + " 个对象，约 "
                + index.totalBytes() / 1024 / 1024 + " MB");
        failures.addAll(downloader.download(tasks, concurrency, listener));
        return tasks.size();
    }

    /** 库的真实下载地址：优先 JSON 声明，其次老格式的仓库根，最后官方默认域名。 */
    public static String artifactUrl(Library lib, Library.Artifact artifact, String relativePath) {
        if (artifact != null && artifact.url != null && !artifact.url.isBlank()) {
            return artifact.url;
        }
        if (lib != null && lib.url != null && !lib.url.isBlank()) {
            String base = lib.url.endsWith("/") ? lib.url.substring(0, lib.url.length() - 1) : lib.url;
            return base + '/' + relativePath;
        }
        return "https://libraries.minecraft.net/" + relativePath;
    }

    /** 供预检 / 诊断使用的工具：列出当前版本应存在于 classpath 的文件。 */
    public Map<Library, Path> classpathFiles(VersionMeta meta, Rules.Context context) {
        LibraryCollector.Selection selection = LibraryCollector.select(meta, context);
        Map<Library, Path> files = new LinkedHashMap<>();
        for (Library lib : selection.classpath()) {
            Library.Artifact artifact = lib.downloads == null ? null : lib.downloads.artifact;
            String relative = lib.resolvePath(artifact);
            if (relative != null) {
                files.put(lib, AppPaths.librariesDir().resolve(relative));
            }
        }
        return files;
    }

    /** 读取安装标记，缺失或损坏返回 null。 */
    public static Marker readMarker(String versionId) {
        return JsonUtils.read(AppPaths.installedMarker(versionId), Marker.class, null);
    }
}
