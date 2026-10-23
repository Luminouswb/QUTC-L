package com.qutc.launcher.core.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.HashUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Forge / NeoForge 安装器。
 *
 * <p>两条路线，优先第一条：
 * <ol>
 *   <li><b>无头安装（首选）</b>：{@code java -jar installer.jar --installClient <staging>}。
 *       现代 Forge（1.13+）的客户端安装需要跑安装器内置的处理器（installertools 生成
 *       {@code client-srg.jar} / {@code client-extra.jar} / {@code forge-*-client.jar}），
 *       只解包 JSON 是不够的——这是真机踩出来的结论。安装器在 staging 目录里按
 *       原版 .minecraft 布局工作，完成后我们把 {@code versions/} 与 {@code libraries/}
 *       合并到 QUTC 的真实布局（共享库在 {@code shared/libraries}）。</li>
 *   <li><b>纯解包（兜底）</b>：安装器跑不起来时（例如离线/Java 缺失），直接取出
 *       内嵌版本 JSON 与 {@code maven/} 文件。古老版本（1.7.10 / 1.12）的客户端安装
 *       确实只需要这一步；现代版本可能仍需用户联网重试安装。</li>
 * </ol>
 */
public final class ForgeInstaller {

    public record Result(String versionId, int mavenFiles, boolean modernLayout) {
    }

    /** 无头安装的结果（版本与库都已合并进真实目录）。 */
    public record HeadlessResult(String versionId, int librariesCopied, int versionsCopied, Path logFile) {
    }

    private ForgeInstaller() {
    }

    /**
     * 无头执行安装器并把产物合并进真实目录。
     *
     * @param installerJar  已下载的 {@code *-installer.jar}
     * @param stagingDir    安装器的工作目录（会按 .minecraft 布局创建）
     * @param javaExecutable 用来跑安装器的 Java（通常按 MC 版本推荐的主版本选）
     * @param versionsDir   真实版本目录
     * @param librariesDir  真实共享库目录
     * @param lineSink      安装器输出回调，可为 null
     */
    public static HeadlessResult installHeadless(Path installerJar, Path stagingDir, Path javaExecutable,
                                                 Path versionsDir, Path librariesDir, Path seedLibraries,
                                                 String skipVersionId,
                                                 Consumer<String> lineSink, long timeoutSeconds) throws IOException {
        FsUtils.ensureDir(stagingDir);
        Path stagingLibraries = stagingDir.resolve("libraries");
        int seeded = seedLibraries(seedLibraries, stagingLibraries);
        if (seeded > 0) {
            Log.info("Loader", "预置安装器本地库 " + seeded + " 个文件（免去重复下载）");
        }
        Path profiles = stagingDir.resolve("launcher_profiles.json");
        if (!Files.isRegularFile(profiles)) {
            Files.writeString(profiles, "{\"profiles\":{},\"settings\":{},\"version\":3}", StandardCharsets.UTF_8);
        }
        Path logFile = stagingDir.resolveSibling(stagingDir.getFileName() + ".installer.log");

        Log.info("Loader", "执行安装器（无头）: " + installerJar.getFileName() + " @ " + javaExecutable);
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable.toString(),
                "-Dfile.encoding=UTF-8",
                "-jar", installerJar.toAbsolutePath().toString(),
                "--installClient", stagingDir.toAbsolutePath().toString());
        builder.directory(stagingDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> tail = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
                if (tail.size() >= 60) {
                    tail.remove(0);
                }
                tail.add(line);
                if (lineSink != null) {
                    lineSink.accept(line);
                }
            }
        }
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("等待加载器安装器时被中断", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("加载器安装器超时（" + timeoutSeconds + " 秒），日志: " + logFile);
        }
        if (process.exitValue() != 0) {
            throw new IOException("加载器安装器退出码 " + process.exitValue() + "，日志: " + logFile
                    + "\n最近输出:\n" + String.join("\n", tail));
        }

        Path stagingVersions = stagingDir.resolve("versions");
        if (!Files.isDirectory(stagingVersions)) {
            throw new IOException("安装器没有产出 versions 目录，日志: " + logFile);
        }
        int versionsCopied = 0;
        String versionId = null;
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(stagingVersions)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        }
        // 安装器会顺手写一份原版版本 JSON（id 就是 1.21.1）：那份不能覆盖我们自己的原版目录，
        // 真正的加载器版本 id 含 forge / neoforge
        dirs.removeIf(path -> skipVersionId != null
                && skipVersionId.equals(path.getFileName().toString()));
        dirs.sort(Comparator.comparingLong(path -> {
            try {
                return -Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }));
        // 优先加载器版本目录
        List<Path> loaderDirs = new ArrayList<>();
        for (Path dir : dirs) {
            String id = dir.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (id.contains("forge") || id.contains("neoforge")) {
                loaderDirs.add(dir);
            }
        }
        List<Path> ordered = new ArrayList<>(loaderDirs);
        for (Path dir : dirs) {
            if (!loaderDirs.contains(dir)) {
                ordered.add(dir);
            }
        }
        for (Path dir : ordered) {
            String id = dir.getFileName().toString();
            Path json = dir.resolve(id + ".json");
            if (!Files.isRegularFile(json)) {
                continue;
            }
            Path targetDir = versionsDir.resolve(id);
            FsUtils.ensureDir(targetDir);
            try (var stream = Files.list(dir)) {
                for (Path file : stream.toList()) {
                    Files.copy(file, targetDir.resolve(file.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            versionsCopied++;
            if (versionId == null) {
                versionId = id;
            }
            Log.info("Loader", "安装器产出客户端版本: " + id);
        }
        if (versionId == null) {
            throw new IOException("安装器没有写出任何版本 JSON，日志: " + logFile);
        }

        int librariesCopied = copyTree(stagingDir.resolve("libraries"), librariesDir);

        Log.info("Loader", "安装器产物已合并：版本 " + versionId + "，库文件 " + librariesCopied + " 个");
        try {
            FsUtils.deleteRecursive(stagingDir);
        } catch (IOException e) {
            Log.warn("Loader", "清理安装器临时目录失败: " + e.getMessage());
        }
        return new HeadlessResult(versionId, librariesCopied, versionsCopied, logFile);
    }

    /**
     * 从安装器的 {@code install_profile.json} 读取它自己要用的全部依赖，经镜像链提前下到
     * staging 的 libraries 目录。
     *
     * <p>为什么必须做这一步：NeoForge / Forge 安装器内部会硬连自己的 Maven
     * （{@code maven.neoforged.net} / {@code maven.minecraftforge.net}），
     * 而国内经常连不上；它又不会走启动器的镜像。把 installertools、neoform、universal
     * 这些它要用的包提前放好，安装器校验 SHA-1 命中后就不再联网。
     *
     * @return 成功预置的文件数（失败项只记日志，不中断）
     */
    public static int seedFromInstallProfile(Path installerJar, Path stagingLibraries,
                                             Downloader downloader, MirrorResolver mirrors,
                                             Downloader.Listener listener) throws IOException {
        List<Downloader.FileTask> tasks = new ArrayList<>();
        try (ZipFile zip = new ZipFile(installerJar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry profileEntry = zip.getEntry("install_profile.json");
            if (profileEntry == null) {
                return 0;
            }
            String text;
            try (InputStream in = zip.getInputStream(profileEntry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonNode profile = JsonUtils.parse(text);
            for (JsonNode library : profile.path("libraries")) {
                JsonNode artifact = library.path("downloads").path("artifact");
                String url = artifact.path("url").asText(null);
                String relative = artifact.path("path").asText(null);
                if (url == null || relative == null || url.isBlank() || relative.isBlank()) {
                    continue;
                }
                String sha1 = artifact.path("sha1").asText(null);
                long size = artifact.path("size").asLong(-1);
                Path target = stagingLibraries.resolve(relative).normalize();
                if (!target.startsWith(stagingLibraries.toAbsolutePath().normalize())) {
                    continue;
                }
                if (Files.isRegularFile(target) && (sha1 == null || HashUtils.verifySha1(target, sha1))) {
                    continue;
                }
                tasks.add(Downloader.FileTask.mirrors(mirrors.candidates(url), target, sha1, size));
            }
        }
        if (tasks.isEmpty()) {
            return 0;
        }
        Log.info("Loader", "预置安装器依赖 " + tasks.size() + " 个（经镜像链）");
        List<Downloader.Failure> failures = downloader.download(tasks, 8, listener);
        if (!failures.isEmpty()) {
            Log.warn("Loader", "安装器依赖有 " + failures.size() + " 个预置失败，安装器可能需要自行联网: "
                    + failures.get(0).reason());
        }
        return tasks.size() - failures.size();
    }

    /** 递归复制目录树（不存在则跳过），返回复制的文件数。 */
    static int copyTree(Path source, Path target) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            return 0;
        }
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        int copied = 0;
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Path relative = source.relativize(path);
                Path out = root.resolve(relative.toString()).normalize();
                if (!out.startsWith(root)) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(path, out, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        return copied;
    }

    /**
     * 把真实共享库「预置」到安装器工作目录：同卷用硬链接（几乎零成本），
     * 跨卷退化为复制。这样安装器只下载它真正缺的东西，而不是从官方源重下一遍
     * （官方源在国内经常超时，会把安装器直接搞失败）。
     */
    static int seedLibraries(Path source, Path target) throws IOException {
        if (source == null || !Files.isDirectory(source)) {
            return 0;
        }
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        int count = 0;
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Path relative = source.relativize(path);
                Path out = root.resolve(relative.toString()).normalize();
                if (!out.startsWith(root) || Files.exists(out)) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                FsUtils.linkOrCopy(path, out);
                count++;
            }
        }
        return count;
    }

    /**
     * 纯解包路线（兜底；古老版本足够，现代版本可能仍缺处理器产物）。
     *
     * @param installerJar 已下载的 {@code *-installer.jar}
     * @param versionsDir  启动器版本目录
     * @param librariesDir 共享库目录
     */
    public static Result extract(Path installerJar, Path versionsDir, Path librariesDir) throws IOException {
        try (ZipFile zip = new ZipFile(installerJar.toFile(), StandardCharsets.UTF_8)) {
            String jsonText = readEntry(zip, "version.json");
            boolean modern = jsonText != null;
            String versionId;
            if (modern) {
                versionId = JsonUtils.parse(jsonText).path("id").asText(null);
            } else {
                String profileText = readEntry(zip, "install_profile.json");
                if (profileText == null) {
                    throw new IOException("安装器里既没有 version.json 也没有 install_profile.json，可能不是合规范安装器");
                }
                JsonNode profile = JsonUtils.parse(profileText);
                JsonNode versionInfo = profile.path("versionInfo");
                if (versionInfo.isMissingNode() || versionInfo.isNull()) {
                    throw new IOException("install_profile.json 缺少 versionInfo");
                }
                versionId = versionInfo.path("id").asText(null);
                jsonText = versionInfo.toString();
            }
            if (versionId == null || versionId.isBlank()) {
                throw new IOException("安装器内的版本 JSON 缺少 id 字段");
            }

            int mavenFiles = copyMaven(zip, librariesDir);

            Path target = versionsDir.resolve(versionId).resolve(versionId + ".json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, jsonText, StandardCharsets.UTF_8);
            Log.info("Loader", "解包安装器: " + installerJar.getFileName() + " → 版本 " + versionId
                    + "（maven 文件 " + mavenFiles + " 个，布局 " + (modern ? "version.json" : "install_profile") + "）");
            return new Result(versionId, mavenFiles, modern);
        }
    }

    /** 把 {@code maven/**} 铺进共享库目录，带路径穿越防护。 */
    static int copyMaven(ZipFile zip, Path librariesDir) throws IOException {
        Path root = librariesDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        int copied = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith("maven/") || name.endsWith("/")) {
                continue;
            }
            String relative = name.substring("maven/".length());
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) {
                Log.warn("Loader", "安装器内含越界路径，已跳过: " + name);
                continue;
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            copied++;
        }
        return copied;
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
