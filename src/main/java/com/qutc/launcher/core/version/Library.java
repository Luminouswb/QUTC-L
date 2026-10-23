package com.qutc.launcher.core.version;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 依赖库条目（原版 + 加载器）。
 *
 * <p>{@code name} 遵循 Maven 坐标 {@code group:artifact:version[:classifier]}，
 * 这是去重与排序的唯一依据。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Library {

    public String name;

    /** 旧版本 JSON 里的仓库根地址，缺省为官方 libraries 域名。 */
    public String url;

    public List<Rule> rules;

    public Downloads downloads;

    /** 平台 → classifier，例如 {@code {"windows":"natives-windows"}}。 */
    public Map<String, String> natives;

    public Extract extract;

    /** 远古版本的字段，用于标记仅客户端/仅服务端需要。 */
    public Boolean clientreq;
    public Boolean serverreq;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Downloads {
        public Artifact artifact;
        public Map<String, Artifact> classifiers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Artifact {
        public String path;
        public String sha1;
        public String url;
        public long size = -1L;

        public boolean declared() {
            return (path != null && !path.isBlank()) || (url != null && !url.isBlank());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Extract {
        public List<String> exclude;
    }

    // ---- Maven 坐标解析 ----

    public String groupId() {
        String[] parts = splitName();
        return parts.length > 0 ? parts[0] : "";
    }

    public String artifactId() {
        String[] parts = splitName();
        return parts.length > 1 ? parts[1] : "";
    }

    public String version() {
        String[] parts = splitName();
        return parts.length > 2 ? parts[2] : "";
    }

    public String classifier() {
        String[] parts = splitName();
        return parts.length > 3 ? parts[3] : "";
    }

    /**
     * 去重键：group + artifact + classifier。
     * 刻意不含 version —— 子版本（加载器）对同名库的覆盖必须落回父版本的位置。
     */
    public String dedupeKey() {
        return groupId() + ':' + artifactId()
                + (classifier().isEmpty() ? "" : ':' + classifier());
    }

    /** 该库是否被当前环境接受。 */
    public boolean accepted(Rules.Context context) {
        return Rules.allows(rules, context);
    }

    /** 该库是否带当前平台的 native 包。 */
    public String nativeClassifier(Rules.Context context) {
        if (natives == null || natives.isEmpty()) {
            return null;
        }
        String base = natives.get(context.osName());
        if (base == null) {
            return null;
        }
        if ("arm64".equalsIgnoreCase(context.arch())) {
            String arm = base + "-arm64";
            if (downloads != null && downloads.classifiers != null && downloads.classifiers.containsKey(arm)) {
                return arm;
            }
        }
        return base;
    }

    /** 库的仓库相对路径，优先用 JSON 声明的 path。 */
    public String resolvePath(Artifact artifact) {
        if (artifact != null && artifact.path != null && !artifact.path.isBlank()) {
            return artifact.path.replace('\\', '/');
        }
        String[] parts = splitName();
        if (parts.length < 3) {
            return null;
        }
        String group = parts[0].replace('.', '/');
        String artifactId = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : "";
        String fileName = artifactId + '-' + version + (classifier.isEmpty() ? "" : '-' + classifier) + ".jar";
        return group + '/' + artifactId + '/' + version + '/' + fileName;
    }

    private String[] splitName() {
        if (name == null || name.isBlank()) {
            return new String[0];
        }
        return name.trim().split(":");
    }

    @Override
    public String toString() {
        return "Library{" + name + '}';
    }
}
