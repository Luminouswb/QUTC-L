package com.qutc.launcher.asset;

import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * 老版本资源布局适配。
 *
 * <p>1.7 及以前，游戏按<b>逻辑名</b>读取资源（{@code assets/minecraft/sounds/...}），
 * 而现代版本按 <b>hash 前缀目录</b> 存放。两种老布局：
 * <ul>
 *   <li>{@code virtual}：把 objects 按逻辑名复制到 {@code assets/virtual/<index>}，
 *       启动参数里 {@code --assetsDir} 指向它。</li>
 *   <li>{@code map_to_resources}（1.6 及更早）：复制到游戏目录的 {@code resources/}。</li>
 * </ul>
 */
public final class AssetLayout {

    private AssetLayout() {
    }

    /**
     * 铺设 virtual 目录。
     *
     * @return 实际复制/更新的文件数
     */
    public static int applyVirtual(Path assetsDir, AssetIndex index, Path targetVirtualDir) throws IOException {
        if (index == null || !index.virtual) {
            return 0;
        }
        int copied = 0;
        int skipped = 0;
        for (Map.Entry<String, String> entry : index.logicalToHash().entrySet()) {
            String logicalName = entry.getKey();
            String hash = entry.getValue();
            if (hash.length() < 2) {
                continue;
            }
            Path source = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.isRegularFile(source)) {
                skipped++;
                continue;
            }
            Path target = targetVirtualDir.resolve(logicalName);
            if (!FsUtils.isInside(targetVirtualDir, target)) {
                Log.warn("Assets", "跳过越界逻辑名: " + logicalName);
                continue;
            }
            if (Files.isRegularFile(target) && Files.size(target) == Files.size(source)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        Log.info("Assets", "virtual 目录铺设完成（索引 " + index.id + "）：新复制 " + copied
                + " 个，源缺失 " + skipped + " 个");
        return copied;
    }

    /**
     * 1.6 及更早的 {@code map_to_resources} 布局。
     */
    public static int applyMapToResources(Path assetsDir, Path gameDir, AssetIndex index) throws IOException {
        if (index == null || !index.mapToResources) {
            return 0;
        }
        Path resourcesDir = gameDir.resolve("resources");
        int copied = 0;
        for (Map.Entry<String, String> entry : index.logicalToHash().entrySet()) {
            String hash = entry.getValue();
            if (hash.length() < 2) {
                continue;
            }
            Path source = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.isRegularFile(source)) {
                continue;
            }
            Path target = resourcesDir.resolve(entry.getKey());
            if (!FsUtils.isInside(resourcesDir, target)) {
                continue;
            }
            if (Files.isRegularFile(target) && Files.size(target) == Files.size(source)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        Log.info("Assets", "map_to_resources 铺设完成：" + copied + " 个文件");
        return copied;
    }

    /** 老索引的启动参数 {@code --assetsDir} 需要指向 virtual 目录。 */
    public static Path assetRootFor(Path assetsDir, AssetIndex index, Path virtualDir) {
        return index != null && index.virtual ? virtualDir : assetsDir;
    }
}
