package com.qutc.launcher.core.java;

import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.OsUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java 运行时探测。
 *
 * <p>总纲要求「自动搜索已安装的 Java」。这里的发现顺序经过取舍：
 * 优先读 {@code release} 文件而不是执行 {@code java -version}，
 * 因为后者每次约 100–300ms、被杀软拦时更久，而启动器要在界面上秒开。
 * 只有 release 文件缺失时才退化为执行探测。
 */
public final class JavaProbe {

    private static final Pattern REG_VALUE =
            Pattern.compile("^\\s+(\\S.*?)\\s{2,}REG_[A-Z_]+\\s{2,}(.+)$");
    private static final Pattern VERSION_IN_OUTPUT =
            Pattern.compile("(?:openjdk|java)\\s+version\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_VALUE = Pattern.compile("\"([^\"]*)\"");

    private static volatile List<JavaRuntime> cached;

    private JavaProbe() {
    }

    /** 已探测到的运行时（首次调用会做全量扫描，之后走缓存）。 */
    public static List<JavaRuntime> discover() {
        List<JavaRuntime> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (JavaProbe.class) {
            if (cached == null) {
                cached = List.copyOf(scan());
            }
            return cached;
        }
    }

    /** 用户改了环境变量或手动添加 JDK 后强制重扫。 */
    public static void refresh() {
        synchronized (JavaProbe.class) {
            cached = null;
        }
    }

    /**
     * 选出最合适的运行时。
     *
     * @param required 版本 JSON 要求的主版本（0 表示无要求）
     */
    public static JavaRuntime best(List<JavaRuntime> runtimes, int required) {
        if (runtimes == null || runtimes.isEmpty()) {
            return null;
        }
        List<JavaRuntime> candidates = new ArrayList<>(runtimes);
        candidates.sort((a, b) -> {
            int byMajor = Integer.compare(b.major(), a.major());
            if (byMajor != 0) {
                return byMajor;
            }
            return compareVersion(b.version(), a.version());
        });
        if (required <= 0) {
            return candidates.get(0);
        }
        // 优先完全相同的主版本（8 版本跑在 17 上会因内部 API 变更直接崩）
        for (JavaRuntime runtime : candidates) {
            if (runtime.major() == required) {
                return runtime;
            }
        }
        // 其次选不低于要求的最高版本
        for (JavaRuntime runtime : candidates) {
            if (runtime.major() >= required) {
                return runtime;
            }
        }
        return null;
    }

    // ---- 扫描 ----

    private static List<JavaRuntime> scan() {
        Map<String, JavaRuntime> found = new LinkedHashMap<>();
        long start = System.nanoTime();

        addCandidate(found, envPath("JAVA_HOME"), "java_home");
        addPathEntries(found);
        addCurrentRuntime(found);
        scanRegistry(found);
        scanCommonDirs(found);

        List<JavaRuntime> result = new ArrayList<>(found.values());
        result.sort((a, b) -> Integer.compare(b.major(), a.major()));
        long cost = (System.nanoTime() - start) / 1_000_000L;
        Log.info("Java", "探测到 " + result.size() + " 个 Java 运行时（耗时 " + cost + "ms）");
        for (JavaRuntime runtime : result) {
            Log.debug("Java", "  " + runtime.displayName() + " @ " + runtime.home() + " [" + runtime.source() + ']');
        }
        return result;
    }

    private static void addPathEntries(Map<String, JavaRuntime> found) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return;
        }
        String name = OsUtils.isWindows() ? "java.exe" : "java";
        for (String entry : path.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isRegularFile(candidate)) {
                Path home = candidate.getParent() == null ? null : candidate.getParent().getParent();
                addCandidate(found, home, "path");
            }
        }
    }

    private static void addCurrentRuntime(Map<String, JavaRuntime> found) {
        addCandidate(found, envPath("QUTC_JAVA_HOME"), "launcher");
        String current = System.getProperty("java.home");
        if (current != null) {
            addCandidate(found, Path.of(current), "current");
        }
    }

    private static void scanRegistry(Map<String, JavaRuntime> found) {
        if (!OsUtils.isWindows()) {
            return;
        }
        String[] keys = {
                "HKLM\\SOFTWARE\\JavaSoft\\JRE",
                "HKLM\\SOFTWARE\\JavaSoft\\JDK",
                "HKLM\\SOFTWARE\\JavaSoft\\Java Runtime Environment",
                "HKLM\\SOFTWARE\\JavaSoft\\Java Development Kit",
                "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\JRE",
                "HKLM\\SOFTWARE\\Eclipse Adoptium",
                "HKLM\\SOFTWARE\\Microsoft\\JDK",
                "HKLM\\SOFTWARE\\Azul Systems\\Zulu",
                "HKLM\\SOFTWARE\\Amazon Corretto",
                "HKLM\\SOFTWARE\\BellSoft"
        };
        for (String key : keys) {
            for (String line : regQuery(key)) {
                Matcher matcher = REG_VALUE.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                String valueName = matcher.group(1).trim().toLowerCase(Locale.ROOT);
                if (!(valueName.contains("javahome") || valueName.equals("installdir")
                        || valueName.equals("installationpath") || valueName.equals("path")
                        || valueName.contains("home"))) {
                    continue;
                }
                String value = matcher.group(2).trim();
                if (value.isEmpty() || value.startsWith("C:\\Windows")) {
                    continue;
                }
                addCandidate(found, Path.of(value), "registry");
            }
        }
    }

    private static List<String> regQuery(String key) {
        List<String> lines = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("reg", "query", key, "/s").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            process.waitFor(6, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 注册表不可读不是致命问题，继续用目录扫描兜底
        }
        return lines;
    }

    private static void scanCommonDirs(Map<String, JavaRuntime> found) {
        List<Path> roots = new ArrayList<>();
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String localAppData = System.getenv("LOCALAPPDATA");
        String appData = System.getenv("APPDATA");

        if (programFiles != null) {
            for (String vendor : new String[]{"Java", "Eclipse Adoptium", "Microsoft", "Amazon Corretto",
                    "Zulu", "BellSoft", "Semeru", "JetBrains"}) {
                roots.add(Path.of(programFiles, vendor));
            }
        }
        if (programFilesX86 != null) {
            roots.add(Path.of(programFilesX86, "Java"));
            roots.add(Path.of(programFilesX86, "Eclipse Adoptium"));
        }
        if (localAppData != null) {
            roots.add(Path.of(localAppData, "Programs", "Eclipse Adoptium"));
            roots.add(Path.of(localAppData, "Programs", "Microsoft"));
            roots.add(Path.of(localAppData, "Packages"));
        }
        if (appData != null) {
            roots.add(Path.of(appData, ".minecraft", "runtime"));
        }
        if (OsUtils.isMac()) {
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
            roots.add(Path.of(System.getProperty("user.home", "."), "Library", "Java", "JavaVirtualMachines"));
        }
        if (OsUtils.isLinux()) {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/usr/java"));
        }

        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            // Packages 目录层数多且噪音大，只扫两层
            int depth = root.endsWith("Packages") ? 1 : 3;
            walkForHomes(root, depth, found);
        }
    }

    /**
     * 在根目录下 BFS 找 {@code bin/java(.exe)}。
     * 限制层数与节点数，避免在 Program Files 这种巨型目录里卡住。
     */
    private static void walkForHomes(Path root, int maxDepth, Map<String, JavaRuntime> found) {
        List<Path> level = List.of(root);
        int visited = 0;
        for (int depth = 0; depth <= maxDepth; depth++) {
            List<Path> next = new ArrayList<>();
            for (Path dir : level) {
                if (++visited > 600) {
                    return;
                }
                Path executable = javaExecutable(dir);
                if (executable != null) {
                    addCandidate(found, dir, "scan");
                    continue;
                }
                try (Stream<Path> children = Files.list(dir)) {
                    children.filter(Files::isDirectory).limit(64).forEach(next::add);
                } catch (IOException ignored) {
                    // 权限不足的目录直接跳过
                }
            }
            if (next.isEmpty()) {
                return;
            }
            level = next;
        }
    }

    private static Path javaExecutable(Path home) {
        String name = OsUtils.isWindows() ? "java.exe" : "java";
        Path candidate = home.resolve("bin").resolve(name);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        // macOS 布局：<home>/Contents/Home/bin/java
        Path macHome = home.resolve("Contents").resolve("Home");
        Path macJava = macHome.resolve("bin").resolve(name);
        return Files.isRegularFile(macJava) ? macJava : null;
    }

    private static void addCandidate(Map<String, JavaRuntime> found, Path home, String source) {
        if (home == null) {
            return;
        }
        JavaRuntime runtime = probe(home, source);
        if (runtime == null) {
            return;
        }
        String key = runtime.home().toString().toLowerCase(Locale.ROOT);
        JavaRuntime existing = found.get(key);
        // 同目录已存在时，保留来源更可信的那条（java_home > launcher > registry > scan > path）
        if (existing == null || sourceRank(source) < sourceRank(existing.source())) {
            found.put(key, runtime);
        }
    }

    private static int sourceRank(String source) {
        return switch (source) {
            case "java_home" -> 0;
            case "launcher" -> 1;
            case "current" -> 2;
            case "registry" -> 3;
            case "scan" -> 4;
            case "path" -> 5;
            default -> 6;
        };
    }

    // ---- 单点探测 ----

    /**
     * 探测一个 Java home。
     *
     * @return 不可用时返回 {@code null}
     */
    public static JavaRuntime probe(Path home, String source) {
        if (home == null) {
            return null;
        }
        Path normalized = home.toAbsolutePath().normalize();
        Path executable = javaExecutable(normalized);
        if (executable == null) {
            return null;
        }
        // java.home 可能指向 jre 子目录
        Path realHome = normalized;
        if (normalized.endsWith("jre") && Files.isDirectory(normalized.getParent().resolve("bin"))) {
            realHome = normalized.getParent();
        }

        Map<String, String> release = readRelease(realHome);
        String version = release.get("JAVA_VERSION");
        String vendor = release.getOrDefault("IMPLEMENTOR", release.get("JAVA_VENDOR"));
        String arch = normalizeArch(release.get("OS_ARCH"));

        if (version == null || version.isBlank()) {
            String[] probed = probeByExecuting(executable);
            if (probed != null) {
                version = probed[0];
                if (vendor == null || vendor.isBlank()) {
                    vendor = probed[1];
                }
            }
        }
        if (version == null || version.isBlank()) {
            return null;
        }
        if (arch == null) {
            arch = OsUtils.arch();
        }
        int major = majorOf(version);
        if (major <= 0) {
            return null;
        }
        return new JavaRuntime(realHome, executable, version, major,
                vendor == null ? "" : vendor.trim(), arch, source);
    }

    static Map<String, String> readRelease(Path home) {
        Path file = home.resolve("release");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                Matcher matcher = QUOTED_VALUE.matcher(value);
                if (matcher.find()) {
                    value = matcher.group(1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            Log.debug("Java", "读取 release 失败: " + file);
            return Map.of();
        }
        return values;
    }

    /** 兜底：执行 {@code java -version} 解析版本与厂商。 */
    static String[] probeByExecuting(Path executable) {
        try {
            Process process = new ProcessBuilder(executable.toString(), "-version")
                    .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String text = output.toString();
            Matcher matcher = VERSION_IN_OUTPUT.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            String version = matcher.group(1);
            String vendor = "";
            for (String line : text.split("\\R")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("runtime environment") || lower.contains("vm:")) {
                    vendor = line.trim();
                    break;
                }
            }
            return new String[]{version, vendor};
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    // ---- 版本号工具 ----

    /** 从版本号推断主版本：{@code 1.8.0_381 → 8}、{@code 21.0.12 → 21}。 */
    public static int majorOf(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        String value = version.trim();
        if (value.startsWith("1.")) {
            int dot = value.indexOf('.', 2);
            String second = dot > 0 ? value.substring(2, dot) : value.substring(2);
            return leadingInt(second);
        }
        return leadingInt(value);
    }

    private static int leadingInt(String text) {
        int end = 0;
        boolean negative = false;
        if (end < text.length() && (text.charAt(0) == '-' || text.charAt(0) == '+')) {
            negative = text.charAt(0) == '-';
            end = 1;
        }
        int start = end;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            int value = Integer.parseInt(text.substring(start, end));
            return negative ? -value : value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String normalizeArch(String releaseArch) {
        if (releaseArch == null || releaseArch.isBlank()) {
            return null;
        }
        String value = releaseArch.toLowerCase(Locale.ROOT);
        if (value.contains("aarch64") || value.contains("arm64")) {
            return "arm64";
        }
        if (value.contains("x86_64") || value.contains("amd64") || value.equals("x64")) {
            return "x64";
        }
        if (value.contains("x86") || value.contains("i386") || value.contains("i686")) {
            return "x86";
        }
        return value;
    }

    /**
     * 版本号比较（数值感知）：{@code 21.0.12.1 > 21.0.7}。
     *
     * <p>不能用字符串比较——那会把 21.0.7 排在 21.0.12.1 前面。
     */
    public static int compareVersion(String left, String right) {
        if (left == null) {
            return right == null ? 0 : -1;
        }
        if (right == null) {
            return 1;
        }
        String[] a = left.split("[._+\\-]");
        String[] b = right.split("[._+\\-]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            String x = i < a.length ? a[i] : "0";
            String y = i < b.length ? b[i] : "0";
            boolean xNumeric = isNumeric(x);
            boolean yNumeric = isNumeric(y);
            if (xNumeric && yNumeric) {
                long nx = parseLong(x);
                long ny = parseLong(y);
                if (nx != ny) {
                    return Long.compare(nx, ny);
                }
            } else if (xNumeric != yNumeric) {
                // 纯数字段优先于字母段（1.8.0 > 1.8.0-ea）
                return xNumeric ? 1 : -1;
            } else {
                int cmp = x.compareToIgnoreCase(y);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    }

    private static boolean isNumeric(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Path envPath(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    /** 去重后的所有 home 路径，供界面展示。 */
    public static Set<Path> homes() {
        Set<Path> homes = new LinkedHashSet<>();
        for (JavaRuntime runtime : discover()) {
            homes.add(runtime.home());
        }
        return homes;
    }
}
