package com.qutc.launcher.ui.service;

import com.qutc.launcher.core.external.ExternalVersions;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 引用式导入本地版本目录。
 *
 * <p>支持两种选择：
 * <ul>
 *   <li>整个 {@code .minecraft} 目录（含 {@code versions/}）→ 扫描其中所有版本；</li>
 *   <li>单个版本目录（{@code versions/<id>/}，内含 {@code <id>.json}）→ 只导入这一个。</li>
 * </ul>
 * 只写 {@code external-versions.json} 登记，<b>不复制任何文件</b>。
 */
public final class VersionFolderImporter {

    /** 解析结果：来源根目录 + 可导入的版本 id。 */
    public record Plan(Path root, List<String> versionIds) {
        public boolean empty() {
            return versionIds.isEmpty();
        }
    }

    private VersionFolderImporter() {
    }

    /**
     * 识别用户选的目录。
     *
     * @throws IOException 目录无法读取，或不是可识别的版本目录
     */
    public static Plan inspect(Path chosen) throws IOException {
        if (chosen == null || !Files.isDirectory(chosen)) {
            throw new IOException("不是有效目录: " + chosen);
        }
        // 1) 选的是 .minecraft（里面有 versions/）
        Path versions = chosen.resolve("versions");
        if (Files.isDirectory(versions)) {
            return new Plan(chosen, scanVersionDirs(versions));
        }
        // 2) 选的是 versions/ 目录本身
        if ("versions".equals(name(chosen)) && Files.isDirectory(chosen)) {
            Path root = chosen.getParent();
            if (root != null) {
                return new Plan(root, scanVersionDirs(chosen));
            }
        }
        // 3) 选的是单个版本目录
        String dirName = name(chosen);
        Path ownJson = chosen.resolve(dirName + ".json");
        if (Files.isRegularFile(ownJson)) {
            Path parent = chosen.getParent();
            Path root = parent == null ? null : parent.getParent();
            if (parent == null || !"versions".equals(name(parent)) || root == null) {
                throw new IOException("版本目录不在 versions/ 下，无法确定来源根目录: " + chosen);
            }
            return new Plan(root, List.of(dirName));
        }
        throw new IOException("目录里没有 versions/ 也没有版本 JSON: " + chosen);
    }

    /**
     * 写入登记表。
     *
     * @param skipIds 已经存在（本启动器下载）的版本 id，跳过避免覆盖
     * @return 实际登记成功的 id
     */
    public static List<String> register(Plan plan, List<String> skipIds) {
        List<String> registered = new ArrayList<>();
        for (String id : plan.versionIds()) {
            if (skipIds != null && skipIds.contains(id)) {
                continue;
            }
            ExternalVersions.add(id, plan.root());
            registered.add(id);
        }
        Log.info("Import", "引用式导入 " + registered.size() + " 个版本，来源 " + plan.root());
        return registered;
    }

    private static List<String> scanVersionDirs(Path versionsDir) throws IOException {
        List<String> ids = new ArrayList<>();
        try (var stream = Files.list(versionsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String id = name(dir);
                if (Files.isRegularFile(dir.resolve(id + ".json"))) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static String name(Path path) {
        return path.getFileName() == null ? "" : path.getFileName().toString();
    }
}
