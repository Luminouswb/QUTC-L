package com.qutc.launcher.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资源索引（{@code assets/indexes/<id>.json}）。
 *
 * <p>老版本（1.6 及以前）用 {@code virtual}，资源要按逻辑名铺到 {@code assets/virtual/legacy}；
 * 1.7 时代还有 {@code map_to_resources}，要求映射进游戏目录的 {@code resources/}。
 * 这两种布局在新版本里已经消失，但总纲要求覆盖 1.8 ~ 最新，必须保留处理。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AssetIndex {

    public String id;

    public Map<String, AssetObject> objects;

    @JsonProperty("map_to_resources")
    public boolean mapToResources;

    /** 老版本需要虚拟目录结构。 */
    public boolean virtual;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class AssetObject {
        public String hash;
        public long size;

        /** 散列资源在 objects 下的相对路径：前两位做目录。 */
        public String objectPath() {
            if (hash == null || hash.length() < 2) {
                return null;
            }
            return hash.substring(0, 2) + '/' + hash;
        }
    }

    public Map<String, AssetObject> safeObjects() {
        return objects == null ? Map.of() : objects;
    }

    /** 统计总大小，用于进度条与磁盘预估。 */
    public long totalBytes() {
        long total = 0;
        for (AssetObject object : safeObjects().values()) {
            if (object != null && object.size > 0) {
                total += object.size;
            }
        }
        return total;
    }

    /** 生成逻辑名 → 散列 的快照，供虚拟目录铺设使用。 */
    public Map<String, String> logicalToHash() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, AssetObject> entry : safeObjects().entrySet()) {
            if (entry.getValue() != null && entry.getValue().hash != null) {
                map.put(entry.getKey(), entry.getValue().hash);
            }
        }
        return map;
    }
}
