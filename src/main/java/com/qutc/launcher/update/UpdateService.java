package com.qutc.launcher.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.Http;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.main.Launcher;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.HashUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 12：增量更新与回滚。
 *
 * <p>更新源是一个静态目录，只需放两样东西：
 * <ol>
 *   <li>{@code files.json} —— 版本号 + 文件清单（sha256 / size / url）；</li>
 *   <li>对应版本的 jar（或安装包）文件。</li>
 * </ol>
 *
 * <p>流程：检查 → 下载到 {@code update/staging/} → 写 {@code update/pending.json}
 * → 退出启动器，由生成的 PowerShell 脚本等进程结束后替换 jar（Windows 下运行中的 jar 被锁）
 * → 重启；替换前把旧 jar 备份到 {@code update/backup/}，可用 {@code --rollback-update} 回滚。
 */
public final class UpdateService {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Manifest {
        public String version;
        public String notes;
        public String publishedAt;
        public List<FileEntry> files = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FileEntry {
        public String path;
        public String url;
        public String sha256;
        public long size = -1L;

        public FileEntry() {
        }

        public FileEntry(String path, String url, String sha256, long size) {
            this.path = path;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    public record CheckResult(boolean available, String remoteVersion, String currentVersion,
                              Manifest manifest, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pending(String remoteVersion, String stagedJar, String targetJar, String backupJar,
                          long createdAt) {
    }

    private UpdateService() {
    }

    public static Path updateDir() {
        return AppPaths.root().resolve("update");
    }

    public static Path stagingDir() {
        return updateDir().resolve("staging");
    }

    public static Path backupDir() {
        return updateDir().resolve("backup");
    }

    public static Path pendingFile() {
        return updateDir().resolve("pending.json");
    }

    // ---- 检查 ----

    /** 拉取 {@code <base>/files.json} 并判断是否有新版本。 */
    public static CheckResult check(String baseUrl) throws IOException {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IOException("未配置更新源地址（设置 → 更新 → 更新源）");
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String manifestUrl = base + "/files.json";
        byte[] body = Http.getBytes(List.of(manifestUrl), 15, 30);
        Manifest manifest = JsonUtils.parse(body, Manifest.class);
        if (manifest == null || manifest.version == null) {
            throw new IOException("files.json 格式不正确：" + manifestUrl);
        }
        boolean newer = isNewer(manifest.version, Launcher.VERSION);
        String message = newer
                ? "发现新版本 " + manifest.version + "（当前 " + Launcher.VERSION + "）"
                : "已是最新版本 " + Launcher.VERSION;
        return new CheckResult(newer, manifest.version, Launcher.VERSION, manifest, message);
    }

    /** 版本号按数字段比较：0.10.0 > 0.9.9。 */
    public static boolean isNewer(String remote, String local) {
        int[] r = segments(remote);
        int[] l = segments(local);
        int length = Math.max(r.length, l.length);
        for (int i = 0; i < length; i++) {
            int rv = i < r.length ? r[i] : 0;
            int lv = i < l.length ? l[i] : 0;
            if (rv != lv) {
                return rv > lv;
            }
        }
        return false;
    }

    private static int[] segments(String version) {
        if (version == null) {
            return new int[0];
        }
        String cleaned = version.replaceAll("^[vV]", "");
        String[] parts = cleaned.split("[^0-9]+");
        List<Integer> numbers = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                try {
                    numbers.add(Integer.parseInt(part));
                } catch (NumberFormatException ignored) {
                    // 超长数字直接忽略
                }
            }
        }
        int[] result = new int[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            result[i] = numbers.get(i);
        }
        return result;
    }

    // ---- 下载与准备 ----

    /** 只取清单里的主 jar（第一个 .jar 条目）。 */
    public static FileEntry mainJar(Manifest manifest) throws IOException {
        if (manifest == null || manifest.files == null) {
            throw new IOException("files.json 里没有 files");
        }
        for (FileEntry entry : manifest.files) {
            if (entry.path != null && entry.path.toLowerCase().endsWith(".jar")) {
                return entry;
            }
        }
        throw new IOException("files.json 里没有 .jar 文件条目");
    }

    /** 下载新 jar 到 staging，返回落盘路径。 */
    public static Path download(Downloader downloader, MirrorResolver mirrors, Manifest manifest) throws IOException {
        FileEntry entry = mainJar(manifest);
        if (entry.url == null || entry.url.isBlank()) {
            throw new IOException("files.json 的 jar 条目缺少 url");
        }
        FsUtils.ensureDir(stagingDir());
        Path target = stagingDir().resolve(Path.of(entry.path).getFileName().toString());
        Downloader.FileTask task = new Downloader.FileTask(List.of(entry.url), target, null,
                entry.sha256 == null || entry.sha256.isBlank() ? null : entry.sha256, entry.size);
        List<Downloader.Failure> failures = downloader.download(List.of(task), 1, null);
        if (!failures.isEmpty()) {
            throw new IOException("下载更新失败：" + failures.get(0).reason());
        }
        return target;
    }

    /** 写 pending.json，返回脚本路径。 */
    public static Path prepare(Path stagedJar, Manifest manifest) throws IOException {
        Path current = currentJar();
        if (current == null) {
            throw new IOException("当前以目录/classpath 方式运行，无法自更新（请用打包后的 jar 或安装包）");
        }
        FsUtils.ensureDir(updateDir());
        FsUtils.ensureDir(backupDir());
        Path backup = backupDir().resolve("qutc-launcher-" + Launcher.VERSION + ".jar");
        Pending pending = new Pending(manifest.version, stagedJar.toString(), current.toString(),
                backup.toString(), System.currentTimeMillis());
        JsonUtils.write(pendingFile(), pending);
        Log.info("Update", "已准备更新: " + Launcher.VERSION + " → " + manifest.version + "，待重启应用");
        return pendingFile();
    }

    /**
     * 生成并启动替换脚本：等待当前进程退出 → 备份旧 jar → 覆盖 → 重启 → 删除 pending。
     *
     * @param restartCommand 重启命令（通常是安装目录的 QUTC.exe 或 javaw 命令行）
     */
    public static Path spawnApplyScript(Pending pending, String restartCommand) throws IOException {
        FsUtils.ensureDir(updateDir());
        Path script = updateDir().resolve("apply-update.ps1");
        String restart = restartCommand == null || restartCommand.isBlank()
                ? ""
                : "Start-Process -FilePath " + psQuote(restartCommand);
        List<String> lines = new ArrayList<>();
        lines.add("$ErrorActionPreference = 'SilentlyContinue'");
        lines.add("Wait-Process -Id " + ProcessHandle.current().pid() + " -Timeout 30");
        lines.add("Start-Sleep -Milliseconds 800");
        lines.add("Copy-Item -LiteralPath '" + escape(pending.targetJar()) + "' -Destination '"
                + escape(pending.backupJar()) + "' -Force");
        lines.add("Copy-Item -LiteralPath '" + escape(pending.stagedJar()) + "' -Destination '"
                + escape(pending.targetJar()) + "' -Force");
        lines.add("Remove-Item -LiteralPath '" + escape(pendingFile().toString()) + "' -Force");
        if (!restart.isEmpty()) {
            lines.add(restart);
        }
        Files.writeString(script, String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", script.toString()).redirectErrorStream(true).start();
        Log.info("Update", "已启动更新脚本: " + script);
        return script;
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }

    private static String psQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ---- 回滚 ----

    /** 找最近一次备份。 */
    public static Path latestBackup() throws IOException {
        if (!Files.isDirectory(backupDir())) {
            return null;
        }
        try (var stream = Files.list(backupDir())) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(null);
        }
    }

    /**
     * 回滚：把备份 jar 覆盖回当前 jar。
     *
     * @return 被恢复的 jar 路径；没有备份时返回 null
     */
    public static Path rollback() throws IOException {
        Path current = currentJar();
        Path backup = latestBackup();
        if (current == null) {
            throw new IOException("当前以目录/classpath 方式运行，无法回滚");
        }
        if (backup == null) {
            return null;
        }
        Files.copy(backup, current, StandardCopyOption.REPLACE_EXISTING);
        Log.info("Update", "已回滚到备份: " + backup.getFileName());
        return current;
    }

    // ---- 工具 ----

    /** 当前运行的 jar；以 classes 目录运行时返回 null。 */
    public static Path currentJar() {
        try {
            java.security.CodeSource source = UpdateService.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path location = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(location) ? location : null;
        } catch (Exception error) {
            return null;
        }
    }

    /** 校验 staging 里的 jar 是否与清单一致。 */
    public static boolean verify(Path jar, FileEntry entry) throws IOException {
        if (entry.sha256 != null && !entry.sha256.isBlank()) {
            return entry.sha256.equalsIgnoreCase(HashUtils.sha256(jar));
        }
        return true;
    }
}
