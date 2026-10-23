package com.qutc.launcher.core.mods;

import com.fasterxml.jackson.databind.JsonNode;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 本地模组扫描器（阶段 8）。
 *
 * <p>读取 {@code mods/} 下每个 jar 的元数据：
 * <ul>
 *   <li>Fabric / Quilt：{@code fabric.mod.json} / {@code quilt.mod.json}；</li>
 *   <li>Forge / NeoForge：{@code META-INF/mods.toml} / {@code META-INF/neoforge.mods.toml}；</li>
 *   <li>都没有时退回 jar 文件名。</li>
 * </ul>
 * 启用状态用 {@code .disabled} 后缀表示（MC 官方启动器与 PCL 的通行约定）。
 */
public final class ModScanner {

    /** 加载器 / 平台自带 id：这些依赖不需要额外安装模组。 */
    private static final Set<String> PLATFORM_IDS = Set.of(
            "minecraft", "java", "fabricloader", "fabric-loader", "fabric", "quilt_loader",
            "quilted_fabric_loader", "forge", "neoforge", "fml", "javafml", "lowcodefml", "architectury");

    public record Dependency(String modId, String range, boolean mandatory) {
        public boolean platform() {
            return PLATFORM_IDS.contains(modId.toLowerCase(Locale.ROOT));
        }
    }

    public record Mod(Path jar,
                      String fileName,
                      boolean enabled,
                      String id,
                      String name,
                      String version,
                      List<String> authors,
                      List<String> loaders,
                      List<Dependency> dependencies) {

        public String displayVersion() {
            return version == null || version.isBlank() ? "?" : version;
        }

        public String authorText() {
            return authors.isEmpty() ? "未知" : String.join(", ", authors);
        }
    }

    public record Report(List<Mod> mods, List<String> warnings) {

        public List<Mod> enabled() {
            return mods.stream().filter(Mod::enabled).toList();
        }

        public List<Mod> disabled() {
            return mods.stream().filter(mod -> !mod.enabled()).toList();
        }

        /** 这个模组缺失的必装前置（平台依赖与可选依赖不算）。 */
        public List<Dependency> missing(Mod mod) {
            Set<String> installed = new LinkedHashSet<>();
            for (Mod other : mods) {
                if (other.jar().equals(mod.jar())) {
                    continue;
                }
                installed.add(other.id().toLowerCase(Locale.ROOT));
            }
            List<Dependency> missing = new ArrayList<>();
            for (Dependency dependency : mod.dependencies()) {
                if (!dependency.mandatory() || dependency.platform()) {
                    continue;
                }
                if (!installed.contains(dependency.modId().toLowerCase(Locale.ROOT))) {
                    missing.add(dependency);
                }
            }
            return missing;
        }
    }

    /** 扫描目录；目录不存在时返回空报告而不是抛错。 */
    public Report scan(Path modsDir) {
        List<Mod> mods = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return new Report(mods, warnings);
        }
        try (var stream = Files.list(modsDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".jar") && !name.endsWith(".jar.disabled")) {
                    continue;
                }
                try {
                    mods.add(read(file));
                } catch (IOException error) {
                    warnings.add(file.getFileName() + ": " + error.getMessage());
                }
            }
        } catch (IOException error) {
            warnings.add("扫描 mods 目录失败: " + error.getMessage());
        }
        mods.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new Report(mods, warnings);
    }

    public Mod read(Path file) throws IOException {
        boolean enabled = !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".disabled");
        String baseName = file.getFileName().toString().replaceAll("(?i)\\.disabled$", "");
        try (ZipFile zip = new ZipFile(file.toFile())) {
            JsonNode fabric = jsonEntry(zip, "fabric.mod.json");
            if (fabric != null) {
                return fromFabric(file, baseName, enabled, fabric, "fabric");
            }
            JsonNode quilt = jsonEntry(zip, "quilt.mod.json");
            if (quilt != null) {
                return fromQuilt(file, baseName, enabled, quilt);
            }
            String forge = textEntry(zip, "META-INF/neoforge.mods.toml");
            if (forge != null) {
                return fromToml(file, baseName, enabled, forge, "neoforge");
            }
            forge = textEntry(zip, "META-INF/mods.toml");
            if (forge != null) {
                return fromToml(file, baseName, enabled, forge, "forge");
            }
        } catch (IOException error) {
            // 不是合法 zip，或者元数据损坏：保留为「未知模组」而不是从列表里消失
            Log.debug("Mods", "读取模组元数据失败 " + baseName + ": " + error.getMessage());
        }
        String guessedName = baseName.replaceAll("(?i)\\.jar$", "");
        return new Mod(file, baseName, enabled, guessedName.toLowerCase(Locale.ROOT),
                guessedName, "?", List.of(), List.of("unknown"), List.of());
    }

    /**
     * 启用 / 禁用：只改扩展名，不移动文件，避免破坏其他工具的引用。
     *
     * @return 改名后的新路径
     */
    public Path setEnabled(Path jar, boolean enabled) throws IOException {
        String name = jar.getFileName().toString();
        boolean currentlyEnabled = !name.toLowerCase(Locale.ROOT).endsWith(".disabled");
        if (currentlyEnabled == enabled) {
            return jar;
        }
        String targetName;
        if (enabled) {
            targetName = name.replaceAll("(?i)\\.disabled$", "");
        } else {
            targetName = name + ".disabled";
        }
        Path target = jar.resolveSibling(targetName);
        return Files.move(jar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- 元数据解析 ----

    private Mod fromFabric(Path file, String baseName, boolean enabled, JsonNode node, String loader)
            throws IOException {
        String id = node.path("id").asText(baseName);
        String name = node.path("name").asText(id);
        String version = node.path("version").asText("?");
        List<String> authors = new ArrayList<>();
        for (JsonNode author : node.path("authors")) {
            if (author.isTextual()) {
                authors.add(author.asText());
            } else if (author.hasNonNull("name")) {
                authors.add(author.path("name").asText());
            }
        }
        List<Dependency> dependencies = new ArrayList<>();
        JsonNode depends = node.path("depends");
        depends.fieldNames().forEachRemaining(key -> {
            JsonNode value = depends.get(key);
            String range = value.isArray() ? String.join(" || ", textArray(value))
                    : value.asText("*");
            dependencies.add(new Dependency(key, range, true));
        });
        JsonNode recommends = node.path("recommends");
        recommends.fieldNames().forEachRemaining(key ->
                dependencies.add(new Dependency(key, "*", false)));
        return new Mod(file, baseName, enabled, id, name, version, authors,
                List.of(loader), dependencies);
    }

    private Mod fromQuilt(Path file, String baseName, boolean enabled, JsonNode node) {
        JsonNode loader = node.path("quilt_loader");
        String id = loader.path("id").asText(node.path("id").asText(baseName));
        String version = loader.path("version").asText("?");
        JsonNode metadata = loader.path("metadata");
        String name = metadata.path("name").asText(id);
        List<String> authors = new ArrayList<>();
        for (JsonNode author : metadata.path("contributors")) {
            if (author.isTextual()) {
                authors.add(author.asText());
            } else if (author.hasNonNull("name")) {
                authors.add(author.path("name").asText());
            }
        }
        List<Dependency> dependencies = new ArrayList<>();
        JsonNode depends = loader.path("depends").isMissingNode() ? node.path("depends") : loader.path("depends");
        depends.fieldNames().forEachRemaining(key -> dependencies.add(
                new Dependency(key, depends.get(key).asText("*"), true)));
        return new Mod(file, baseName, enabled, id, name, version, authors,
                List.of("quilt"), dependencies);
    }

    /** 极简 TOML 读取：只关心 [[mods]] 与 [[dependencies.x]] 两个表。 */
    private Mod fromToml(Path file, String baseName, boolean enabled, String toml, String loader) {
        String id = null;
        String name = null;
        String version = "?";
        List<String> authors = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();
        String section = "";
        String dependencyOf = null;
        String depId = null;
        String depRange = null;
        boolean depMandatory = true;

        for (String rawLine : toml.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[[") && line.endsWith("]]")) {
                flushDependency(dependencies, dependencyOf, depId, depRange, depMandatory);
                depId = null;
                depRange = null;
                depMandatory = true;
                section = line.substring(2, line.length() - 2).trim();
                dependencyOf = section.startsWith("dependencies.")
                        ? section.substring("dependencies.".length()).trim() : null;
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                flushDependency(dependencies, dependencyOf, depId, depRange, depMandatory);
                depId = null;
                depRange = null;
                depMandatory = true;
                section = line.substring(1, line.length() - 1).trim();
                dependencyOf = null;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = strip(line.substring(eq + 1).trim());
            if (section.equals("mods")) {
                switch (key) {
                    case "modId" -> {
                        if (id == null) {
                            id = value;
                        }
                    }
                    case "displayName" -> {
                        if (name == null) {
                            name = value;
                        }
                    }
                    case "version" -> {
                        if ("?".equals(version)) {
                            version = value;
                        }
                    }
                    case "authors" -> {
                        for (String author : value.split(",")) {
                            if (!author.isBlank()) {
                                authors.add(author.trim());
                            }
                        }
                    }
                    default -> {
                        // 其余字段忽略
                    }
                }
            } else if (section.startsWith("dependencies.")) {
                switch (key) {
                    case "modId" -> depId = value;
                    case "versionRange" -> depRange = value;
                    case "mandatory" -> depMandatory = !"false".equalsIgnoreCase(value);
                    default -> {
                        // 忽略
                    }
                }
            }
        }
        flushDependency(dependencies, dependencyOf, depId, depRange, depMandatory);

        if (id == null || id.isBlank()) {
            id = baseName.replaceAll("(?i)\\.jar$", "").toLowerCase(Locale.ROOT);
        }
        if (name == null || name.isBlank()) {
            name = id;
        }
        return new Mod(file, baseName, enabled, id, name, version, authors,
                List.of(loader), dependencies);
    }

    private static void flushDependency(List<Dependency> target, String owner, String id,
                                        String range, boolean mandatory) {
        if (owner != null && id != null && !id.isBlank()) {
            target.add(new Dependency(id, range == null ? "*" : range, mandatory));
        }
    }

    private static String strip(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && (trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static JsonNode jsonEntry(ZipFile zip, String entryName) throws IOException {
        String text = textEntry(zip, entryName);
        if (text == null) {
            return null;
        }
        return JsonUtils.parse(text);
    }

    private static String textEntry(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> textArray(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(item.asText());
        }
        return result;
    }
}
