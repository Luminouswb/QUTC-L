package com.qutc.launcher.ui.components;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.core.mods.ModScanner;
import com.qutc.launcher.core.mods.PackScanner;
import com.qutc.launcher.thread.Async;
import com.qutc.launcher.ui.service.VersionService;
import com.qutc.launcher.ui.theme.Ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 单个版本的模组 / 资源包 / 光影管理面板。
 *
 * <p>并入「版本管理 → 版本设置」对话框的左侧页签：
 * {@link #modsView()} / {@link #resourcePacksView()} / {@link #shaderPacksView()}，
 * 内容只作用于 {@link #setVersion(VersionService.Entry)} 传入的版本。
 */
public final class ModManagerPane {

    private final ModScanner modScanner = new ModScanner();
    private final PackScanner packScanner = new PackScanner();

    private final ListView<ModScanner.Mod> modList = new ListView<>();
    private final ListView<PackScanner.Pack> resourceList = new ListView<>();
    private final ListView<PackScanner.Pack> shaderList = new ListView<>();

    private final Label modStatus = new Label();
    private final Label resourceStatus = new Label();
    private final Label shaderStatus = new Label();

    private final VBox modsView;
    private final VBox resourceView;
    private final VBox shaderView;

    private VersionService.Entry entry;
    private ModScanner.Report report = new ModScanner.Report(List.of(), List.of());

    public ModManagerPane() {
        modList.setPlaceholder(new Label("这个版本还没有模组。"));
        resourceList.setPlaceholder(new Label("还没有资源包。"));
        shaderList.setPlaceholder(new Label("还没有光影包。"));
        modList.setCellFactory(view -> new ModCell());
        resourceList.setCellFactory(view -> new PackCell(PackScanner.Kind.RESOURCEPACK));
        shaderList.setCellFactory(view -> new PackCell(PackScanner.Kind.SHADER));

        modsView = packView(modList, modStatus, "打开模组目录");
        resourceView = packView(resourceList, resourceStatus, "打开资源包目录");
        shaderView = packView(shaderList, shaderStatus, "打开光影目录");
    }

    public Node modsView() {
        return modsView;
    }

    public Node resourcePacksView() {
        return resourceView;
    }

    public Node shaderPacksView() {
        return shaderView;
    }

    /** 切换到某个版本并加载内容。 */
    public void setVersion(VersionService.Entry entry) {
        this.entry = entry;
        reload();
    }

    public void reload() {
        VersionService.Entry current = entry;
        if (current == null) {
            modStatus.setText("没有可管理的版本");
            return;
        }
        modStatus.setText("正在扫描 " + current.displayName() + " …");
        Async.run(() -> {
            Path modsDir = AppPaths.modsDir(current.versionId(), current.config().isolated);
            Path resourceDir = AppPaths.resourcePacksDir(current.versionId(), current.config().isolated);
            Path shaderDir = AppPaths.shaderPacksDir(current.versionId(), current.config().isolated);
            ModScanner.Report scanned = modScanner.scan(modsDir);
            List<PackScanner.Pack> resourcePacks = packScanner.scan(resourceDir, PackScanner.Kind.RESOURCEPACK);
            List<PackScanner.Pack> shaderPacks = packScanner.scan(shaderDir, PackScanner.Kind.SHADER);
            Async.ui(() -> {
                report = scanned;
                modList.getItems().setAll(scanned.mods());
                resourceList.getItems().setAll(resourcePacks);
                shaderList.getItems().setAll(shaderPacks);
                long missing = scanned.mods().stream()
                        .mapToLong(mod -> scanned.missing(mod).size())
                        .sum();
                modStatus.setText(scanned.mods().size() + " 个模组（启用 " + scanned.enabled().size() + "）"
                        + (missing > 0 ? " · ⚠ 缺失前置 " + missing + " 项" : ""));
                resourceStatus.setText(resourcePacks.size() + " 个资源包");
                shaderStatus.setText(shaderPacks.size() + " 个光影包");
            });
        });
    }

    private VBox packView(Node list, Label status, String openLabel) {
        Button refresh = new Button("刷新");
        refresh.getStyleClass().add("button-ghost");
        Ui.animate(refresh);
        refresh.setOnAction(e -> reload());

        Button open = new Button(openLabel);
        open.getStyleClass().add("button-ghost");
        open.setOnAction(e -> openFolder());

        status.getStyleClass().add("version-meta");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, refresh, open, spacer, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, toolbar, list);
        box.setPadding(new Insets(10, 4, 4, 4));
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    private void openFolder() {
        VersionService.Entry current = entry;
        if (current == null) {
            return;
        }
        try {
            Path dir = AppPaths.modsDir(current.versionId(), current.config().isolated);
            Files.createDirectories(dir);
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception error) {
            Dialogs.error("打开目录失败", "无法打开目录", error.getMessage());
        }
    }

    private void toggleMod(ModScanner.Mod mod) {
        Async.run(() -> {
            try {
                modScanner.setEnabled(mod.jar(), !mod.enabled());
                Async.ui(this::reload);
            } catch (IOException error) {
                Async.ui(() -> Dialogs.error("操作失败", mod.name(), error.getMessage()));
            }
        });
    }

    private void togglePack(PackScanner.Pack pack) {
        Async.run(() -> {
            try {
                packScanner.setEnabled(pack, !pack.enabled());
                Async.ui(this::reload);
            } catch (IOException error) {
                Async.ui(() -> Dialogs.error("操作失败", pack.name(), error.getMessage()));
            }
        });
    }

    // ---------- 行渲染 ----------

    private final class ModCell extends ListCell<ModScanner.Mod> {

        @Override
        protected void updateItem(ModScanner.Mod item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(item.name());
            name.getStyleClass().add("version-name");

            Label version = new Label(item.displayVersion());
            version.getStyleClass().add("badge");

            Label loader = new Label(String.join(" / ", item.loaders()));
            loader.getStyleClass().addAll("badge", loaderStyle(item));

            Label state = new Label(item.enabled() ? "已启用" : "已禁用");
            state.getStyleClass().add("badge");

            HBox badges = new HBox(8, version, loader, state);
            badges.setAlignment(Pos.CENTER_LEFT);
            HBox nameRow = new HBox(10, name, badges);
            nameRow.setAlignment(Pos.CENTER_LEFT);

            Label meta = new Label("作者: " + item.authorText() + " · " + item.fileName());
            meta.getStyleClass().add("version-meta");

            List<ModScanner.Dependency> missing = report.missing(item);
            Label warn = new Label(missing.isEmpty() ? null
                    : "⚠ 缺少前置: " + missing.stream()
                    .map(dependency -> dependency.modId() + " " + dependency.range())
                    .reduce((a, b) -> a + ", " + b).orElse(""));
            warn.getStyleClass().add("version-meta");
            warn.setStyle("-fx-text-fill: #ffb86b;");

            VBox info = new VBox(3, nameRow, meta, warn);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button toggle = new Button(item.enabled() ? "禁用" : "启用");
            toggle.getStyleClass().add("button-ghost");
            toggle.setOnAction(e -> toggleMod(item));

            HBox cell = new HBox(12, info, spacer, toggle);
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.getStyleClass().add("version-card");
            setGraphic(cell);
            setText(null);
            setPadding(Insets.EMPTY);
        }

        private String loaderStyle(ModScanner.Mod item) {
            String loader = item.loaders().isEmpty() ? "" : item.loaders().get(0).toLowerCase(Locale.ROOT);
            return switch (loader) {
                case "fabric" -> "badge-fabric";
                case "quilt" -> "badge-quilt";
                case "forge", "neoforge" -> "badge-forge";
                default -> "badge-vanilla";
            };
        }
    }

    private final class PackCell extends ListCell<PackScanner.Pack> {

        private final PackScanner.Kind kind;

        private PackCell(PackScanner.Kind kind) {
            this.kind = kind;
        }

        @Override
        protected void updateItem(PackScanner.Pack item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(item.name());
            name.getStyleClass().add("version-name");

            Label format = new Label(item.formatText());
            format.getStyleClass().addAll("badge", kind == PackScanner.Kind.SHADER ? "badge-quilt" : "badge-fabric");

            Label state = new Label(item.enabled() ? "已启用" : "已禁用");
            state.getStyleClass().add("badge");

            HBox badges = new HBox(8, format, state);
            HBox nameRow = new HBox(10, name, badges);
            nameRow.setAlignment(Pos.CENTER_LEFT);

            Label meta = new Label(item.fileName()
                    + (item.sizeBytes() > 0 ? " · " + (item.sizeBytes() / 1024) + " KB" : ""));
            meta.getStyleClass().add("version-meta");

            VBox info = new VBox(3, nameRow, meta);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button toggle = new Button(item.enabled() ? "禁用" : "启用");
            toggle.getStyleClass().add("button-ghost");
            toggle.setOnAction(e -> togglePack(item));

            HBox cell = new HBox(12, info, spacer, toggle);
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.getStyleClass().add("version-card");
            setGraphic(cell);
            setText(null);
            setPadding(Insets.EMPTY);
        }
    }
}
