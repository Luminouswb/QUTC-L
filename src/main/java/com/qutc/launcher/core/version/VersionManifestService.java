package com.qutc.launcher.core.version;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.download.Http;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

/**
 * 版本清单获取与缓存。
 *
 * <p>总纲要求「缓存 15 分钟」，实现方式：内存缓存 + 磁盘缓存双份，
 * 任一份在 TTL 内即视为新鲜，避免每次打开启动器都打一次网络请求。
 */
public final class VersionManifestService {

    public static final String OFFICIAL_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    /** 总纲：清单缓存 TTL 15 分钟。 */
    public static final long TTL_MILLIS = 15L * 60L * 1000L;

    private final Path cacheFile;
    private final MirrorResolver mirrors;
    private VersionManifest cached;
    private long cachedAtMillis;

    public VersionManifestService() {
        this(AppPaths.cacheDir().resolve("version_manifest_v2.json"), MirrorResolver.officialOnly());
    }

    public VersionManifestService(Path cacheFile) {
        this(cacheFile, MirrorResolver.officialOnly());
    }

    public VersionManifestService(Path cacheFile, MirrorResolver mirrors) {
        this.cacheFile = cacheFile;
        this.mirrors = mirrors == null ? MirrorResolver.officialOnly() : mirrors;
    }

    public Path cacheFile() {
        return cacheFile;
    }

    /**
     * 取清单。
     *
     * @param forceRefresh 忽略缓存强制拉取
     * @throws IOException 网络失败且本地无任何缓存
     */
    public synchronized VersionManifest get(boolean forceRefresh) throws IOException {
        if (!forceRefresh) {
            VersionManifest memory = freshInMemory();
            if (memory != null) {
                return memory;
            }
            VersionManifest disk = freshOnDisk();
            if (disk != null) {
                cached = disk;
                cachedAtMillis = System.currentTimeMillis();
                return disk;
            }
        }

        try {
            return fetch();
        } catch (IOException error) {
            // 网络失败时退回任意旧缓存：宁可给用户一份稍旧的列表，也不要直接打不开启动器
            VersionManifest fallback = readDisk();
            if (fallback != null) {
                Log.warn("Manifest", "拉取版本清单失败，使用本地旧缓存", error);
                cached = fallback;
                cachedAtMillis = System.currentTimeMillis();
                return fallback;
            }
            throw error;
        }
    }

    private VersionManifest fetch() throws IOException {
        Log.info("Manifest", "拉取版本清单（下载源：" + mirrors.describe() + "）");
        byte[] body = Http.getBytes(mirrors.manifestCandidates(), 30, 30);
        VersionManifest manifest = JsonUtils.parse(body, VersionManifest.class);
        if (manifest == null || manifest.safeVersions().isEmpty()) {
            throw new IOException("版本清单内容为空或格式异常");
        }
        FsUtils.writeAtomic(cacheFile, body);
        cached = manifest;
        cachedAtMillis = System.currentTimeMillis();
        Log.info("Manifest", "版本清单已更新，共 " + manifest.safeVersions().size() + " 个版本，"
                + "最新正式版=" + (manifest.latest == null ? "?" : manifest.latest.release));
        return manifest;
    }

    private VersionManifest freshInMemory() {
        if (cached == null) {
            return null;
        }
        return System.currentTimeMillis() - cachedAtMillis <= TTL_MILLIS ? cached : null;
    }

    private VersionManifest freshOnDisk() {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }
            FileTime modified = Files.getLastModifiedTime(cacheFile);
            long age = Instant.now().toEpochMilli() - modified.toMillis();
            if (age > TTL_MILLIS) {
                return null;
            }
            return readDisk();
        } catch (IOException ignored) {
            return null;
        }
    }

    private VersionManifest readDisk() {
        return JsonUtils.read(cacheFile, VersionManifest.class, null);
    }

    /** 供测试注入：直接给定一份清单。 */
    public synchronized void prime(VersionManifest manifest) {
        this.cached = manifest;
        this.cachedAtMillis = System.currentTimeMillis();
    }

    /** 只列正式版，按清单顺序（新→旧）。 */
    public List<VersionManifest.Entry> releases(boolean forceRefresh) throws IOException {
        return get(forceRefresh).safeVersions().stream()
                .filter(VersionManifest.Entry::isRelease)
                .toList();
    }
}
