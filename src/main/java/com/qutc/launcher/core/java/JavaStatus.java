package com.qutc.launcher.core.java;

import com.qutc.launcher.utils.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 启动前 Java 匹配状态（主页与预检共用一份判定，避免两处口径不一致）。
 */
public final class JavaStatus {

    /**
     * @param ok              是否满足该版本推荐 Java
     * @param missing         本机根本没找到 Java
     * @param recommendedMajor 该版本推荐的 Java 主版本（查 §三.4 矩阵）
     * @param runtimeName     实际选中的运行时名称
     * @param executable      实际 java.exe 路径
     * @param message         给用户看的一句话
     */
    public record Report(boolean ok, boolean missing, int recommendedMajor,
                         String runtimeName, String executable, String message) {

        public String describe() {
            if (missing) {
                return "Java 缺失（需要 Java " + recommendedMajor + "）";
            }
            return (ok ? "Java " : "Java 不兼容（需要 " + recommendedMajor + "）：") + runtimeName;
        }
    }

    private JavaStatus() {
    }

    public static Report forVersion(String versionId) {
        return forVersion(versionId, null);
    }

    public static Report forVersion(String versionId, Path javaHomeOverride) {
        int recommended = JavaMatrix.recommendMajor(versionId);
        List<JavaRuntime> runtimes = JavaProbe.discover();
        if (javaHomeOverride != null) {
            JavaRuntime forced = JavaProbe.probe(javaHomeOverride, "用户指定");
            if (forced != null) {
                boolean ok = forced.major() >= JavaMatrix.minimumMajor(versionId);
                return new Report(ok, false, recommended, forced.displayName(),
                        forced.executable().toString(),
                        ok ? "使用用户指定的 Java " + forced.major()
                                : "用户指定的 Java " + forced.major() + " 低于该版本最低要求 "
                                + JavaMatrix.minimumMajor(versionId));
            }
            Log.warn("Java", "用户指定的 Java 路径不可用: " + javaHomeOverride);
        }
        if (runtimes.isEmpty()) {
            return new Report(false, true, recommended, null, null,
                    "未检测到任何 Java，请先在设置里指定或安装 Java " + recommended);
        }
        JavaRuntime best = JavaProbe.best(runtimes, recommended);
        if (best != null) {
            return new Report(true, false, recommended, best.displayName(),
                    best.executable().toString(), "Java " + best.major() + " 匹配");
        }
        JavaRuntime fallback = JavaProbe.best(runtimes, JavaMatrix.minimumMajor(versionId));
        if (fallback != null) {
            return new Report(false, false, recommended, fallback.displayName(),
                    fallback.executable().toString(),
                    "未找到 Java " + recommended + "，将退回 Java " + fallback.major() + "（可能不稳定）");
        }
        JavaRuntime any = runtimes.get(0);
        return new Report(false, false, recommended, any.displayName(), any.executable().toString(),
                "现有 Java " + any.major() + " 低于该版本最低要求 " + JavaMatrix.minimumMajor(versionId));
    }

    /** 版本目录里是否有 mods（决定内存推荐档位）。 */
    public static int countMods(String versionId) {
        Path mods = com.qutc.launcher.config.AppPaths.modsDir(versionId, true);
        return countJars(mods);
    }

    public static boolean hasShaders(String versionId) {
        Path shaders = com.qutc.launcher.config.AppPaths.shaderPacksDir(versionId, true);
        return containsEntries(shaders);
    }

    private static int countJars(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean containsEntries(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        } catch (Exception e) {
            return false;
        }
    }
}
