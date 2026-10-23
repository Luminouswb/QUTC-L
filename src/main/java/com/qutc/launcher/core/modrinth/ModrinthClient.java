package com.qutc.launcher.core.modrinth;

import com.fasterxml.jackson.databind.JsonNode;
import com.qutc.launcher.download.Http;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Modrinth 公开 API 客户端（阶段 8：下载中心 / 模组 / 光影 / 材质 / 整合包）。
 *
 * <p>只依赖官方 {@code api.modrinth.com/v2}：Modrinth 的内容 CDN 没有国内镜像，
 * 但接口体积小，真机可直连；下载大文件时由 {@link #fileUrls} 交给
 * {@link com.qutc.launcher.download.MirrorResolver} 的候选链（通常是唯一官方地址）。
 *
 * <p>API 文档：https://docs.modrinth.com/api/
 */
public final class ModrinthClient {

    public static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "QUTC-Launcher/0.1.0 (github.com/qutc/launcher)";

    /** 内容类型；{@code api} 就是 Modrinth 的 project_type。 */
    public enum Kind {
        MOD("mod", "模组"),
        RESOURCEPACK("resourcepack", "资源包"),
        SHADER("shader", "光影"),
        MODPACK("modpack", "整合包");

        private final String api;
        private final String label;

        Kind(String api, String label) {
            this.api = api;
            this.label = label;
        }

        public String api() {
            return api;
        }

        public String label() {
            return label;
        }

        public static Kind of(String value) {
            if (value == null) {
                return MOD;
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "resourcepack" -> RESOURCEPACK;
                case "shader", "shaders" -> SHADER;
                case "modpack", "modpacks" -> MODPACK;
                default -> MOD;
            };
        }
    }

    /** 搜索结果里的一个项目。 */
    public record Project(String id,
                          String slug,
                          String title,
                          String description,
                          String author,
                          Kind kind,
                          long downloads,
                          long follows,
                          String iconUrl) {

        public String pageUrl() {
            return "https://modrinth.com/" + kind.api() + "/" + slug;
        }
    }

    public record SearchPage(List<Project> hits, int totalHits, int offset, int limit) {
        public boolean hasMore() {
            return offset + hits.size() < totalHits;
        }
    }

    /** 版本里的一个可下载文件。 */
    public record File(String filename, String url, long size, String sha1, boolean primary) {
    }

    public record Version(String id,
                          String projectId,
                          String name,
                          String versionNumber,
                          String versionType,
                          List<String> gameVersions,
                          List<String> loaders,
                          List<File> files) {

        /** 主文件（发布者标记的 primary，否则第一个）。 */
        public File primaryFile() {
            for (File file : files) {
                if (file.primary()) {
                    return file;
                }
            }
            return files.isEmpty() ? null : files.get(0);
        }

        public boolean isRelease() {
            return "release".equalsIgnoreCase(versionType);
        }
    }

    // ---- 查询 ----

    /**
     * 搜索项目。
     *
     * @param gameVersion 为空表示不限
     * @param loader      为空表示不限（仅 mod 有意义）
     */
    public SearchPage search(Kind kind, String query, String gameVersion, String loader,
                             int offset, int limit) throws IOException {
        StringBuilder path = new StringBuilder("/search?limit=").append(clamp(limit, 1, 100))
                .append("&offset=").append(Math.max(0, offset));
        if (query != null && !query.isBlank()) {
            path.append("&query=").append(encode(query.trim()));
        }
        List<String> facets = new ArrayList<>();
        facets.add("[\"project_type:" + kind.api() + "\"]");
        if (gameVersion != null && !gameVersion.isBlank()) {
            facets.add("[\"versions:" + gameVersion.trim() + "\"]");
        }
        if (loader != null && !loader.isBlank() && kind == Kind.MOD) {
            facets.add("[\"categories:" + loader.trim().toLowerCase(Locale.ROOT) + "\"]");
        }
        path.append("&facets=").append(encode("[" + String.join(",", facets) + "]"));
        path.append("&index=relevance");

        JsonNode root = getJson(path.toString());
        List<Project> hits = new ArrayList<>();
        for (JsonNode node : root.path("hits")) {
            hits.add(new Project(
                    node.path("project_id").asText(""),
                    node.path("slug").asText(""),
                    node.path("title").asText("(未命名)"),
                    node.path("description").asText(""),
                    node.path("author").asText(""),
                    kind,
                    node.path("downloads").asLong(0),
                    node.path("follows").asLong(0),
                    node.path("icon_url").asText(null)));
        }
        return new SearchPage(hits, root.path("total_hits").asInt(hits.size()), Math.max(0, offset),
                clamp(limit, 1, 100));
    }

    /** 项目的全部版本（可按游戏版本 / 加载器过滤）。 */
    public List<Version> versions(String projectIdOrSlug, String gameVersion, String loader) throws IOException {
        StringBuilder path = new StringBuilder("/project/")
                .append(encode(projectIdOrSlug))
                .append("/version");
        String query = queryString(gameVersion, loader);
        if (!query.isEmpty()) {
            path.append('?').append(query);
        }
        JsonNode root = getJson(path.toString());
        List<Version> result = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                result.add(parseVersion(node));
            }
        }
        return result;
    }

    /** 单个项目详情（用于导入整合包时展示名称/图标）。 */
    public Project project(String idOrSlug) throws IOException {
        JsonNode node = getJson("/project/" + encode(idOrSlug));
        Kind kind = Kind.of(node.path("project_type").asText("mod"));
        return new Project(
                node.path("id").asText(""),
                node.path("slug").asText(""),
                node.path("title").asText("(未命名)"),
                node.path("description").asText(""),
                "",
                kind,
                node.path("downloads").asLong(0),
                node.path("followers").asLong(0),
                node.path("icon_url").asText(null));
    }

    /** CDN 地址列表（Modrinth 无镜像，只给官方地址，保持与下载器接口一致）。 */
    public static List<String> fileUrls(File file) {
        return file == null || file.url() == null || file.url().isBlank()
                ? List.of()
                : List.of(file.url());
    }

    // ---- 内部 ----

    private Version parseVersion(JsonNode node) {
        List<String> gameVersions = new ArrayList<>();
        for (JsonNode item : node.path("game_versions")) {
            gameVersions.add(item.asText());
        }
        List<String> loaders = new ArrayList<>();
        for (JsonNode item : node.path("loaders")) {
            loaders.add(item.asText());
        }
        List<File> files = new ArrayList<>();
        for (JsonNode item : node.path("files")) {
            files.add(new File(
                    item.path("filename").asText("download.jar"),
                    item.path("url").asText(""),
                    item.path("size").asLong(0),
                    item.path("hashes").path("sha1").asText(null),
                    item.path("primary").asBoolean(files.isEmpty())));
        }
        return new Version(
                node.path("id").asText(""),
                node.path("project_id").asText(""),
                node.path("name").asText(""),
                node.path("version_number").asText(""),
                node.path("version_type").asText("release"),
                gameVersions,
                loaders,
                files);
    }

    private JsonNode getJson(String pathAndQuery) throws IOException {
        String url = pathAndQuery.startsWith("http") ? pathAndQuery : API + pathAndQuery;
        byte[] body = Http.getBytes(List.of(url), 20, 40);
        Log.debug("Modrinth", "GET " + url + " → " + body.length + " 字节");
        return JsonUtils.parse(new String(body, StandardCharsets.UTF_8));
    }

    private static String queryString(String gameVersion, String loader) {
        StringBuilder sb = new StringBuilder();
        if (gameVersion != null && !gameVersion.isBlank()) {
            sb.append("game_versions=").append(encode("[\"" + gameVersion.trim() + "\"]"));
        }
        if (loader != null && !loader.isBlank()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append("loaders=").append(encode("[\"" + loader.trim().toLowerCase(Locale.ROOT) + "\"]"));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    /** 供 SelfTest 检查请求头。 */
    public static String userAgent() {
        return USER_AGENT;
    }
}
