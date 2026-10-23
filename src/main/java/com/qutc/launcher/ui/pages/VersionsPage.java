package com.qutc.launcher.ui.pages;

import com.qutc.launcher.core.loader.LoaderKind;
import com.qutc.launcher.thread.Async;
import com.qutc.launcher.ui.components.Dialogs;
import com.qutc.launcher.ui.service.LaunchRunner;
import com.qutc.launcher.ui.service.VersionService;
import com.qutc.launcher.ui.theme.Ui;
import com.qutc.launcher.utils.Log;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 版本管理页：全盘本地版本、加载器识别、版本隔离开关、独立配置、备份导出删除。
 */
public final class VersionsPage implements Page {

    private final VBox root = new VBox(14);
    private final VersionService service = new VersionService();
    private final ListView<VersionService.Entry> list = new ListView<>();
    private final Label counter = new Label();
    private final CheckBox showHidden = new CheckBox("显示隐藏版本");
    private final Consumer<String> onLaunchRequested;

    public VersionsPage(Consumer<String> onLaunchRequested) {
        this.onLaunchRequested = onLaunchRequested;
        build();
    }

    @Override
    public String id() {
        return "versions";
    }

    @Override
    public String title() {
        return "版本管理";
    }

    @Override
    public javafx.scene.Node view() {
        return root;
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void build() {
        root.setPadding(new Insets(20, 26, 24, 26));

        Label title = new Label("版本管理");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("版本隔离默认开启；加载器与 OptiFine 自动识别");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(4, title, subtitle);

        counter.getStyleClass().add("version-meta");

        Button refresh = new Button("刷新");
        refresh.getStyleClass().add("button-ghost");
        Ui.animate(refresh);
        refresh.setOnAction(e -> refresh());

        showHidden.setOnAction(e -> refresh());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, counter, spacer, showHidden, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(view -> new VersionCell());
        list.setPlaceholder(new Label("还没有安装版本。"));
        VBox.setVgrow(list, Priority.ALWAYS);

        root.getChildren().addAll(heading, toolbar, list);
    }

    private void refresh() {
        Async.supply(service::scan).thenAccept(entries -> Async.ui(() -> {
            List<VersionService.Entry> visible = entries.stream()
                    .filter(entry -> showHidden.isSelected() || !entry.hidden())
                    .toList();
            list.getItems().setAll(visible);
            long installed = entries.stream().filter(VersionService.Entry::installed).count();
            counter.setText("共 " + entries.size() + " 个版本 · 已完整安装 " + installed);
        })).exceptionally(error -> {
            Log.error("UI", "扫描版本失败", error);
            Async.ui(() -> counter.setText("扫描失败: " + error.getMessage()));
            return null;
        });
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    // ---------- 行渲染 ----------

    private final class VersionCell extends ListCell<VersionService.Entry> {

        @Override
        protected void updateItem(VersionService.Entry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(item.displayName());
            name.getStyleClass().add("version-name");

            Label badge = new Label(badgeText(item));
            badge.getStyleClass().addAll("badge", badgeStyle(item));
            Label isolated = new Label(item.config().isolated ? "隔离" : "共用");
            isolated.getStyleClass().add("badge");
            Label hidden = new Label(item.hidden() ? "已隐藏" : null);
            hidden.getStyleClass().add("badge");
            Label source = new Label(item.sourceText());
            source.getStyleClass().addAll("badge", item.imported() ? "badge-imported" : "badge-local");
            if (item.imported() && !item.sourceAvailable()) {
                source.getStyleClass().add("badge-danger");
            }

            HBox badges = new HBox(8, badge, isolated, source);
            if (item.hidden()) {
                badges.getChildren().add(hidden);
            }
            HBox nameRow = new HBox(10, name, badges);
            nameRow.setAlignment(Pos.CENTER_LEFT);

            Label meta = new Label(item.subtitle()
                    + (item.installed() ? " · 已安装" : " · 未完整安装")
                    + (item.modCount() > 0 ? " · mods " + item.modCount() : ""));
            meta.getStyleClass().add("version-meta");

            Label path = new Label(item.imported()
                    ? "来源: " + item.externalRoot() + "\\versions\\" + item.versionId()
                    : item.dir().toString());
            path.getStyleClass().add("version-meta");
            path.setOpacity(0.65);

            Button launch = new Button("启动");
            launch.getStyleClass().add("button-primary");
            launch.setStyle("-fx-font-size: 12px; -fx-padding: 7 18 7 18;");
            launch.setOnAction(e -> launchVersion(item));

            Button settings = new Button("设置");
            settings.getStyleClass().add("button-ghost");
            settings.setOnAction(e -> VersionSettingsDialog.show(window(), item, this::refreshItem));

            Button more = new Button("⋯");
            more.getStyleClass().add("button-ghost");
            more.setOnAction(e -> showMoreMenu(more, item));

            HBox actions = new HBox(8, launch, settings, more);
            actions.setAlignment(Pos.CENTER_RIGHT);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            VBox info = new VBox(3, nameRow, meta, path);
            HBox cell = new HBox(12, info, spacer, actions);
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.getStyleClass().add("version-card");
            setGraphic(cell);
            setText(null);
            setPadding(Insets.EMPTY);
        }

        private void refreshItem() {
            refresh();
        }
    }

    private static String badgeText(VersionService.Entry entry) {
        LoaderKind kind = entry.loader();
        if (kind == null) {
            return "原版";
        }
        return switch (kind) {
            case FORGE -> "Forge";
            case NEOFORGE -> "NeoForge";
            case FABRIC -> "Fabric";
            case QUILT -> "Quilt";
            case OPTIFINE -> "OptiFine";
        };
    }

    private static String badgeStyle(VersionService.Entry entry) {
        LoaderKind kind = entry.loader();
        if (kind == null) {
            return "badge-vanilla";
        }
        return switch (kind) {
            case FORGE, NEOFORGE -> "badge-forge";
            case FABRIC -> "badge-fabric";
            case QUILT -> "badge-quilt";
            case OPTIFINE -> "badge-forge";
        };
    }

    // ---------- 操作 ----------

    private void launchVersion(VersionService.Entry entry) {
        if (onLaunchRequested != null) {
            onLaunchRequested.accept(entry.versionId());
            return;
        }
        LaunchRunner.launch(entry, new LaunchRunner.Listener() {
            @Override
            public void onStatus(String text) {
                counter.setText(text);
            }

            @Override
            public void onFinished(boolean success, String message) {
                counter.setText(message);
                if (!success) {
                    Dialogs.error("启动失败", "无法启动 " + entry.displayName(), message);
                }
            }
        });
    }

    private void showMoreMenu(Button anchor, VersionService.Entry entry) {
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem("重命名（只改显示名）");
        rename.setOnAction(e -> renameVersion(entry));
        MenuItem backup = new MenuItem("备份为 zip");
        backup.setOnAction(e -> backupVersion(entry));
        MenuItem export = new MenuItem("导出到…");
        export.setOnAction(e -> exportVersion(entry));
        MenuItem open = new MenuItem("打开版本目录");
        open.setOnAction(e -> service.openFolder(entry));
        MenuItem hide = new MenuItem(entry.hidden() ? "取消隐藏" : "隐藏（主页不显示）");
        hide.setOnAction(e -> toggleHidden(entry));
        MenuItem delete = new MenuItem("删除版本…");
        delete.setOnAction(e -> deleteVersion(entry));
        menu.getItems().addAll(rename, backup, export, open, new SeparatorMenuItem(), hide, delete);
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 4);
    }

    private void renameVersion(VersionService.Entry entry) {
        Dialogs.prompt("重命名版本", "只修改显示名，不会改动版本目录与加载器 id",
                entry.displayName(), window()).ifPresent(name -> {
            service.rename(entry, name);
            refresh();
        });
    }

    private void toggleHidden(VersionService.Entry entry) {
        service.setHidden(entry, !entry.hidden());
        refresh();
    }

    private void deleteVersion(VersionService.Entry entry) {
        boolean confirmed = Dialogs.confirm("删除版本",
                "确定删除 " + entry.displayName() + " 吗？",
                "目录 " + entry.dir() + " 将被永久删除（不会进回收站），存档也会一起消失。", window());
        if (!confirmed) {
            return;
        }
        Async.run(() -> {
            try {
                service.delete(entry);
                Async.ui(this::refresh);
            } catch (IOException error) {
                Async.ui(() -> Dialogs.error("删除失败", entry.displayName(), error.getMessage()));
            }
        });
    }

    private void backupVersion(VersionService.Entry entry) {
        counter.setText("正在备份 " + entry.displayName() + " …");
        Async.run(() -> {
            try {
                Path zip = service.backup(entry);
                Async.ui(() -> {
                    counter.setText("备份完成");
                    Dialogs.info("备份完成", entry.displayName(), "已保存到:\n" + zip);
                });
            } catch (IOException error) {
                Log.error("UI", "备份失败", error);
                Async.ui(() -> {
                    counter.setText("备份失败");
                    Dialogs.error("备份失败", entry.displayName(), error.getMessage());
                });
            }
        });
    }

    private void exportVersion(VersionService.Entry entry) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择导出目录");
        java.io.File dir = chooser.showDialog(window());
        if (dir == null) {
            return;
        }
        Path target = dir.toPath();
        counter.setText("正在导出 " + entry.displayName() + " …");
        Async.run(() -> {
            try {
                Path out = service.exportTo(entry, target);
                Async.ui(() -> {
                    counter.setText("导出完成");
                    Dialogs.info("导出完成", entry.displayName(), "已导出到:\n" + out);
                });
            } catch (IOException error) {
                Log.error("UI", "导出失败", error);
                Async.ui(() -> {
                    counter.setText("导出失败");
                    Dialogs.error("导出失败", entry.displayName(), error.getMessage());
                });
            }
        });
    }
}
