package com.qutc.launcher.ui.service;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.config.VersionConfig;
import com.qutc.launcher.core.external.ExternalVersions;
import com.qutc.launcher.core.install.InstallEngine;
import com.qutc.launcher.core.loader.LoaderDetector;
import com.qutc.launcher.core.loader.LoaderKind;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 版本管理业务：扫描、识别、备份、导出、删除、打开目录。
 *
 * <p>UI 层只负责渲染；所有耗时的扫描/备份都应由调用方放进 {@code Async}。
 */
public final class VersionService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * @param displayName 用户展示名（自定义名优先）
     * @param modCount    mods 数量（内存推荐与 UI 展示用）
     */
    public record Entry(String versionId,
                        String displayName,
                        VersionConfig config,
                        LoaderDetector.Detection detection,
                        boolean installed,
                        Path dir,
                        long modifiedMillis,
                        int modCount,
                        String externalRoot) {

        public LoaderKind loader() {
            return detection.primary();
        }

        /** 是否是「引用外部目录」导入的版本（不是本启动器下载的）。 */
        public boolean imported() {
            return externalRoot != null && !externalRoot.isBlank();
        }

        /** 外部版本来源目录是否还在。 */
        public boolean sourceAvailable() {
            return !imported() || java.nio.file.Files.isDirectory(Path.of(externalRoot));
        }

        /** 来源徽标文案。 */
        public String sourceText() {
            if (!imported()) {
                return "本启动器下载";
            }
            return sourceAvailable() ? "外部导入" : "外部导入 · 来源不可用";
        }

        /** 「1.20.1 · Forge」这种展示文本。 */
        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            sb.append(versionId);
            if (detection.hasLoader()) {
                sb.append("  ·  ").append(detection.primary().displayName());
            } else {
                sb.append("  ·  原版");
            }
            if (detection.optifine() && detection.primary() != LoaderKind.OPTIFINE) {
                sb.append(" + OptiFine");
            }
            return sb.toString();
        }

        public boolean hidden() {
            return config != null && config.hidden;
        }
    }

    public List<Entry> scan() {
        Path versionsDir = AppPaths.versionsDir();
        List<Entry> result = new ArrayList<>();
        if (Files.isDirectory(versionsDir)) {
            try (var stream = Files.list(versionsDir)) {
                for (Path dir : stream.filter(Files::isDirectory).toList()) {
                    String id = dir.getFileName().toString();
                    Path json = dir.resolve(id + ".json");
                    if (!Files.isRegularFile(json)) {
                        continue;
                    }
                    result.add(toEntry(id, dir, null));
                }
            } catch (IOException e) {
                Log.warn("Version", "扫描版本目录失败: " + e.getMessage());
            }
        }
        // 引用式导入的版本：只登记路径，不复制文件
        for (ExternalVersions.Entry external : ExternalVersions.all()) {
            if (result.stream().anyMatch(entry -> entry.versionId().equals(external.versionId))) {
                continue;
            }
            Path root = Path.of(external.root);
            Path dir = root.resolve("versions").resolve(external.versionId);
            if (!Files.isRegularFile(dir.resolve(external.versionId + ".json"))) {
                Log.warn("Version", "外部版本已失效: " + external.versionId + " @ " + root);
                continue;
            }
            try {
                result.add(toEntry(external.versionId, dir, root.toString()));
            } catch (RuntimeException e) {
                Log.warn("Version", "读取外部版本失败: " + external.versionId + " | " + e.getMessage());
            }
        }
        result.sort(Comparator.comparingLong(Entry::modifiedMillis).reversed());
        return result;
    }

    public Entry load(String versionId) {
        Optional<ExternalVersions.Entry> external = ExternalVersions.find(versionId);
        if (external.isPresent()) {
            Path root = Path.of(external.get().root);
            return toEntry(versionId, root.resolve("versions").resolve(versionId), root.toString());
        }
        Path dir = AppPaths.versionDir(versionId);
        return toEntry(versionId, dir, null);
    }

    private Entry toEntry(String id, Path dir, String externalRoot) {
        VersionConfig config = VersionConfig.load(id);
        LoaderDetector.Detection detection = LoaderDetector.detect(id);
        long modified;
        try {
            modified = Files.getLastModifiedTime(dir.resolve(id + ".json")).toMillis();
        } catch (IOException e) {
            modified = 0L;
        }
        int modCount = 0;
        Path mods = externalRoot == null
                ? AppPaths.modsDir(id, config.isolated)
                : Path.of(externalRoot).resolve("versions").resolve(id).resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var stream = Files.list(mods)) {
                modCount = (int) stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .count();
            } catch (IOException ignored) {
                // 只影响展示
            }
        }
        return new Entry(id, config.label(), config, detection,
                InstallEngine.readMarker(id) != null, dir, modified, modCount, externalRoot);
    }

    /** 重命名只改显示名，不动目录 —— 加载器 id 与目录名绑定，改目录会破坏继承。 */
    public void rename(Entry entry, String displayName) {
        VersionConfig config = entry.config();
        config.displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
        config.save();
    }

    public void setHidden(Entry entry, boolean hidden) {
        VersionConfig config = entry.config();
        config.hidden = hidden;
        config.save();
    }

    public void saveConfig(VersionConfig config) {
        config.normalize();
        config.save();
    }

    /** 删除版本目录（调用方必须已确认）；引用式导入只移除登记，不动外部文件。 */
    public void delete(Entry entry) throws IOException {
        if (entry.imported()) {
            ExternalVersions.remove(entry.versionId());
            Log.info("Version", "已移除外部导入登记（外部文件未删除）: " + entry.versionId());
            return;
        }
        FsUtils.deleteRecursive(entry.dir());
        Log.info("Version", "已删除版本: " + entry.versionId());
    }

    /** 备份到 backups/：整个版本目录打成一个 zip。 */
    public Path backup(Entry entry) throws IOException {
        Path backupDir = AppPaths.root().resolve("backups");
        FsUtils.ensureDir(backupDir);
        Path zip = backupDir.resolve(entry.versionId() + '-' + STAMP.format(LocalDateTime.now()) + ".zip");
        zipDirectory(entry.dir(), zip);
        Log.info("Version", "已备份 " + entry.versionId() + " → " + zip);
        return zip;
    }

    /** 导出：把版本目录复制到目标目录下（保留目录名）。 */
    public Path exportTo(Entry entry, Path targetRoot) throws IOException {
        Path target = targetRoot.resolve(entry.versionId());
        copyTree(entry.dir(), target);
        Log.info("Version", "已导出 " + entry.versionId() + " → " + target);
        return target;
    }

    public void openFolder(Entry entry) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(entry.dir().toFile());
            }
        } catch (Exception e) {
            Log.warn("Version", "打开目录失败: " + e.getMessage());
        }
    }

    // ---- 内部工具 ----

    private static void zipDirectory(Path source, Path zipFile) throws IOException {
        try (OutputStream out = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            List<Path> files;
            try (var stream = Files.walk(source)) {
                files = stream.filter(Files::isRegularFile).toList();
            }
            for (Path file : files) {
                String name = source.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = Files.newInputStream(file)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
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
}
