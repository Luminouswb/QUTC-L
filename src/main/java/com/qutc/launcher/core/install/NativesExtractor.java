package com.qutc.launcher.core.install;

import com.qutc.launcher.core.version.LibraryCollector;
import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.ZipUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * natives 解压器。
 *
 * <p>两种形态必须都支持：
 * <ul>
 *   <li>老版本（≤1.18）：natives 以 classifier 包形式存在，需解压到 {@code natives/} 并传
 *       {@code -Djava.library.path}。</li>
 *   <li>新版本（1.19+）：natives 直接以 jar 进 classpath，由 LWJGL 自己解压到
 *       {@code -Dorg.lwjgl.system.SharedLibraryExtractPath} 指定目录，<b>不要</b>二次解压。</li>
 * </ul>
 *
 * <p>解压遵守各库自己的 {@code extract.exclude}（通常是 {@code META-INF/}），
 * 防穿越与防炸弹由 {@link ZipUtils#extract} 统一保证。
 */
public final class NativesExtractor {

    /** natives 目录里必须存在的文件扩展名（用于完整性判断）。 */
    private static final List<String> NATIVE_SUFFIXES = List.of(".dll", ".so", ".dylib", ".jnilib");

    private NativesExtractor() {
    }

    public record Result(int jars, int files, long bytes) {
        public static Result empty() {
            return new Result(0, 0, 0);
        }
    }

    /**
     * 把一组 native 库解压到目标目录。
     *
     * @param nativesDir 目标目录（{@code versions/<id>/natives}）
     * @param nativeJars 已下载到本地的 classifier jar
     * @param excludes   需要跳过的条目前缀，通常是 {@code META-INF/}
     */
    public static Result extract(Path nativesDir, List<NativeJar> nativeJars) throws IOException {
        if (nativeJars.isEmpty()) {
            return Result.empty();
        }
        Files.createDirectories(nativesDir);
        int jars = 0;
        int files = 0;
        long bytes = 0;
        for (NativeJar nativeJar : nativeJars) {
            if (!Files.isRegularFile(nativeJar.jar())) {
                Log.warn("Natives", "native 包缺失，跳过解压: " + nativeJar.jar());
                continue;
            }
            List<String> excludes = new ArrayList<>(nativeJar.excludes());
            // META-INF 永远不该进 natives 目录
            if (excludes.stream().noneMatch(prefix -> prefix.startsWith("META-INF"))) {
                excludes.add("META-INF/");
            }
            long before = countNativeFiles(nativesDir);
            int extracted = ZipUtils.extract(nativeJar.jar(), nativesDir, entry -> {
                String lower = entry.toLowerCase(Locale.ROOT);
                if (entry.endsWith("/")) {
                    return false;
                }
                for (String exclude : excludes) {
                    if (!exclude.isBlank() && entry.startsWith(exclude)) {
                        return false;
                    }
                }
                // 只要本地库文件，别把 java 类也铺出来
                return endsWithAny(lower, NATIVE_SUFFIXES);
            }, 256L * 1024 * 1024, null);
            long after = countNativeFiles(nativesDir);
            jars++;
            files += (int) (after - before);
            bytes += extracted;
            Log.debug("Natives", "解压 " + nativeJar.jar().getFileName() + " → " + nativesDir.getFileName()
                    + "，新增本地库 " + (after - before));
        }
        Result result = new Result(jars, files, bytes);
        Log.info("Natives", "natives 解压完成：" + result.jars() + " 个包，"
                + countNativeFiles(nativesDir) + " 个本地库文件");
        return result;
    }

    /** 一次性解压结果（按库分别指定 excludes 的场景）。 */
    public record NativeJar(Path jar, List<String> excludes) {
        public static NativeJar of(Path jar, List<String> excludes) {
            return new NativeJar(jar, excludes == null ? List.of() : excludes);
        }

        public static NativeJar of(LibraryCollector.NativeLib lib, Path jar) {
            return new NativeJar(jar, lib.excludes());
        }
    }

    /** 目录里现有本地库文件数，用于完整性判断。 */
    public static long countNativeFiles(Path nativesDir) {
        if (!Files.isDirectory(nativesDir)) {
            return 0;
        }
        try (var stream = Files.list(nativesDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> endsWithAny(path.getFileName().toString().toLowerCase(Locale.ROOT), NATIVE_SUFFIXES))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** natives 是否齐全：期望数量与实际数量一致。 */
    public static boolean isComplete(Path nativesDir, int expectedJars) {
        if (expectedJars <= 0) {
            return true;
        }
        return countNativeFiles(nativesDir) > 0;
    }

    private static boolean endsWithAny(String lower, List<String> suffixes) {
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
