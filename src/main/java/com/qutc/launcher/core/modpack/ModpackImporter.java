package com.qutc.launcher.core.modpack;

import com.fasterxml.jackson.databind.JsonNode;
import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.core.install.InstallEngine;
import com.qutc.launcher.core.loader.LoaderInstaller;
import com.qutc.launcher.core.loader.LoaderKind;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.ZipUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Modrinth 整合包（{@code .mrpack}）导入（阶段 8）。
 *
 * <p>导入流程：
 * <ol>
 *   <li>读 {@code modrinth.index.json}，取整合包名、游戏版本、加载器；</li>
 *   <li>确保原版 + 对应加载器已安装（复用 {@link LoaderInstaller}）；</li>
 *   <li>在 {@code versions/} 下新建独立实例目录，把加载器版本的 JSON 复制成实例 id，
 *       实现「一个整合包 = 一个隔离版本」，与总纲的版本隔离默认开启一致；</li>
 *   <li>按 index 的 files 清单下载模组/资源到实例目录（SHA-1 校验）；</li>
 *   <li>解压 overrides 覆盖文件；</li>
 *   <li>写 {@code modpack.json} 元数据，供 UI 展示。</li>
 * </ol>
 */
public final class ModpackImporter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public record Index(String name, String versionId, String summary,
                        String minecraft, String loaderKind, String loaderVersion) {
    }

    public record FileSpec(String path, List<String> downloads, long size, String sha1) {
    }

    public record Result(String versionId, String name, String gameVersion, String loaderText,
                         int files, List<Downloader.Failure> failures) {
    }

    private final MirrorResolver mirrors;
    private final Downloader downloader;

    public ModpackImporter(MirrorResolver mirrors) {
        this.mirrors = mirrors == null ? MirrorResolver.of(null) : mirrors;
        this.downloader = new Downloader();
    }

    /** 读取 mrpack 的 index（不落地任何文件，用于导入前预览）。 */
    public Index readIndex(Path mrpack) throws IOException {
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            ZipEntry entry = zip.getEntry("modrinth.index.json");
            if (entry == null) {
                throw new IOException("不是有效的 Modrinth 整合包：缺少 modrinth.index.json");
            }
            String json;
            try (InputStream in = zip.getInputStream(entry)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode root = JsonUtils.parse(json);
            if (root.path("formatVersion").asInt(0) != 1) {
                throw new IOException("只支持 formatVersion = 1 的 mrpack，实际为 "
                        + root.path("formatVersion").asText("?"));
            }
            JsonNode dependencies = root.path("dependencies");
            String minecraft = dependencies.path("minecraft").asText(null);
            if (minecraft == null || minecraft.isBlank()) {
                throw new IOException("整合包没有声明 minecraft 版本");
            }
            String loaderKind = null;
            String loaderVersion = null;
            for (String key : new String[]{"fabric-loader", "quilt-loader", "forge", "neoforge"}) {
                if (dependencies.has(key)) {
                    loaderKind = key;
                    loaderVersion = dependencies.path(key).asText();
                    break;
                }
            }
            return new Index(
                    root.path("name").asText("未命名整合包"),
                    root.path("versionId").asText(""),
                    root.path("summary").asText(""),
                    minecraft, loaderKind, loaderVersion);
        }
    }

    public Result importMrpack(Path mrpack, Downloader.Listener listener) throws IOException {
        Index index = readIndex(mrpack);
        Log.info("Modpack", "导入整合包 " + index.name() + "（MC " + index.minecraft()
                + "，" + (index.loaderKind() == null ? "原版" : index.loaderKind() + " " + index.loaderVersion()) + "）");

        LoaderKind kind = loaderKindOf(index.loaderKind());
        String baseVersionId = index.minecraft();
        if (kind != null) {
            LoaderInstaller.Outcome outcome = new LoaderInstaller(mirrors)
                    .install(kind, index.minecraft(), index.loaderVersion(), listener);
            baseVersionId = outcome.versionId();
        } else {
            new InstallEngine(AppPaths.versionsDir(), downloader, mirrors)
                    .install(index.minecraft(), InstallEngine.Options.defaults(), listener);
        }

        String instanceId = uniqueInstanceId(index.name(), index.minecraft());
        Path instanceDir = AppPaths.versionDir(instanceId);
        FsUtils.ensureDir(instanceDir);

        // 把加载器版本的 JSON 复制成实例 id：实例继承加载器版本（加载器 JSON 自身再 inheritsFrom 原版）
        Path sourceJson = AppPaths.versionJson(baseVersionId);
        if (!Files.isRegularFile(sourceJson)) {
            throw new IOException("加载器版本 JSON 缺失，无法创建整合包实例: " + sourceJson);
        }
        JsonNode versionNode = JsonUtils.readTree(sourceJson);
        if (versionNode == null || !versionNode.isObject()) {
            throw new IOException("加载器版本 JSON 解析失败: " + sourceJson);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) versionNode).put("id", instanceId);
        if (!versionNode.has("inheritsFrom")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) versionNode).put("inheritsFrom", baseVersionId);
        }
        JsonUtils.write(AppPaths.versionJson(instanceId), versionNode);

        List<FileSpec> files = readFiles(mrpack);
        List<Downloader.FileTask> tasks = new ArrayList<>();
        for (FileSpec file : files) {
            Path target = safeResolve(instanceDir, file.path());
            if (target == null) {
                Log.warn("Modpack", "跳过越界文件: " + file.path());
                continue;
            }
            tasks.add(Downloader.FileTask.mirrors(
                    mirrors.candidates(file.downloads().isEmpty() ? "" : file.downloads().get(0)),
                    target, file.sha1(), file.size()));
        }

        List<Downloader.Failure> failures = tasks.isEmpty()
                ? List.of()
                : new Downloader().download(tasks, 6, listener);

        int overrides = extractOverrides(mrpack, instanceDir);
        FsUtils.ensureDir(AppPaths.modsDir(instanceId, true));

        writeMetadata(instanceId, index, baseVersionId, files.size(), overrides);

        // 补全实例的客户端 jar / 库 / 资源（实例 JSON 是加载器 JSON 的副本，缺失部分在这里补齐）
        try {
            new InstallEngine(AppPaths.versionsDir(), downloader, mirrors)
                    .install(instanceId, InstallEngine.Options.defaults(), null);
        } catch (IOException error) {
            Log.warn("Modpack", "实例依赖补全失败（首次启动会重试）: " + error.getMessage());
        }

        String loaderText = kind == null ? "原版" : kind.displayName() + " " + index.loaderVersion();
        Log.info("Modpack", "整合包导入完成 → " + instanceId + "（文件 " + tasks.size()
                + "，覆盖 " + overrides + "，失败 " + failures.size() + "）");
        return new Result(instanceId, index.name(), index.minecraft(), loaderText, tasks.size(), failures);
    }

    /** 读取 index 里的 files 数组。 */
    public List<FileSpec> readFiles(Path mrpack) throws IOException {
        List<FileSpec> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(mrpack.toFile())) {
            ZipEntry entry = zip.getEntry("modrinth.index.json");
            if (entry == null) {
                return result;
            }
            String json;
            try (InputStream in = zip.getInputStream(entry)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode root = JsonUtils.parse(json);
            for (JsonNode node : root.path("files")) {
                String path = node.path("path").asText("");
                if (path.isBlank()) {
                    continue;
                }
                List<String> downloads = new ArrayList<>();
                for (JsonNode url : node.path("downloads")) {
                    downloads.add(url.asText());
                }
                result.add(new FileSpec(path, downloads, node.path("fileSize").asLong(-1),
                        node.path("hashes").path("sha1").asText(null)));
            }
        }
        return result;
    }

    // ---- 内部 ----

    private static LoaderKind loaderKindOf(String key) {
        if (key == null) {
            return null;
        }
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "fabric-loader" -> LoaderKind.FABRIC;
            case "quilt-loader" -> LoaderKind.QUILT;
            case "forge" -> LoaderKind.FORGE;
            case "neoforge" -> LoaderKind.NEOFORGE;
            default -> null;
        };
    }

    private String uniqueInstanceId(String name, String minecraft) {
        String base = FsUtils.sanitizeName(name);
        if (base.isBlank()) {
            base = "modpack";
        }
        String candidate = base + "-" + minecraft;
        int counter = 2;
        while (Files.exists(AppPaths.versionDir(candidate))) {
            candidate = base + "-" + minecraft + "-" + counter;
            counter++;
        }
        return candidate;
    }

    private int extractOverrides(Path mrpack, Path instanceDir) throws IOException {
        Path tempRoot = AppPaths.cacheDir().resolve("modpack-tmp").resolve(UUID.randomUUID().toString());
        FsUtils.ensureDir(tempRoot);
        try {
            int count = ZipUtils.extract(mrpack, tempRoot,
                    name -> name.startsWith("overrides/") || name.startsWith("client-overrides/"),
                    ZipUtils.DEFAULT_MAX_TOTAL_BYTES, null);
            for (String prefix : new String[]{"overrides", "client-overrides"}) {
                Path source = tempRoot.resolve(prefix);
                if (Files.isDirectory(source)) {
                    copyTree(source, instanceDir);
                }
            }
            return count;
        } finally {
            try {
                FsUtils.deleteRecursive(tempRoot);
            } catch (IOException ignored) {
                // 临时目录清理失败不影响导入结果
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path out = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(path, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Path safeResolve(Path root, String relative) {
        Path resolved = root.resolve(relative.replace('\\', '/')).normalize();
        return resolved.startsWith(root.toAbsolutePath().normalize()) ? resolved : null;
    }

    private void writeMetadata(String instanceId, Index index, String baseVersionId,
                               int files, int overrides) throws IOException {
        var node = JsonUtils.newObject();
        node.put("name", index.name());
        node.put("summary", index.summary());
        node.put("packVersion", index.versionId());
        node.put("minecraft", index.minecraft());
        node.put("loader", index.loaderKind());
        node.put("loaderVersion", index.loaderVersion());
        node.put("baseVersionId", baseVersionId);
        node.put("files", files);
        node.put("overrides", overrides);
        node.put("installedAt", STAMP.format(LocalDateTime.now()));
        JsonUtils.write(AppPaths.versionDir(instanceId).resolve("modpack.json"), node);
    }

    /** 供 UI 判断某个版本是不是整合包实例。 */
    public static Map<String, String> readMetadata(String versionId) {
        Path file = AppPaths.versionDir(versionId).resolve("modpack.json");
        JsonNode node = JsonUtils.readTree(file);
        if (node == null) {
            return Map.of();
        }
        return Map.of(
                "name", node.path("name").asText(""),
                "summary", node.path("summary").asText(""),
                "minecraft", node.path("minecraft").asText(""),
                "loader", node.path("loader").asText(""),
                "loaderVersion", node.path("loaderVersion").asText(""));
    }
}
