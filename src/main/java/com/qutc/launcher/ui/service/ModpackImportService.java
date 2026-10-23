package com.qutc.launcher.ui.service;

import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.core.modpack.ModpackImporter;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.thread.Async;
import com.qutc.launcher.ui.components.Dialogs;
import com.qutc.launcher.utils.Log;

import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 整合包导入（Modrinth {@code .mrpack}）。
 *
 * <p>原先是「模组管理」页里的独立子页；模组管理并入版本设置后，
 * 导入动作因为不属于单个版本，挪到「版本管理」页工具栏。
 */
public final class ModpackImportService {

    private ModpackImportService() {
    }

    /**
     * 选文件 → 读 index → 确认 → 下载并解压成独立实例。
     *
     * @param status 进度文案回调（在 FX 线程调用）
     * @param onDone 导入成功后回调（用于刷新版本列表）
     */
    public static void chooseAndImport(Window owner, Consumer<String> status, Runnable onDone) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Modrinth 整合包");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Modrinth 整合包 (*.mrpack)", "*.mrpack"));
        java.io.File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        Path mrpack = file.toPath();
        status.accept("正在读取整合包信息…");
        Async.run(() -> {
            try {
                ModpackImporter importer = new ModpackImporter(MirrorResolver.of(ConfigManager.get()));
                ModpackImporter.Index index = importer.readIndex(mrpack);
                Async.ui(() -> {
                    boolean okay = Dialogs.confirm("导入整合包",
                            index.name() + "（MC " + index.minecraft() + "）",
                            "加载器: " + (index.loaderKind() == null ? "原版"
                                    : index.loaderKind() + " " + index.loaderVersion())
                                    + "\n\n导入会解压成一个独立版本实例，"
                                    + "需要下载整合包声明的全部文件。是否继续？", owner);
                    if (!okay) {
                        status.accept("已取消导入");
                        return;
                    }
                    status.accept("正在导入 " + index.name() + " …（下载中，请稍候）");
                    Async.run(() -> {
                        try {
                            ModpackImporter.Result result = importer.importMrpack(mrpack,
                                    snapshot -> Async.ui(() -> status.accept(String.format(Locale.ROOT,
                                            "下载整合包文件 %d/%d · %.1f MB / %.1f MB",
                                            snapshot.doneFiles(), snapshot.totalFiles(),
                                            snapshot.doneBytes() / 1024.0 / 1024.0,
                                            snapshot.totalBytes() / 1024.0 / 1024.0))));
                            Async.ui(() -> {
                                if (onDone != null) {
                                    onDone.run();
                                }
                                Dialogs.info("整合包导入完成", result.name(),
                                        "实例: " + result.versionId() + "\n游戏版本: " + result.gameVersion()
                                                + "\n加载器: " + result.loaderText()
                                                + "\n文件: " + result.files()
                                                + (result.failures().isEmpty() ? ""
                                                : "\n失败 " + result.failures().size() + " 项，启动时会重试"));
                            });
                        } catch (Exception error) {
                            Log.error("UI", "导入整合包失败", error);
                            Async.ui(() -> {
                                status.accept("导入失败");
                                Dialogs.error("导入失败", index.name(), String.valueOf(error.getMessage()));
                            });
                        }
                    });
                });
            } catch (Exception error) {
                Async.ui(() -> {
                    status.accept("读取失败");
                    Dialogs.error("读取整合包失败", mrpack.getFileName().toString(),
                            String.valueOf(error.getMessage()));
                });
            }
        });
    }
}
