package com.qutc.launcher.core.version;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本 JSON 模型（原版 / 加载器 / 快照共用）。
 *
 * <p>加载器版本通过 {@code inheritsFrom} 指向父版本，本类只描述「单层」内容；
 * 合并后的完整视图由 {@link VersionResolver} 产出。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class VersionMeta {

    public String id;
    public String type;
    public String inheritsFrom;
    public String mainClass;
    public String jar;
    public String assets;
    public String minecraftArguments;

    @JsonProperty("assetIndex")
    public AssetIndexRef assetIndex;

    public List<Library> libraries;

    public VersionArguments arguments;

    @JsonProperty("javaVersion")
    public JavaVersionReq javaVersion;

    public int minimumLauncherVersion;

    public String releaseTime;
    public String time;

    public Logging logging;

    public Map<String, Library.Artifact> downloads;

    /** 该 JSON 所在的版本目录，解析器填充，供下载器定位。 */
    @JsonIgnore
    public String sourceId;

    public static VersionMeta empty() {
        return new VersionMeta();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class AssetIndexRef {
        public String id;
        public String sha1;
        public long size = -1L;
        public long totalSize = -1L;
        public String url;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class VersionArguments {
        public List<ArgumentEntry> game;
        public List<ArgumentEntry> jvm;

        public static VersionArguments empty() {
            VersionArguments value = new VersionArguments();
            value.game = new ArrayList<>();
            value.jvm = new ArrayList<>();
            return value;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class JavaVersionReq {
        public String component;
        public int majorVersion;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Logging {
        public Client client;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Client {
        public String argument;
        public FileInfo file;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FileInfo {
        public String id;
        public String sha1;
        public long size = -1L;
        public String url;
    }

    // ---- 便捷查询 ----

    @JsonIgnore
    public boolean usesModernArguments() {
        return arguments != null && ((arguments.game != null && !arguments.game.isEmpty())
                || (arguments.jvm != null && !arguments.jvm.isEmpty()));
    }

    @JsonIgnore
    public List<Library> safeLibraries() {
        return libraries == null ? List.of() : libraries;
    }

    /** 主类必备：缺失时说明版本 JSON 不完整。 */
    @JsonIgnore
    public boolean hasMainClass() {
        return mainClass != null && !mainClass.isBlank();
    }

    /** 一份空的合并结果，供解析器累加。 */
    public static VersionMeta beginning() {
        VersionMeta meta = new VersionMeta();
        meta.libraries = new ArrayList<>();
        meta.arguments = VersionArguments.empty();
        meta.downloads = new LinkedHashMap<>();
        return meta;
    }
}
