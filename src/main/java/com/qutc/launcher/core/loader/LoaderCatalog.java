package com.qutc.launcher.core.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.qutc.launcher.download.Http;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加载器版本目录：四家加载器的「某游戏版本可用哪些加载器版本」。
 *
 * <p>数据来源与总纲「全加载器兼容矩阵」对应：
 * <ul>
 *   <li>Fabric / Quilt → 各自的 meta API（Fabric 走 BMCLAPI 的 {@code /fabric-meta/}）；</li>
 *   <li>Forge / NeoForge → Maven 的 {@code maven-metadata.xml}；</li>
 *   <li>OptiFine → 官方无公开元数据，必须用户手动下载安装包，这里只做识别。</li>
 * </ul>
 */
public final class LoaderCatalog {

    public static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader/";
    public static final String QUILT_META = "https://meta.quiltmc.org/v3/versions/loader/";
    public static final String FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    public static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";
    public static final String NEOFORGE_LEGACY_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/forge/";

    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    private final MirrorResolver mirrors;

    public LoaderCatalog(MirrorResolver mirrors) {
        this.mirrors = mirrors == null ? MirrorResolver.officialOnly() : mirrors;
    }

    /** 列出某游戏版本的加载器版本（新→旧）。找不到返回空列表，不抛异常。 */
    public List<LoaderVersion> list(LoaderKind kind, String gameVersion) {
        if (gameVersion == null || gameVersion.isBlank()) {
            return List.of();
        }
        try {
            return switch (kind) {
                case FABRIC -> fabricList(gameVersion);
                case QUILT -> quiltList(gameVersion);
                case FORGE -> forgeList(gameVersion);
                case NEOFORGE -> neoForgeList(gameVersion);
                case OPTIFINE -> {
                    Log.info("Loader", "OptiFine 没有公开的版本元数据，请从 optifine.net 手动下载安装器");
                    yield List.of();
                }
            };
        } catch (IOException | RuntimeException e) {
            Log.warn("Loader", "获取 " + kind.displayName() + " 版本列表失败: " + e.getMessage());
            return List.of();
        }
    }

    private List<LoaderVersion> fabricList(String game) throws IOException {
        JsonNode array = JsonUtils.parse(fetch(FABRIC_META + game));
        List<LoaderVersion> result = new ArrayList<>();
        for (JsonNode node : array) {
            JsonNode loader = node.path("loader");
            String version = loader.path("version").asText(null);
            if (version == null || version.isBlank()) {
                continue;
            }
            Boolean stable = loader.has("stable") ? loader.path("stable").asBoolean() : null;
            result.add(new LoaderVersion(LoaderKind.FABRIC, game, version,
                    "fabric-loader-" + version + '-' + game, stable));
        }
        return result;
    }

    private List<LoaderVersion> quiltList(String game) throws IOException {
        JsonNode array = JsonUtils.parse(fetch(QUILT_META + game));
        List<LoaderVersion> result = new ArrayList<>();
        for (JsonNode node : array) {
            JsonNode loader = node.path("loader");
            String version = loader.path("version").asText(null);
            if (version == null || version.isBlank()) {
                continue;
            }
            result.add(new LoaderVersion(LoaderKind.QUILT, game, version,
                    "quilt-loader-" + version + '-' + game, null));
        }
        return result;
    }

    private List<LoaderVersion> forgeList(String game) throws IOException {
        List<String> versions = mavenVersions(FORGE_MAVEN + "maven-metadata.xml");
        String prefix = game + '-';
        List<LoaderVersion> result = new ArrayList<>();
        for (String coordinate : versions) {
            if (!coordinate.startsWith(prefix)) {
                continue;
            }
            String loader = coordinate.substring(prefix.length());
            result.add(new LoaderVersion(LoaderKind.FORGE, game, loader,
                    game + "-forge-" + loader, null));
        }
        result.sort((a, b) -> compareVersionsDesc(a.loaderVersion(), b.loaderVersion()));
        return result;
    }

    private List<LoaderVersion> neoForgeList(String game) throws IOException {
        if ("1.20.1".equals(game)) {
            // NeoForge 1.20.1 走 net.neoforged:forge，坐标是 1.20.1-47.1.x
            List<String> versions = mavenVersions(NEOFORGE_LEGACY_MAVEN + "maven-metadata.xml");
            String prefix = game + '-';
            List<LoaderVersion> result = new ArrayList<>();
            for (String coordinate : versions) {
                if (!coordinate.startsWith(prefix)) {
                    continue;
                }
                String loader = coordinate.substring(prefix.length());
                result.add(new LoaderVersion(LoaderKind.NEOFORGE, game, loader,
                        game + "-neoforge-" + loader, null));
            }
            result.sort((a, b) -> compareVersionsDesc(a.loaderVersion(), b.loaderVersion()));
            return result;
        }
        List<String> versions = mavenVersions(NEOFORGE_MAVEN + "maven-metadata.xml");
        String prefix = neoForgePrefix(game);
        List<LoaderVersion> result = new ArrayList<>();
        for (String version : versions) {
            if (prefix != null && !version.startsWith(prefix)) {
                continue;
            }
            result.add(new LoaderVersion(LoaderKind.NEOFORGE, game, version,
                    "neoforge-" + version, null));
        }
        if (result.isEmpty() && prefix != null) {
            // 版本号规则对不上（例如快照/新分支）时退回全部，让用户自己挑
            for (String version : versions) {
                result.add(new LoaderVersion(LoaderKind.NEOFORGE, game, version,
                        "neoforge-" + version, null));
            }
        }
        result.sort((a, b) -> compareVersionsDesc(a.loaderVersion(), b.loaderVersion()));
        return result;
    }

    /**
     * MC 1.21.1 → NeoForge 21.1.x；1.21 → 21.0.x；1.20.4 → 20.4.x。
     * 无法推导时返回 null（表示不过滤）。
     */
    static String neoForgePrefix(String game) {
        if (game == null || !game.startsWith("1.")) {
            return null;
        }
        String rest = game.substring(2);
        String[] parts = rest.split("\\.");
        if (parts.length == 1) {
            return parts[0] + ".0.";
        }
        if (parts.length >= 2) {
            return parts[0] + '.' + parts[1] + '.';
        }
        return null;
    }

    /** 读取 Maven 元数据里的全部 {@code <version>}。 */
    List<String> mavenVersions(String metadataUrl) throws IOException {
        String xml = fetch(metadataUrl);
        List<String> versions = new ArrayList<>();
        Matcher matcher = VERSION_TAG.matcher(xml);
        while (matcher.find()) {
            versions.add(matcher.group(1).trim());
        }
        return versions;
    }

    private String fetch(String officialUrl) throws IOException {
        List<String> candidates = mirrors.candidates(officialUrl);
        return Http.getString(candidates, 30, 60);
    }

    /** 数字感知的降序比较（21.1.72 > 21.0.7；带 -beta 的排在正式版之后）。 */
    static int compareVersionsDesc(String left, String right) {
        String[] a = left.split("[.-]");
        String[] b = right.split("[.-]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            if (i >= a.length) {
                // 左侧是右侧的前缀：右侧多出的段是数字 → 右侧更新；是 beta/rc → 右侧是预发布，左侧更新
                return parseNumber(b[i]) != null ? 1 : -1;
            }
            if (i >= b.length) {
                return parseNumber(a[i]) != null ? -1 : 1;
            }
            int cmp = compareSegmentDesc(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int compareSegmentDesc(String a, String b) {
        Long na = parseNumber(a);
        Long nb = parseNumber(b);
        if (na != null && nb != null) {
            return Long.compare(nb, na);
        }
        if (na != null) {
            return -1;
        }
        if (nb != null) {
            return 1;
        }
        return b.compareToIgnoreCase(a);
    }

    private static Long parseNumber(String segment) {
        if (segment.isEmpty() || !segment.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(segment);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 便捷：只要最新稳定版。 */
    public LoaderVersion latestStable(LoaderKind kind, String gameVersion) {
        List<LoaderVersion> all = list(kind, gameVersion);
        for (LoaderVersion version : all) {
            if (Boolean.TRUE.equals(version.stable())) {
                return version;
            }
        }
        return all.isEmpty() ? null : all.get(0);
    }

    /** 由版本 id 猜加载器类型（供启动器展示）。 */
    public static LoaderKind kindOfVersionId(String versionId) {
        if (versionId == null) {
            return null;
        }
        String lower = versionId.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) {
            return LoaderKind.NEOFORGE;
        }
        if (lower.contains("fabric")) {
            return LoaderKind.FABRIC;
        }
        if (lower.contains("quilt")) {
            return LoaderKind.QUILT;
        }
        if (lower.contains("forge")) {
            return LoaderKind.FORGE;
        }
        if (lower.contains("optifine")) {
            return LoaderKind.OPTIFINE;
        }
        return null;
    }
}
