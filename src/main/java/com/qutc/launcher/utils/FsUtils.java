package com.qutc.launcher.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 文件系统工具集：原子写、递归删除、可写性探测。
 *
 * <p>总纲要求所有 JSON 配置可自动修复、可回滚，因此配置写入必须走 {@link #writeAtomic}。
 */
public final class FsUtils {

    private FsUtils() {
    }

    public static void ensureDir(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    /**
     * 原子写文件：同目录临时文件 + 原子替换，避免断电/崩溃留下半截 JSON。
     *
     * <p>刻意不用 {@code Files.createTempFile}：它会缓存默认临时目录，
     * 在 {@code %TEMP%} 异常切到自建目录后会写到错误位置。
     */
    public static void writeAtomic(Path target, byte[] content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = parent == null
                ? Path.of(target.getFileName() + ".tmp-" + java.util.UUID.randomUUID())
                : parent.resolve("." + target.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
        try {
            Files.write(temp, content, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void writeAtomic(Path target, String content) throws IOException {
        writeAtomic(target, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        if (!Files.isDirectory(root)) {
            Files.deleteIfExists(root);
            return;
        }
        try (var stream = Files.walk(root)) {
            var paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** 目录读写权限探测：真实落地一个探针文件，比 exists() 可靠。 */
    public static boolean isWritable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".qutc-probe-", ".tmp");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static long sizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1L;
        } catch (IOException e) {
            return -1L;
        }
    }

    public static void copy(Path source, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 优先硬链接（外部版本导入用），失败回落复制。 */
    public static void linkOrCopy(Path source, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(target)) {
            return;
        }
        try {
            Files.createLink(target, source);
        } catch (IOException | UnsupportedOperationException e) {
            copy(source, target);
        }
    }

    public static String readString(Path file) throws IOException {
        return Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 生成安全的目录名/版本名：去掉 Windows 非法字符，保留中文。 */
    public static String sanitizeName(String raw) {
        if (raw == null) {
            return "unnamed";
        }
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (cleaned.isEmpty()) {
            return "unnamed";
        }
        if (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    /** 判断 child 是否位于 parent 之内，防止路径穿越。 */
    public static boolean isInside(Path parent, Path child) {
        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path normalizedChild = child.toAbsolutePath().normalize();
        return normalizedChild.startsWith(normalizedParent);
    }
}
