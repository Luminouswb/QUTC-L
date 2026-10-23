package com.qutc.launcher.core.external;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 引用式导入的版本登记表（{@code <root>/external-versions.json}）。
 *
 * <p>与「复制进启动器目录」不同，引用式导入<b>只记录来源目录</b>：
 * <ul>
 *   <li>{@code root} 是那个 {@code .minecraft}（或版本所在目录的父级）根；</li>
 *   <li>启动时从 {@code root/versions/<id>} 读版本 JSON / jar，
 *       从 {@code root/libraries}、{@code root/assets} 读依赖与资源；</li>
 *   <li>原目录被移动/删除后，版本会失效——UI 要显示为「来源不可用」。</li>
 * </ul>
 */
public final class ExternalVersions {

    /** 一条导入记录。 */
    public static final class Entry {
        public String versionId;
        public String root;
        public String importedAt;
    }

    /** 落盘结构。 */
    public static final class Store {
        public List<Entry> versions = new ArrayList<>();
    }

    private ExternalVersions() {
    }

    public static Path file() {
        return AppPaths.root().resolve("external-versions.json");
    }

    public static synchronized List<Entry> all() {
        Store store = JsonUtils.read(file(), Store.class, new Store());
        if (store == null || store.versions == null) {
            return List.of();
        }
        return List.copyOf(store.versions);
    }

    public static Optional<Entry> find(String versionId) {
        if (versionId == null) {
            return Optional.empty();
        }
        return all().stream().filter(entry -> versionId.equals(entry.versionId)).findFirst();
    }

    public static synchronized void add(String versionId, Path root) {
        Store store = loadStore();
        store.versions.removeIf(entry -> versionId.equals(entry.versionId));
        Entry entry = new Entry();
        entry.versionId = versionId;
        entry.root = root.toAbsolutePath().normalize().toString();
        entry.importedAt = Instant.now().toString();
        store.versions.add(entry);
        saveStore(store);
    }

    public static synchronized void remove(String versionId) {
        Store store = loadStore();
        if (store.versions.removeIf(entry -> versionId.equals(entry.versionId))) {
            saveStore(store);
        }
    }

    private static Store loadStore() {
        Store store = JsonUtils.read(file(), Store.class, new Store());
        if (store == null || store.versions == null) {
            store = new Store();
        }
        return store;
    }

    private static void saveStore(Store store) {
        try {
            JsonUtils.write(file(), store);
        } catch (IOException e) {
            Log.warn("External", "保存外部版本登记失败: " + e.getMessage());
        }
    }
}
