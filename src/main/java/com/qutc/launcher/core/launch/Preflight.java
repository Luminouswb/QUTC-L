package com.qutc.launcher.core.launch;

import com.qutc.launcher.asset.AssetIndex;
import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.core.java.JavaMatrix;
import com.qutc.launcher.core.java.JavaRuntime;
import com.qutc.launcher.core.version.Library;
import com.qutc.launcher.core.version.LibraryCollector;
import com.qutc.launcher.core.version.VersionMeta;
import com.qutc.launcher.core.install.NativesExtractor;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.HashUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.OsUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 启动前七级强制检查链（总纲 §一.2）。
 *
 * <p>顺序固定，任一失败即终止启动：
 * <ol>
 *   <li>Java 环境匹配（查 {@link JavaMatrix}，禁止推断）</li>
 *   <li>版本 JSON 完整性</li>
 *   <li>libraries 哈希校验</li>
 *   <li>assets 资源索引检查</li>
 *   <li>natives 解压完整性</li>
 *   <li>目录读写权限</li>
 *   <li>JVM 参数合法性</li>
 * </ol>
 */
public final class Preflight {

    public record Check(int level, String name, boolean ok, String message) {
        public String display() {
            return (ok ? "[通过] " : "[失败] ") + level + ". " + name + "：" + message;
        }
    }

    public record Report(String versionId, List<Check> checks, List<String> warnings) {
        public boolean ok() {
            return checks.stream().allMatch(Check::ok);
        }

        public Check firstFailure() {
            for (Check check : checks) {
                if (!check.ok()) {
                    return check;
                }
            }
            return null;
        }

        public String summary() {
            Check failure = firstFailure();
            if (failure == null) {
                return "七级检查全部通过";
            }
            return "第 " + failure.level() + " 级检查失败（" + failure.name() + "）：" + failure.message();
        }
    }

    private Preflight() {
    }

    /**
     * @param verifyLibraryHashes 是否对每个库做完整 SHA-1 校验（关闭可加快启动）
     */
    public static Report run(LaunchContext ctx, boolean verifyLibraryHashes) {
        List<Check> checks = new ArrayList<>(7);
        List<String> warnings = new ArrayList<>();

        checks.add(javaCheck(ctx));
        checks.add(versionJsonCheck(ctx));
        checks.add(librariesCheck(ctx, verifyLibraryHashes));
        checks.add(assetsCheck(ctx));
        checks.add(nativesCheck(ctx));
        checks.add(permissionsCheck(ctx));
        checks.add(jvmArgsCheck(ctx, warnings));

        Report report = new Report(ctx.versionId, List.copyOf(checks), List.copyOf(warnings));
        for (Check check : checks) {
            Log.debug("Preflight", check.display());
        }
        Log.info("Preflight", "版本 " + ctx.versionId + "：" + report.summary());
        return report;
    }

    // ---- ① Java 匹配 ----

    private static Check javaCheck(LaunchContext ctx) {
        JavaRuntime runtime = ctx.javaRuntime;
        if (runtime == null) {
            return new Check(1, "Java 环境匹配", false, "未指定 Java 运行时");
        }
        // 版本 JSON 声明的 majorVersion 优先（整合包实例的 id 不含游戏版本号）
        int declared = ctx.meta != null && ctx.meta.javaVersion != null ? ctx.meta.javaVersion.majorVersion : 0;
        int minimum = declared > 0 ? declared : JavaMatrix.minimumMajor(ctx.versionId);
        int recommended = declared > 0 ? declared : JavaMatrix.recommendMajor(ctx.versionId);
        if (runtime.major() < minimum) {
            return new Check(1, "Java 环境匹配", false,
                    "该版本最低需要 Java " + minimum + "（推荐 Java " + recommended + "），当前选中 "
                            + runtime.displayName() + "，请到设置里切换 Java");
        }
        if (declared <= 0 && !JavaMatrix.compatible(ctx.versionId, runtime.major())) {
            return new Check(1, "Java 环境匹配", false,
                    "Java " + runtime.major() + " 超出该版本可用范围（最低 Java " + minimum + "），请切换运行时");
        }
        if (!runtime.is64Bit()) {
            return new Check(1, "Java 环境匹配", false,
                    "检测到 32 位 Java " + runtime.version() + "，游戏内存将受限，请安装 64 位 Java " + recommended);
        }
        return new Check(1, "Java 环境匹配", true,
                "Java " + runtime.major() + "（推荐 Java " + recommended + "）");
    }

    // ---- ② 版本 JSON 完整性 ----

    private static Check versionJsonCheck(LaunchContext ctx) {
        VersionMeta meta = ctx.meta;
        if (meta == null) {
            return new Check(2, "版本 JSON 完整性", false, "版本 JSON 未加载");
        }
        List<String> problems = new ArrayList<>();
        if (!meta.hasMainClass()) {
            problems.add("缺少 mainClass");
        }
        if (meta.safeLibraries().isEmpty()) {
            problems.add("libraries 为空（JSON 可能损坏）");
        }
        if (ctx.clientJar == null || !Files.isRegularFile(ctx.clientJar)) {
            problems.add("客户端 jar 缺失: " + ctx.clientJar);
        }
        if (ctx.assetIndexName == null || ctx.assetIndexName.isBlank()) {
            problems.add("缺少 assetIndex");
        }
        if (meta.assetIndex != null && meta.assetIndex.id != null
                && !Files.isRegularFile(assetIndexFile(ctx, meta.assetIndex.id))) {
            problems.add("资源索引文件缺失: " + meta.assetIndex.id + ".json");
        }
        if (!problems.isEmpty()) {
            return new Check(2, "版本 JSON 完整性", false, String.join("；", problems));
        }
        return new Check(2, "版本 JSON 完整性", true,
                "mainClass=" + meta.mainClass + "，库 " + meta.safeLibraries().size()
                        + " 个，资源索引 " + ctx.assetIndexName);
    }

    private static Path assetIndexFile(LaunchContext ctx, String indexId) {
        return ctx.assetsIndexesOverride != null
                ? ctx.assetsIndexesOverride.resolve(indexId + ".json")
                : AppPaths.assetsIndexFile(indexId);
    }

    // ---- ③ libraries 哈希校验 ----

    private static Check librariesCheck(LaunchContext ctx, boolean verifyHashes) {
        if (ctx.selection == null) {
            return new Check(3, "libraries 哈希校验", false, "依赖库列表未解析");
        }
        int total = 0;
        int missing = 0;
        int corrupted = 0;
        List<String> examples = new ArrayList<>();
        for (Library lib : ctx.selection.classpath()) {
            Library.Artifact artifact = lib.downloads == null ? null : lib.downloads.artifact;
            String relative = lib.resolvePath(artifact);
            if (relative == null) {
                continue;
            }
            total++;
            Path file = ctx.librariesDir.resolve(relative);
            if (!Files.isRegularFile(file)) {
                missing++;
                if (examples.size() < 3) {
                    examples.add(file.getFileName().toString());
                }
                continue;
            }
            if (!verifyHashes || artifact == null || artifact.sha1 == null) {
                continue;
            }
            if (artifact.size > 0 && FsUtils.sizeOf(file) != artifact.size) {
                corrupted++;
                if (examples.size() < 3) {
                    examples.add(file.getFileName() + "(大小不符)");
                }
                continue;
            }
            if (!HashUtils.verifySha1(file, artifact.sha1)) {
                corrupted++;
                if (examples.size() < 3) {
                    examples.add(file.getFileName() + "(哈希不符)");
                }
            }
        }
        if (missing > 0 || corrupted > 0) {
            String detail = "缺失 " + missing + " 个、损坏 " + corrupted + " 个（共 " + total + "）"
                    + (examples.isEmpty() ? "" : "，例如 " + String.join("、", examples));
            return new Check(3, "libraries 哈希校验", false, detail + "，请重新补全依赖库");
        }
        return new Check(3, "libraries 哈希校验", true,
                total + " 个库" + (verifyHashes ? "哈希全部通过" : "存在性检查通过"));
    }

    // ---- ④ assets 资源索引检查 ----

    private static Check assetsCheck(LaunchContext ctx) {
        if (ctx.meta == null || ctx.meta.assetIndex == null || ctx.meta.assetIndex.id == null) {
            return new Check(4, "assets 资源索引检查", true, "该版本未声明资源索引");
        }
        Path indexFile = assetIndexFile(ctx, ctx.meta.assetIndex.id);
        if (!Files.isRegularFile(indexFile)) {
            return new Check(4, "assets 资源索引检查", false, "资源索引缺失: " + indexFile.getFileName());
        }
        AssetIndex index = JsonUtils.read(indexFile, AssetIndex.class, null);
        if (index == null) {
            return new Check(4, "assets 资源索引检查", false, "资源索引无法解析，文件可能损坏");
        }
        if (index.id == null) {
            index.id = ctx.meta.assetIndex.id;
        }
        Map<String, AssetIndex.AssetObject> objects = index.safeObjects();
        Path objectsDir = ctx.assetsObjectsOverride != null
                ? ctx.assetsObjectsOverride : AppPaths.assetsObjectsDir();
        int missing = 0;
        int checked = 0;
        List<String> examples = new ArrayList<>();
        for (AssetIndex.AssetObject object : objects.values()) {
            if (object == null) {
                continue;
            }
            String path = object.objectPath();
            if (path == null) {
                continue;
            }
            checked++;
            Path file = objectsDir.resolve(path);
            if (!Files.isRegularFile(file)) {
                missing++;
                if (examples.size() < 3) {
                    examples.add(object.hash);
                }
                continue;
            }
            if (object.size > 0 && FsUtils.sizeOf(file) != object.size) {
                missing++;
                if (examples.size() < 3) {
                    examples.add(object.hash + "(大小不符)");
                }
            }
            if (checked >= 4000) {
                break;
            }
        }
        if (missing > 0) {
            return new Check(4, "assets 资源索引检查", false,
                    "已检查 " + checked + " 个资源，缺失 " + missing + " 个（例如 "
                            + String.join("、", examples) + "），请在下载中心补全资源");
        }
        return new Check(4, "assets 资源索引检查", true,
                "索引 " + index.id + " 共 " + objects.size() + " 个资源，抽查 " + checked + " 个齐全"
                        + (index.virtual ? "（virtual 布局）" : ""));
    }

    // ---- ⑤ natives 完整性 ----

    private static Check nativesCheck(LaunchContext ctx) {
        if (ctx.selection == null || ctx.selection.natives().isEmpty()) {
            return new Check(5, "natives 解压完整性", true, "该版本 natives 直接走 classpath，无需解压");
        }
        int jarsPresent = 0;
        List<String> missingJars = new ArrayList<>();
        for (LibraryCollector.NativeLib nativeLib : ctx.selection.natives()) {
            Library.Artifact artifact = nativeLib.artifact();
            String relative = nativeLib.library().resolvePath(artifact);
            if (relative == null) {
                continue;
            }
            Path jar = ctx.librariesDir.resolve(relative);
            if (Files.isRegularFile(jar)) {
                jarsPresent++;
            } else if (missingJars.size() < 3) {
                missingJars.add(jar.getFileName().toString());
            }
        }
        long nativeFiles = NativesExtractor.countNativeFiles(ctx.nativesDir);
        if (jarsPresent < ctx.selection.natives().size()) {
            return new Check(5, "natives 解压完整性", false,
                    "native 包缺失 " + missingJars + "，请重新补全 natives");
        }
        if (nativeFiles == 0) {
            return new Check(5, "natives 解压完整性", false,
                    "natives 目录为空（" + ctx.nativesDir + "），需要解压 " + jarsPresent + " 个 native 包");
        }
        return new Check(5, "natives 解压完整性", true,
                nativeFiles + " 个本地库文件，来自 " + jarsPresent + " 个 native 包");
    }

    // ---- ⑥ 目录读写权限 ----

    private static Check permissionsCheck(LaunchContext ctx) {
        List<String> problems = new ArrayList<>();
        if (!FsUtils.isWritable(ctx.gameDir)) {
            problems.add("游戏目录不可写: " + ctx.gameDir);
        }
        if (!FsUtils.isWritable(ctx.versionDir)) {
            problems.add("版本目录不可写: " + ctx.versionDir);
        }
        if (!FsUtils.isWritable(ctx.nativesDir)) {
            problems.add("natives 目录不可写: " + ctx.nativesDir);
        }
        if (ctx.assetsDir == null || !Files.isReadable(ctx.assetsDir)) {
            problems.add("资源目录不可读: " + ctx.assetsDir);
        }
        if (!problems.isEmpty()) {
            return new Check(6, "目录读写权限检查", false, String.join("；", problems)
                    + "（请检查是否被安全软件拦截，或目录位于只读位置）");
        }
        return new Check(6, "目录读写权限检查", true, "游戏目录、版本目录、natives、资源目录均可读写");
    }

    // ---- ⑦ JVM 参数合法性 ----

    private static Check jvmArgsCheck(LaunchContext ctx, List<String> warnings) {
        int memory = ctx.memoryMb;
        if (memory < 512) {
            return new Check(7, "JVM 参数合法性检查", false, "分配内存 " + memory + " MB 过小，至少 512 MB");
        }
        long physicalMb = OsUtils.totalPhysicalMemory() / 1024 / 1024;
        if (physicalMb > 0) {
            long hardLimit = physicalMb * 8 / 10;
            if (memory > hardLimit) {
                return new Check(7, "JVM 参数合法性检查", false,
                        "分配内存 " + memory + " MB 超过物理内存的 80%（" + physicalMb + " MB），JVM 可能无法启动");
            }
            long softLimit = physicalMb * 6 / 10;
            if (memory > softLimit) {
                warnings.add("分配内存 " + memory + " MB 超过物理内存的 60%（" + physicalMb
                        + " MB），建议降到 " + (softLimit / 1024) + " GB 左右");
            }
        }
        for (String arg : ctx.extraJvmArgs) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (arg.startsWith("-cp") || arg.startsWith("-classpath")) {
                return new Check(7, "JVM 参数合法性检查", false,
                        "自定义 JVM 参数不允许覆盖 -cp/-classpath（会导致找不到主类）: " + arg);
            }
            if (arg.startsWith("-Xmx") || arg.startsWith("-Xms")) {
                warnings.add("自定义 JVM 参数里的 " + arg + " 会与启动器内存设置冲突，请改用内置内存配置");
            }
        }
        return new Check(7, "JVM 参数合法性检查", true,
                "内存 " + memory + " MB，自定义 JVM 参数 " + ctx.extraJvmArgs.size() + " 项");
    }
}
