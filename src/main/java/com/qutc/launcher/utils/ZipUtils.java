package com.qutc.launcher.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 自研 ZIP 读写。
 *
 * <p>安全边界（继承自上一代项目的踩坑经验，但用 Java 重写）：
 * <ul>
 *   <li>防穿越：entry 名归一化后必须仍落在目标目录内，拒绝绝对路径、盘符、{@code ..}。</li>
 *   <li>防炸弹：累计解压字节数与解压/压缩比双重阈值，超限立即中止。</li>
 *   <li>不创建符号链接：ZIP 里的 link 一律按普通文件写出，杜绝链接逃逸。</li>
 * </ul>
 */
public final class ZipUtils {

    private static final int BUFFER = 64 * 1024;

    /** 单次解压累计上限，默认 1 GiB。 */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    /** 解压/压缩比上限。 */
    public static final long MAX_RATIO = 200L;

    private ZipUtils() {
    }

    /**
     * 解压 zip 到目标目录。
     *
     * @param filter        条目过滤器，返回 false 跳过；为 null 表示全要
     * @param maxTotalBytes 累计解压上限
     * @param progress      已解压字节数回调，可为 null
     * @return 成功解压的条目数
     */
    public static int extract(Path zipFile,
                              Path destination,
                              Predicate<String> filter,
                              long maxTotalBytes,
                              LongConsumer progress) throws IOException {
        Files.createDirectories(destination);
        Path root = destination.toAbsolutePath().normalize();
        long total = 0;
        long limit = maxTotalBytes <= 0 ? DEFAULT_MAX_TOTAL_BYTES : maxTotalBytes;
        int count = 0;

        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String rawName = entry.getName();
                if (rawName == null || rawName.isEmpty()) {
                    continue;
                }
                String normalizedName = rawName.replace('\\', '/');
                if (filter != null && !filter.test(normalizedName)) {
                    continue;
                }
                Path target = resolveSafely(root, normalizedName);
                if (target == null) {
                    Log.warn("Zip", "跳过越界条目: " + rawName);
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                long declared = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (declared > 0 && compressed > 0 && declared / Math.max(compressed, 1L) > MAX_RATIO) {
                    throw new IOException("ZIP 疑似压缩炸弹: " + rawName
                            + " (" + declared + "/" + compressed + ")");
                }

                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(target,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[BUFFER];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        total += read;
                        if (total > limit) {
                            throw new IOException("ZIP 解压超出上限 " + limit + " 字节");
                        }
                        out.write(buffer, 0, read);
                        if (progress != null) {
                            progress.accept(total);
                        }
                    }
                }
                count++;
            }
        }
        return count;
    }

    /** 归一化并校验条目路径；越界返回 null。 */
    public static Path resolveSafely(Path root, String entryName) {
        String name = entryName.replace('\\', '/');
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.contains(":")) {
            return null;
        }
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        return resolved;
    }

    /** 把整个目录打包成 zip。 */
    public static void zipDirectory(Path directory, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path root = directory.toAbsolutePath().normalize();
        try (OutputStream fileOut = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            var stream = Files.walk(root);
            try (stream) {
                for (Path path : stream.sorted().toList()) {
                    if (path.equals(root)) {
                        continue;
                    }
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    if (Files.isDirectory(path)) {
                        zipOut.putNextEntry(new ZipEntry(relative + "/"));
                        zipOut.closeEntry();
                        continue;
                    }
                    zipOut.putNextEntry(new ZipEntry(relative));
                    Files.copy(path, zipOut);
                    zipOut.closeEntry();
                }
            }
        }
    }

    /** 读取 zip 内单个文本条目，不存在返回 null。 */
    public static String readText(Path zipFile, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            ZipEntry entry = zip.getEntry(entryName.replace('\\', '/'));
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
    }
}
