package com.qutc.launcher.core.mods;

import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 资源包 / 光影包扫描器（阶段 8）。
 *
 * <p>两者都是「放进目录即生效」，启用状态同样用 {@code .disabled} 后缀表示
 * （资源包与光影包可以是文件夹或 zip）。
 */
public final class PackScanner {

    public enum Kind {
        RESOURCEPACK("resourcepacks", "资源包", ".zip"),
        SHADER("shaderpacks", "光影", ".zip");

        private final String dirName;
        private final String label;
        private final String extension;

        Kind(String dirName, String label, String extension) {
            this.dirName = dirName;
            this.label = label;
            this.extension = extension;
        }

        public String dirName() {
            return dirName;
        }

        public String label() {
            return label;
        }

        public String extension() {
            return extension;
        }
    }

    public record Pack(Path path,
                       String fileName,
                       boolean enabled,
                       String name,
                       Integer packFormat,
                       long sizeBytes,
                       Kind kind) {

        public String formatText() {
            if (kind == Kind.SHADER) {
                return "光影包";
            }
            return packFormat == null ? "格式未知" : "格式 " + packFormat;
        }
    }

    public List<Pack> scan(Path dir, Kind kind) {
        List<Pack> packs = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return packs;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isDirectory(path)) {
                    packs.add(read(path, kind));
                } else if (lower.endsWith(".zip") || lower.endsWith(".zip.disabled")) {
                    packs.add(read(path, kind));
                }
            }
        } catch (IOException error) {
            Log.warn("Packs", "扫描 " + dir + " 失败: " + error.getMessage());
        }
        packs.sort(Comparator.comparing(Pack::name, String.CASE_INSENSITIVE_ORDER));
        return packs;
    }

    public Path setEnabled(Pack pack, boolean enabled) throws IOException {
        if (pack.enabled() == enabled) {
            return pack.path();
        }
        String name = pack.path().getFileName().toString();
        String targetName = enabled ? name.replaceAll("(?i)\\.disabled$", "") : name + ".disabled";
        Path target = pack.path().resolveSibling(targetName);
        return Files.move(pack.path(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private Pack read(Path path, Kind kind) {
        String raw = path.getFileName().toString();
        boolean enabled = !raw.toLowerCase(Locale.ROOT).endsWith(".disabled");
        String base = raw.replaceAll("(?i)\\.disabled$", "");
        long size = directorySize(path);
        Integer format = null;
        boolean looksLikeShader = kind == Kind.SHADER;

        try {
            if (Files.isDirectory(path)) {
                Path meta = path.resolve("pack.mcmeta");
                if (Files.isRegularFile(meta)) {
                    format = readFormat(Files.readString(meta, StandardCharsets.UTF_8));
                }
                if (looksLikeShader && !Files.isDirectory(path.resolve("shaders"))) {
                    // 有些光影包允许 shaders 目录缺失，仅提示不算错误
                    Log.debug("Packs", "光影包没有 shaders/ 目录: " + base);
                }
            } else {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    ZipEntry meta = zip.getEntry("pack.mcmeta");
                    if (meta != null) {
                        try (InputStream in = zip.getInputStream(meta)) {
                            format = readFormat(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                    if (looksLikeShader) {
                        boolean hasShaders = zip.stream()
                                .anyMatch(entry -> entry.getName().startsWith("shaders/"));
                        if (!hasShaders) {
                            Log.debug("Packs", "zip 里没有 shaders/ 目录: " + base);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            Log.debug("Packs", "读取 " + base + " 失败: " + error.getMessage());
        }

        String name = base.replaceAll("(?i)\\.zip$", "");
        return new Pack(path, base, enabled, name, format, size, kind);
    }

    private static Integer readFormat(String json) {
        try {
            var node = JsonUtils.parse(json);
            var pack = node.path("pack");
            if (pack.has("pack_format")) {
                return pack.path("pack_format").asInt();
            }
            // 1.13 之前是 pack.pack_format 数字；1.20.5+ 出现 supported_formats
            if (pack.path("supported_formats").isArray()) {
                var first = pack.path("supported_formats").get(0);
                if (first != null && first.isInt()) {
                    return first.asInt();
                }
            }
            return null;
        } catch (IOException error) {
            return null;
        }
    }

    private static long directorySize(Path path) {
        if (!Files.isRegularFile(path)) {
            return 0L;
        }
        try {
            return Files.size(path);
        } catch (IOException error) {
            return 0L;
        }
    }
}
