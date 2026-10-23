package com.qutc.launcher.core.version;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 版本解析与 {@code inheritsFrom} 合并。
 *
 * <p>这是整个启动器最容易出错的地方，实现严格遵循总纲的两条铁律：
 * <ol>
 *   <li><b>无限层递归合并</b>（实际限制 8 层并做环路检测），不假定只有一层继承；</li>
 *   <li><b>合并后 libraries 顺序必须是「父版本在前、子版本在后」</b>，
 *       同名库（group:artifact:classifier）去重时保留子版本内容，但位置仍在原处。
 *       顺序一旦反转，加载器的 BootStrapLauncher 会拿到错误版本的 ASM / LWJGL。</li>
 * </ol>
 */
public final class VersionResolver {

    public static final int MAX_INHERIT_DEPTH = 8;

    private final Path versionsDir;
    private final Map<String, VersionMeta> rawCache = new HashMap<>();

    public VersionResolver(Path versionsDir) {
        this.versionsDir = versionsDir;
    }

    public VersionResolver() {
        this(AppPaths.versionsDir());
    }

    public Path versionsDir() {
        return versionsDir;
    }

    /**
     * 解析并合并出一个完整版本。
     *
     * @throws VersionException 版本 JSON 缺失、继承成环或超深、缺少主类
     */
    public VersionMeta resolve(String versionId) throws VersionException {
        List<VersionMeta> chain = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();

        String current = versionId;
        while (current != null && !current.isBlank()) {
            if (!visited.add(current)) {
                throw new VersionException("版本继承出现环路: " + String.join(" -> ", visited) + " -> " + current);
            }
            if (chain.size() >= MAX_INHERIT_DEPTH) {
                throw new VersionException("版本继承层数超过 " + MAX_INHERIT_DEPTH + ": " + visited);
            }
            VersionMeta meta = loadLocal(current);
            chain.add(meta);
            current = meta.inheritsFrom;
        }

        // chain: [0]=自身, [1]=父, [2]=祖父 …；合并必须从最底层祖先开始向上叠
        VersionMeta merged = VersionMeta.beginning();
        for (int i = chain.size() - 1; i >= 0; i--) {
            mergeInto(merged, chain.get(i));
        }

        if (!merged.hasMainClass()) {
            throw new VersionException("版本 " + versionId + " 合并后缺少 mainClass，JSON 不完整");
        }
        merged.sourceId = versionId;
        Log.debug("Version", "已解析 " + versionId + "，继承链长度=" + chain.size()
                + "，库数量=" + merged.safeLibraries().size());
        return merged;
    }

    /** 读取单个版本 JSON，带内存缓存。 */
    public VersionMeta loadLocal(String versionId) throws VersionException {
        VersionMeta cached = rawCache.get(versionId);
        if (cached != null) {
            return cached;
        }
        Path file = versionJsonPath(versionId);
        if (!Files.isRegularFile(file)) {
            throw new VersionException("找不到版本 JSON: " + file);
        }
        VersionMeta meta = JsonUtils.read(file, VersionMeta.class, null);
        if (meta == null) {
            throw new VersionException("版本 JSON 解析失败: " + file);
        }
        if (meta.id == null || meta.id.isBlank()) {
            meta.id = versionId;
        }
        rawCache.put(versionId, meta);
        return meta;
    }

    public Path versionJsonPath(String versionId) {
        return versionsDir.resolve(versionId).resolve(versionId + ".json");
    }

    /** 清空缓存，版本文件被重新下载后调用。 */
    public void invalidate(String versionId) {
        if (versionId == null) {
            rawCache.clear();
        } else {
            rawCache.remove(versionId);
        }
    }

    // ---- 合并逻辑 ----

    /**
     * 把 {@code child}（更具体的版本）叠加到 {@code base}（已合并的父链）上。
     */
    static void mergeInto(VersionMeta base, VersionMeta child) {
        if (child.mainClass != null && !child.mainClass.isBlank()) {
            base.mainClass = child.mainClass;
        }
        if (child.jar != null) {
            base.jar = child.jar;
        }
        if (child.id != null) {
            base.id = child.id;
        }
        if (child.type != null) {
            base.type = child.type;
        }
        if (child.assetIndex != null) {
            base.assetIndex = child.assetIndex;
        }
        if (child.assets != null) {
            base.assets = child.assets;
        }
        if (child.javaVersion != null && child.javaVersion.majorVersion > 0) {
            base.javaVersion = child.javaVersion;
        }
        if (child.minimumLauncherVersion > 0) {
            base.minimumLauncherVersion = child.minimumLauncherVersion;
        }
        if (child.releaseTime != null) {
            base.releaseTime = child.releaseTime;
        }
        if (child.time != null) {
            base.time = child.time;
        }
        if (child.logging != null && child.logging.client != null) {
            base.logging = child.logging;
        }
        // 旧版纯字符串参数：子版本有就覆盖
        if (child.minecraftArguments != null && !child.minecraftArguments.isBlank()) {
            base.minecraftArguments = child.minecraftArguments;
        }
        if (child.downloads != null) {
            if (base.downloads == null) {
                base.downloads = new LinkedHashMap<>();
            }
            base.downloads.putAll(child.downloads);
        }

        base.libraries = mergeLibraries(base.libraries, child.libraries);
        base.arguments = mergeArguments(base.arguments, child.arguments);
    }

    /**
     * 库合并：父在前、子在后；同名去重保留子内容但占父的位置。
     *
     * <p>去重<b>只发生在父与子之间</b>。同一个版本内部的重复键必须原样保留——
     * 1.8.9 就同时列了 {@code org.lwjgl.lwjgl:lwjgl:2.9.4-nightly}（通用）与
     * {@code 2.9.2-nightly}（仅 osx），若在单版本内部合并，2.9.2 会顶掉 2.9.4，
     * 再被规则的 osx 判定丢弃，最终 classpath 里既没有 lwjgl 也没有 lwjgl_util，
     * 表现为 {@code NoClassDefFoundError: org/lwjgl/opengl/OpenGLException}。
     */
    static List<Library> mergeLibraries(List<Library> parent, List<Library> child) {
        List<Library> result = new ArrayList<>(parent == null ? List.of() : parent);
        if (child == null || child.isEmpty()) {
            return result;
        }
        Map<String, Integer> parentPositions = new LinkedHashMap<>();
        for (int i = 0; i < result.size(); i++) {
            Library lib = result.get(i);
            if (lib != null) {
                parentPositions.putIfAbsent(lib.dedupeKey(), i);
            }
        }
        java.util.Set<String> replaced = new java.util.HashSet<>();
        for (Library lib : child) {
            if (lib == null) {
                continue;
            }
            String key = lib.dedupeKey();
            Integer existing = parentPositions.get(key);
            if (existing != null && !replaced.contains(key)) {
                result.set(existing, lib);
                replaced.add(key);
            } else {
                // 父链里没有，或子版本自己重复列出同一坐标：都按原顺序追加，交给规则筛选
                result.add(lib);
            }
        }
        return result;
    }

    /** 参数合并：父参数在前、子参数在后，子版本不能顶掉父版本的启动参数。 */
    static VersionMeta.VersionArguments mergeArguments(VersionMeta.VersionArguments parent,
                                                       VersionMeta.VersionArguments child) {
        VersionMeta.VersionArguments result = VersionMeta.VersionArguments.empty();
        if (parent != null) {
            if (parent.game != null) {
                result.game.addAll(parent.game);
            }
            if (parent.jvm != null) {
                result.jvm.addAll(parent.jvm);
            }
        }
        if (child != null) {
            if (child.game != null) {
                result.game.addAll(child.game);
            }
            if (child.jvm != null) {
                result.jvm.addAll(child.jvm);
            }
        }
        return result;
    }

    /** 合并是否保持了「父在前、子在后」。供自检与单元测试断言。 */
    public static boolean isParentFirst(List<Library> merged, List<Library> parent, List<Library> child) {
        if (merged == null) {
            return false;
        }
        int lastParentIndex = -1;
        int firstChildOnlyIndex = Integer.MAX_VALUE;
        Set<String> parentKeys = new LinkedHashSet<>();
        for (Library lib : parent == null ? List.<Library>of() : parent) {
            if (lib != null) {
                parentKeys.add(lib.dedupeKey());
            }
        }
        Set<String> childKeys = new LinkedHashSet<>();
        for (Library lib : child == null ? List.<Library>of() : child) {
            if (lib != null) {
                childKeys.add(lib.dedupeKey());
            }
        }
        for (int i = 0; i < merged.size(); i++) {
            Library lib = merged.get(i);
            if (lib == null) {
                continue;
            }
            String key = lib.dedupeKey();
            if (parentKeys.contains(key)) {
                lastParentIndex = Math.max(lastParentIndex, i);
            }
            if (childKeys.contains(key) && !parentKeys.contains(key)) {
                firstChildOnlyIndex = Math.min(firstChildOnlyIndex, i);
            }
        }
        if (lastParentIndex < 0 || firstChildOnlyIndex == Integer.MAX_VALUE) {
            return true;
        }
        return lastParentIndex < firstChildOnlyIndex;
    }

    public static final class VersionException extends IOException {
        public VersionException(String message) {
            super(message);
        }
    }
}
