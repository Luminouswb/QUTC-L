package com.qutc.launcher.core.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从合并后的版本中挑出「真正要上类路径的库」与「要解压的 native 包」。
 *
 * <p>筛选顺序严格按官方语义：
 * <ol>
 *   <li>规则过滤（系统 / 架构 / 特性）；</li>
 *   <li>架构后缀过滤（官方 JSON 对 natives 只按 os.name 判定，三种位数的包会同时通过）；</li>
 *   <li>按平台挑选 native classifier（Windows x64 / ARM64 不同）；</li>
 *   <li><b>保持库的原始顺序</b>，不排序。</li>
 * </ol>
 *
 * <p><b>关于「下架库」</b>：曾经按黑名单剔除 Twitch / text2speech / lwjgl-platform，
 * 但实测（2026-10，官方 libraries.minecraft.net）这些文件都还在：
 * <ul>
 *   <li>{@code com.mojang:text2speech} 是 1.13+ 旁白的必需依赖，剔除会让 1.20.1
 *       直接 {@code NoClassDefFoundError: com/mojang/text2speech/Narrator}；</li>
 *   <li>{@code lwjgl-platform:2.9.4-nightly} 的 natives 分类包是 1.8.9 的
 *       {@code lwjgl64.dll} 来源，剔除会让游戏 {@code UnsatisfiedLinkError: no lwjgl64}。</li>
 * </ul>
 * 真正的死条目（如 {@code lwjgl:2.9.2-nightly}）由规则里的 {@code os.name=osx} 自然排除，
 * 不需要黑名单。下载失败会如实报告，而不是悄悄丢掉库文件。
 */
public final class LibraryCollector {

    private LibraryCollector() {
    }

    /** 选中的 native 包，附带解压排除规则。 */
    public record NativeLib(Library library, Library.Artifact artifact, List<String> excludes) {
    }

    /** 选择结果。classpath 与 natives 都保持版本 JSON 中的原始顺序。 */
    public record Selection(List<Library> classpath, List<NativeLib> natives) {

        public boolean isEmpty() {
            return classpath.isEmpty() && natives.isEmpty();
        }
    }

    public static Selection select(VersionMeta meta, Rules.Context context) {
        List<Library> classpath = new ArrayList<>();
        List<NativeLib> natives = new ArrayList<>();

        for (Library lib : meta.safeLibraries()) {
            if (lib == null || !lib.accepted(context)) {
                continue;
            }
            if (!acceptedForArch(lib, context)) {
                continue;
            }

            // 普通 jar
            Library.Artifact artifact = lib.downloads == null ? null : lib.downloads.artifact;
            if (artifact != null && artifact.declared()) {
                classpath.add(lib);
            } else if (lib.downloads == null && lib.url != null) {
                // 旧格式：没有 downloads 段，靠 url + name 推导
                classpath.add(lib);
            }

            // native
            String classifier = lib.nativeClassifier(context);
            if (classifier != null && lib.downloads != null && lib.downloads.classifiers != null) {
                Library.Artifact nativeArtifact = lib.downloads.classifiers.get(classifier);
                if (nativeArtifact != null && nativeArtifact.declared()) {
                    List<String> excludes = lib.extract == null || lib.extract.exclude == null
                            ? List.of() : lib.extract.exclude;
                    natives.add(new NativeLib(lib, nativeArtifact, excludes));
                }
            }
        }
        return new Selection(List.copyOf(classpath), List.copyOf(natives));
    }

    /**
     * 架构筛选。
     *
     * <p>官方 JSON 对 natives 条目只写 {@code {"os":{"name":"windows"}}}，
     * 于是 {@code natives-windows}、{@code natives-windows-arm64}、{@code natives-windows-x86}
     * 三条会同时通过规则判定。若原样全塞进 classpath，LWJGL 可能先命中错误的位数
     * （同名 dll 在不同包里的位数不同），表现为诡异的 UnsatisfiedLinkError。
     * 因此按 classifier 后缀再筛一次。
     */
    private static boolean acceptedForArch(Library lib, Rules.Context context) {
        String classifier = lib.classifier();
        if (classifier == null || classifier.isEmpty()) {
            return true;
        }
        String lower = classifier.toLowerCase(Locale.ROOT);
        String arch = context.arch() == null ? "x64" : context.arch().toLowerCase(Locale.ROOT);
        if (lower.endsWith("-arm64")) {
            return "arm64".equals(arch);
        }
        if (lower.endsWith("-x86")) {
            return "x86".equals(arch);
        }
        // 没有架构后缀的 natives 包是 x64 版本：arm64 / x86 机器不能吃
        if (lower.contains("natives")) {
            return "x64".equals(arch) || "amd64".equals(arch);
        }
        return true;
    }
}
