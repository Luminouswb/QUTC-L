package com.qutc.launcher.download;

import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.config.LauncherConfig;
import com.qutc.launcher.utils.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 下载源解析：把官方 URL 翻译成「按优先级排列的候选 URL 列表」。
 *
 * <p>总纲 §三.2 的「死规则」在这里落地，且全部经过真机探测（2026-10）：
 *
 * <table border="1">
 *   <tr><th>资源</th><th>官方</th><th>BMCLAPI 等价路径</th></tr>
 *   <tr><td>版本清单</td><td>{@code piston-meta.mojang.com/mc/game/version_manifest_v2.json}</td>
 *       <td>同路径（只换域名）</td></tr>
 *   <tr><td>版本 JSON</td><td>{@code piston-meta.mojang.com/v1/packages/...}</td>
 *       <td>同路径；另有 {@code /version/<id>/json} 快捷入口</td></tr>
 *   <tr><td>资源索引</td><td>{@code piston-meta.mojang.com/v1/packages/<sha1>/<id>.json}</td>
 *       <td>同路径（实测 200，<b>不是</b> {@code /assets/indexes/}）</td></tr>
 *   <tr><td>依赖库</td><td>{@code libraries.minecraft.net/<path>}</td>
 *       <td>{@code /maven/<path>}</td></tr>
 *   <tr><td>散列资源</td><td>{@code resources.download.minecraft.net/<前两位>/<hash>}</td>
 *       <td>{@code /assets/<前两位>/<hash>}</td></tr>
 *   <tr><td>Forge / NeoForge / Fabric / Quilt</td><td>各自 Maven</td>
 *       <td>统一 {@code /maven/<path>}（实测 Forge/NeoForge 安装器均 200）</td></tr>
 * </table>
 *
 * <p>mcbbs 镜像（{@code download.mcbbs.net}）曾是 BMCLAPI 的完整等价镜像，
 * 但该域名现已无法解析；仍保留在降级链里，DNS 失败只需约 20ms，不会拖慢下载。
 *
 * <p>fallback 顺序固定为「BMCLAPI → mcbbs → 官方」，<b>镜像失败不直接跳官方</b>：
 * 国内环境下官方源往往更慢。
 */
public final class MirrorResolver {

    public static final String BMCLAPI_BASE = "https://bmclapi2.bangbang93.com";
    public static final String MCBBS_BASE = "https://download.mcbbs.net";

    public static final String OFFICIAL_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** 官方域名 → 该域名下的路径要挂到镜像的哪个前缀。 */
    private static final String[][] PREFIX_RULES = {
            {"https://piston-meta.mojang.com/", "/"},
            {"https://launchermeta.mojang.com/", "/"},
            {"https://libraries.minecraft.net/", "/maven/"},
            {"https://resources.download.minecraft.net/", "/assets/"},
            {"https://maven.minecraftforge.net/", "/maven/"},
            {"https://maven.neoforged.net/releases/", "/maven/"},
            {"https://maven.fabricmc.net/", "/maven/"},
            {"https://maven.quiltmc.org/repository/release/", "/maven/"},
            {"https://meta.fabricmc.net/", "/fabric-meta/"},
    };

    private final LauncherConfig.DownloadSource preferred;
    private final String customBase;
    private final List<LauncherConfig.DownloadSource> order;

    public MirrorResolver(LauncherConfig.DownloadSource preferred, String customBase) {
        this.preferred = preferred == null ? LauncherConfig.DownloadSource.BMCLAPI : preferred;
        this.customBase = normalizeBase(customBase);
        this.order = buildOrder(this.preferred, this.customBase != null);
    }

    /** 按当前全局配置构造。 */
    public static MirrorResolver of(LauncherConfig config) {
        LauncherConfig effective = config == null ? ConfigManager.get() : config;
        return new MirrorResolver(effective.downloadSource, effective.customSourceBase);
    }

    /** 只用官方源（单元测试与「离线自备资源」场景用）。 */
    public static MirrorResolver officialOnly() {
        return new MirrorResolver(LauncherConfig.DownloadSource.OFFICIAL, null);
    }

    public LauncherConfig.DownloadSource preferred() {
        return preferred;
    }

    public List<LauncherConfig.DownloadSource> order() {
        return order;
    }

    private static List<LauncherConfig.DownloadSource> buildOrder(LauncherConfig.DownloadSource preferred,
                                                                  boolean hasCustom) {
        List<LauncherConfig.DownloadSource> list = new ArrayList<>();
        switch (preferred) {
            case OFFICIAL -> list.add(LauncherConfig.DownloadSource.OFFICIAL);
            case BMCLAPI -> list.addAll(List.of(
                    LauncherConfig.DownloadSource.BMCLAPI,
                    LauncherConfig.DownloadSource.MCBBS,
                    LauncherConfig.DownloadSource.OFFICIAL));
            case MCBBS -> list.addAll(List.of(
                    LauncherConfig.DownloadSource.MCBBS,
                    LauncherConfig.DownloadSource.BMCLAPI,
                    LauncherConfig.DownloadSource.OFFICIAL));
            case CUSTOM -> {
                if (hasCustom) {
                    list.add(LauncherConfig.DownloadSource.CUSTOM);
                }
                list.addAll(List.of(
                        LauncherConfig.DownloadSource.BMCLAPI,
                        LauncherConfig.DownloadSource.MCBBS,
                        LauncherConfig.DownloadSource.OFFICIAL));
            }
        }
        return List.copyOf(list);
    }

    /** 版本清单的候选地址。 */
    public List<String> manifestCandidates() {
        return candidates(OFFICIAL_MANIFEST);
    }

    /**
     * 任意官方 URL 的候选地址（按 fallback 优先级）。
     * 已经指向镜像域名的 URL 原样返回，避免「镜像的镜像」。
     */
    public List<String> candidates(String officialUrl) {
        if (officialUrl == null || officialUrl.isBlank()) {
            return List.of();
        }
        if (isMirrorUrl(officialUrl)) {
            return List.of(officialUrl);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (LauncherConfig.DownloadSource source : order) {
            String url = translate(source, officialUrl);
            if (url != null && !url.isBlank()) {
                result.add(url);
            }
        }
        if (result.isEmpty()) {
            result.add(officialUrl);
        }
        return List.copyOf(result);
    }

    /** 把官方 URL 翻译成指定源的 URL；无法翻译返回 null。 */
    public String translate(LauncherConfig.DownloadSource source, String officialUrl) {
        if (source == LauncherConfig.DownloadSource.OFFICIAL) {
            return officialUrl;
        }
        String base = baseOf(source);
        if (base == null) {
            return null;
        }
        return rewrite(base, officialUrl);
    }

    /** 域名替换的核心：按官方域名的已知前缀拼出镜像地址。 */
    static String rewrite(String mirrorBase, String officialUrl) {
        if (mirrorBase == null || officialUrl == null) {
            return null;
        }
        String base = mirrorBase;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String normalized = officialUrl.trim();
        if (normalized.startsWith(base)) {
            return normalized;
        }
        for (String[] rule : PREFIX_RULES) {
            String officialPrefix = rule[0];
            if (normalized.startsWith(officialPrefix)) {
                String rest = normalized.substring(officialPrefix.length());
                return base + rule[1] + rest;
            }
        }
        return null;
    }

    public String baseOf(LauncherConfig.DownloadSource source) {
        return switch (source) {
            case OFFICIAL -> null;
            case BMCLAPI -> BMCLAPI_BASE;
            case MCBBS -> MCBBS_BASE;
            case CUSTOM -> customBase;
        };
    }

    private boolean isMirrorUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith(BMCLAPI_BASE) || lower.startsWith(MCBBS_BASE)) {
            return true;
        }
        return customBase != null && lower.startsWith(customBase.toLowerCase(Locale.ROOT));
    }

    /** 当前源的可读名称，用于日志与 UI。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (LauncherConfig.DownloadSource source : order) {
            if (sb.length() > 0) {
                sb.append(" → ");
            }
            sb.append(switch (source) {
                case OFFICIAL -> "官方";
                case BMCLAPI -> "BMCLAPI";
                case MCBBS -> "mcbbs";
                case CUSTOM -> "自定义";
            });
        }
        return sb.toString();
    }

    private static String normalizeBase(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String base = raw.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            Log.warn("Mirror", "自定义镜像地址不是 http(s)，已忽略: " + raw);
            return null;
        }
        return base;
    }
}
