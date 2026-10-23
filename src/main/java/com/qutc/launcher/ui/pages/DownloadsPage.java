package com.qutc.launcher.ui.pages;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.config.VersionConfig;
import com.qutc.launcher.core.install.InstallEngine;
import com.qutc.launcher.core.modpack.ModpackImporter;
import com.qutc.launcher.core.modrinth.ModrinthClient;
import com.qutc.launcher.core.version.VersionManifest;
import com.qutc.launcher.core.version.VersionManifestService;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.thread.Async;
import com.qutc.launcher.ui.components.Dialogs;
import com.qutc.launcher.ui.service.ModpackImportService;
import com.qutc.launcher.ui.service.VersionFolderImporter;
import com.qutc.launcher.ui.service.VersionService;
import com.qutc.launcher.ui.theme.Ui;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 下载中心（阶段 8）：正式版 / 快照 / 历史版本全列表 + 搜索 + 一键安装。
 *
 * <p>下载走与命令行完全相同的 {@link InstallEngine}：镜像链、校验、natives 全部复用，
 * UI 只负责展示进度。
 */
public final class DownloadsPage implements Page {

    private static final String ALL = "全部";
    private static final String RELEASE = "正式版";
    private static final String SNAPSHOT = "快照";
    private static final String OLD = "历史（Alpha/Beta）";

    private final VBox root = new VBox(14);
    private final ListView<VersionManifest.Entry> list = new ListView<>();
    private final TextField search = new TextField();
    private final ChoiceBox<String> typeFilter = new ChoiceBox<>();
    private final Label counter = new Label();
    private final Label sourceLabel = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressText = new Label();
    private final Set<String> installed = new HashSet<>();
    private final VersionManifestService manifests;
    private final MirrorResolver mirrors;
    private final ModrinthClient modrinth = new ModrinthClient();
    private VBox versionsPane;
    private VBox modsPane;
    private VBox packsPane;
    private StackPane contentStack;
    private ChoiceBox<String> modTarget;

    public DownloadsPage() {
        this.mirrors = MirrorResolver.of(ConfigManager.get());
        this.manifests = new VersionManifestService(
                AppPaths.cacheDir().resolve("version_manifest_v2.json"), mirrors);
        build();
    }

    @Override
    public String id() {
        return "downloads";
    }

    @Override
    public String title() {
        return "下载中心";
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
        root.setPadding(new Insets(16, 20, 18, 20));

        Label title = new Label("下载中心");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("游戏版本 · Mod · 整合包 · 多源自动降级 · SHA-1 校验");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(2, title, subtitle);

        Button versionsTab = categoryButton("游戏版本");
        Button modsTab = categoryButton("Mod");
        Button packsTab = categoryButton("整合包");
        Label groupHeader = new Label("下载分类");
        groupHeader.getStyleClass().add("nav-footer");
        groupHeader.setPadding(new Insets(4, 8, 6, 10));
        VBox sidebar = new VBox(4, groupHeader, versionsTab, modsTab, packsTab);
        sidebar.getStyleClass().add("sub-nav");
        sidebar.setPrefWidth(148);
        sidebar.setMinWidth(148);
        sidebar.setMaxWidth(148);

        versionsPane = buildVersionsPane();
        modsPane = buildCommunityPane(ModrinthClient.Kind.MOD);
        packsPane = buildCommunityPane(ModrinthClient.Kind.MODPACK);
        contentStack = new StackPane(versionsPane, modsPane, packsPane);
        HBox.setHgrow(contentStack, Priority.ALWAYS);
        contentStack.setAlignment(Pos.TOP_LEFT);

        versionsTab.setOnAction(e -> selectCategory(versionsTab, versionsPane));
        modsTab.setOnAction(e -> selectCategory(modsTab, modsPane));
        packsTab.setOnAction(e -> selectCategory(packsTab, packsPane));

        HBox body = new HBox(16, sidebar, contentStack);
        VBox.setVgrow(body, Priority.ALWAYS);
        root.getChildren().addAll(heading, body);
        selectCategory(versionsTab, versionsPane);
    }

    private static Button categoryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("sub-nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        return button;
    }

    private void selectCategory(Button selected, VBox pane) {
        javafx.scene.Node parent = selected.getParent();
        if (parent instanceof VBox box) {
            for (javafx.scene.Node node : box.getChildren()) {
                if (node instanceof Button other) {
                    other.getStyleClass().remove("sub-nav-button-selected");
                }
            }
        }
        selected.getStyleClass().add("sub-nav-button-selected");
        for (VBox each : List.of(versionsPane, modsPane, packsPane)) {
            boolean show = each == pane;
            each.setVisible(show);
            each.setManaged(show);
        }
    }

    /** 游戏版本分类：原版全版本列表 + 搜索/筛选/安装 + 本地导入。 */
    private VBox buildVersionsPane() {
        sourceLabel.getStyleClass().add("version-meta");
        sourceLabel.setText("下载源: " + mirrors.describe());

        search.setPromptText("搜索版本号，例如 1.20.1 / 1.21");
        search.getStyleClass().add("text-field-dark");
        search.setPrefWidth(240);
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        typeFilter.getItems().addAll(ALL, RELEASE, SNAPSHOT, OLD);
        typeFilter.setValue(RELEASE);
        typeFilter.getStyleClass().add("choice-box-dark");
        typeFilter.valueProperty().addListener((observable, oldValue, newValue) -> applyFilter());

        Button refresh = new Button("刷新清单");
        refresh.getStyleClass().add("button-ghost");
        Ui.animate(refresh);
        refresh.setOnAction(e -> loadManifest(true));

        counter.getStyleClass().add("version-meta");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, search, typeFilter, spacer, counter, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // 本地导入：整合包（复制成实例）+ 版本文件夹（引用外部目录）
        Button importPack = new Button("导入整合包…");
        importPack.getStyleClass().add("button-ghost");
        Ui.animate(importPack);
        importPack.setOnAction(e -> ModpackImportService.chooseAndImport(window(), progressText::setText, () -> {
            scanInstalled();
            refresh();
        }));

        Button importFolder = new Button("导入版本文件夹…");
        importFolder.getStyleClass().add("button-ghost");
        Ui.animate(importFolder);
        importFolder.setOnAction(e -> importVersionFolder());

        Label importHint = new Label("支持整个 .minecraft 或单个 versions/<版本> 目录 · 引用方式，不复制文件");
        importHint.getStyleClass().add("version-meta");

        Region importSpacer = new Region();
        HBox.setHgrow(importSpacer, Priority.ALWAYS);
        HBox importRow = new HBox(10, importFolder, importPack, importSpacer, importHint);
        importRow.setAlignment(Pos.CENTER_LEFT);

        list.setCellFactory(view -> new VersionCell());
        list.setPlaceholder(new Label("正在获取版本清单…"));
        VBox.setVgrow(list, Priority.ALWAYS);

        progress.setVisible(false);
        progress.setMaxWidth(Double.MAX_VALUE);
        progressText.setVisible(false);
        progressText.getStyleClass().add("version-meta");
        VBox progressBox = new VBox(6, progressText, progress);

        return new VBox(10, sourceLabel, toolbar, importRow, list, progressBox);
    }


    // ---------- 社区资源：Mod / 整合包（Modrinth） ----------

    private VBox buildCommunityPane(ModrinthClient.Kind kind) {
        boolean isMod = kind == ModrinthClient.Kind.MOD;

        TextField query = new TextField();
        query.setPromptText(isMod ? "搜索模组：sodium / jei / iris" : "搜索整合包：Fabulously Optimized");
        query.getStyleClass().add("text-field-dark");
        query.setPrefWidth(250);

        ChoiceBox<String> gameVer = new ChoiceBox<>();
        gameVer.getItems().addAll("不限版本", "1.21.1", "1.20.6", "1.20.4", "1.20.1",
                "1.19.4", "1.18.2", "1.16.5", "1.12.2", "1.8.9", "1.7.10");
        gameVer.setValue("不限版本");
        gameVer.getStyleClass().add("choice-box-dark");
        gameVer.setPrefWidth(116);
        gameVer.setMinWidth(116);

        HBox toolbar = new HBox(8, query, gameVer);
        if (isMod) {
            ChoiceBox<String> loader = new ChoiceBox<>();
            loader.getItems().addAll("不限加载器", "Fabric", "Forge", "NeoForge", "Quilt");
            loader.setValue("不限加载器");
            loader.getStyleClass().add("choice-box-dark");
            loader.setUserData("loader");
            loader.setPrefWidth(126);
            loader.setMinWidth(126);
            modTarget = new ChoiceBox<>();
            modTarget.getStyleClass().add("choice-box-dark");
            modTarget.setPrefWidth(200);
            modTarget.setMinWidth(200);
            toolbar.getChildren().addAll(loader, modTarget);
            refreshModTargets();
        }

        Button doSearch = new Button("搜索");
        doSearch.getStyleClass().add("button-primary");
        doSearch.setStyle("-fx-padding: 6 16 6 16; -fx-font-size: 12px;");
        doSearch.setMinWidth(70);
        Ui.animate(doSearch);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolbar.getChildren().addAll(spacer, doSearch);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Label status = new Label(isMod ? "选择目标版本、输入关键词后搜索" : "输入关键词后搜索整合包");
        status.getStyleClass().add("version-meta");

        ListView<ModrinthClient.Project> results = new ListView<>();
        results.setPlaceholder(new Label(isMod ? "输入关键词搜索模组" : "输入关键词搜索整合包"));
        results.setCellFactory(v -> new ProjectCell(kind, status));
        VBox.setVgrow(results, Priority.ALWAYS);

        Runnable runSearch = () -> {
            String gv = "不限版本".equals(gameVer.getValue()) ? null : gameVer.getValue();
            String ld = null;
            for (javafx.scene.Node node : toolbar.getChildren()) {
                if (node instanceof ChoiceBox<?> box && "loader".equals(box.getUserData())) {
                    String value = (String) box.getValue();
                    if (value != null && !"不限加载器".equals(value)) {
                        ld = value.toLowerCase(Locale.ROOT);
                    }
                }
            }
            searchCommunity(kind, query.getText(), gv, ld, results, status);
        };
        doSearch.setOnAction(e -> runSearch.run());
        query.setOnAction(e -> runSearch.run());

        VBox pane = new VBox(10, toolbar, status, results);
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.setMaxHeight(Double.MAX_VALUE);
        return pane;
    }

    private void searchCommunity(ModrinthClient.Kind kind, String query, String gameVersion, String loader,
                                 ListView<ModrinthClient.Project> results, Label status) {
        status.setText("正在搜索…");
        Async.supply(() -> modrinth.search(kind, query, gameVersion, loader, 0, 30))
                .thenAccept(page -> Async.ui(() -> {
                    results.getItems().setAll(page.hits());
                    status.setText(page.totalHits() == 0
                            ? "没有找到结果"
                            : "共 " + page.totalHits() + " 个结果 · 显示前 " + page.hits().size() + " 个");
                })).exceptionally(error -> {
                    Log.error("UI", "Modrinth 搜索失败", error);
                    Async.ui(() -> status.setText("搜索失败：" + error.getMessage()));
                    return null;
                });
    }

    private void refreshModTargets() {
        if (modTarget == null) {
            return;
        }
        Async.supply(() -> new VersionService().scan()).thenAccept(entries -> Async.ui(() -> {
            String selected = modTarget.getValue();
            List<String> ids = entries.stream().filter(entry -> !entry.hidden())
                    .map(VersionService.Entry::versionId).toList();
            modTarget.getItems().setAll(ids);
            if (selected != null && ids.contains(selected)) {
                modTarget.setValue(selected);
            } else if (!ids.isEmpty()) {
                modTarget.setValue(ids.get(0));
            }
        }));
    }

    private String gameVersionOf(String versionId) {
        String current = versionId;
        for (int depth = 0; depth < 6; depth++) {
            Path json = AppPaths.versionJson(current);
            if (!Files.isRegularFile(json)) {
                return current;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode node = JsonUtils.readTree(json);
                String parent = node.path("inheritsFrom").asText(null);
                if (parent == null || parent.isBlank()) {
                    return node.path("id").asText(current);
                }
                current = parent;
            } catch (Exception error) {
                Log.warn("UI", "读取版本 JSON 失败: " + current + " | " + error.getMessage());
                return current;
            }
        }
        return current;
    }

    private static String loaderOf(String versionId) {
        String id = versionId.toLowerCase(Locale.ROOT);
        if (id.contains("neoforge")) {
            return "neoforge";
        }
        if (id.contains("forge")) {
            return "forge";
        }
        if (id.contains("quilt")) {
            return "quilt";
        }
        if (id.contains("fabric")) {
            return "fabric";
        }
        return null;
    }

    private void installMod(ModrinthClient.Project project, String versionId, Label status, Button trigger) {
        status.setText("正在查询 " + project.title() + " 的适配版本…");
        if (trigger != null) {
            trigger.setDisable(true);
        }
        Async.run(() -> {
            try {
                String gameVersion = gameVersionOf(versionId);
                String loader = loaderOf(versionId);
                List<ModrinthClient.Version> versions = modrinth.versions(project.id(), gameVersion, loader);
                ModrinthClient.Version pick = versions.stream().filter(ModrinthClient.Version::isRelease)
                        .findFirst().orElse(versions.isEmpty() ? null : versions.get(0));
                if (pick == null) {
                    throw new IOException("没有找到适配 " + gameVersion + " 的文件");
                }
                ModrinthClient.File file = pick.primaryFile();
                if (file == null) {
                    throw new IOException("该版本没有可下载文件");
                }
                VersionConfig config = VersionConfig.load(versionId);
                Path mods = AppPaths.modsDir(versionId, config.isolated);
                Files.createDirectories(mods);
                Path target = mods.resolve(file.filename());
                List<Downloader.Failure> failures = new Downloader().download(
                        List.of(Downloader.FileTask.of(file.url(), target, file.sha1(), file.size())), 2, null);
                if (!failures.isEmpty()) {
                    throw new IOException(failures.get(0).reason());
                }
                Async.ui(() -> {
                    status.setText("已安装 " + file.filename() + " → " + versionId + " 的 mods 目录");
                    if (trigger != null) {
                        trigger.setDisable(false);
                    }
                });
            } catch (Exception error) {
                Log.error("UI", "安装模组失败", error);
                Async.ui(() -> {
                    status.setText("安装失败：" + error.getMessage());
                    if (trigger != null) {
                        trigger.setDisable(false);
                    }
                    Dialogs.error("安装模组失败", project.title(), String.valueOf(error.getMessage()));
                });
            }
        });
    }

    private void installPack(ModrinthClient.Project project, Label status) {
        boolean ok = Dialogs.confirm("下载整合包", project.title(),
                "将从 Modrinth 下载整合包并解压成独立实例。\n\n" + project.description(), window());
        if (!ok) {
            return;
        }
        status.setText("正在查询整合包文件…");
        Async.run(() -> {
            try {
                List<ModrinthClient.Version> versions = modrinth.versions(project.id(), null, null);
                ModrinthClient.Version pick = versions.stream().filter(ModrinthClient.Version::isRelease)
                        .findFirst().orElse(versions.isEmpty() ? null : versions.get(0));
                if (pick == null) {
                    throw new IOException("没有找到可下载的整合包文件");
                }
                ModrinthClient.File file = pick.primaryFile();
                if (file == null) {
                    throw new IOException("该版本没有可下载文件");
                }
                Path dir = AppPaths.cacheDir().resolve("modpacks");
                Files.createDirectories(dir);
                Path target = dir.resolve(file.filename());
                Async.ui(() -> status.setText("正在下载整合包 " + file.filename() + " …"));
                List<Downloader.Failure> failures = new Downloader().download(
                        List.of(Downloader.FileTask.of(file.url(), target, file.sha1(), file.size())), 2, null);
                if (!failures.isEmpty()) {
                    throw new IOException(failures.get(0).reason());
                }
                ModpackImporter importer = new ModpackImporter(MirrorResolver.of(ConfigManager.get()));
                ModpackImporter.Result result = importer.importMrpack(target, snapshot -> Async.ui(() ->
                        status.setText(String.format(Locale.ROOT, "导入整合包 %d/%d · %.1f MB / %.1f MB",
                                snapshot.doneFiles(), snapshot.totalFiles(),
                                snapshot.doneBytes() / 1024.0 / 1024.0, snapshot.totalBytes() / 1024.0 / 1024.0))));
                Async.ui(() -> {
                    status.setText("整合包已导入：" + result.name() + "（实例 " + result.versionId() + "）");
                    scanInstalled();
                    refreshModTargets();
                    Dialogs.info("整合包下载完成", result.name(),
                            "实例: " + result.versionId() + "\n游戏版本: " + result.gameVersion()
                                    + "\n加载器: " + result.loaderText() + "\n文件: " + result.files()
                                    + (result.failures().isEmpty() ? ""
                                    : "\n失败 " + result.failures().size() + " 项，启动时会重试"));
                });
            } catch (Exception error) {
                Log.error("UI", "下载整合包失败", error);
                Async.ui(() -> {
                    status.setText("下载整合包失败：" + error.getMessage());
                    Dialogs.error("下载整合包失败", project.title(), String.valueOf(error.getMessage()));
                });
            }
        });
    }

    private final class ProjectCell extends ListCell<ModrinthClient.Project> {

        private final ModrinthClient.Kind kind;
        private final Label status;

        ProjectCell(ModrinthClient.Kind kind, Label status) {
            this.kind = kind;
            this.status = status;
        }

        @Override
        protected void updateItem(ModrinthClient.Project item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(item.title());
            name.getStyleClass().add("version-name");
            Label description = new Label(item.description());
            description.getStyleClass().add("version-meta");
            description.setWrapText(true);
            double width = getListView() == null || getListView().getWidth() < 200
                    ? 800 : getListView().getWidth() - 30;
            description.setMaxWidth(Math.max(240, width - 260));
            setPrefWidth(width);
            Label meta = new Label(String.format(Locale.ROOT, "作者 %s · 下载 %s 次 · %s",
                    item.author(), formatCount(item.downloads()),
                    kind == ModrinthClient.Kind.MODPACK ? "整合包" : "模组"));
            meta.getStyleClass().add("version-meta");

            Button install = new Button(kind == ModrinthClient.Kind.MODPACK ? "下载并导入" : "安装");
            install.getStyleClass().add("button-primary");
            install.setStyle("-fx-padding: 5 16 5 16; -fx-font-size: 12px;");
            Ui.animate(install);
            install.setOnAction(e -> {
                if (kind == ModrinthClient.Kind.MODPACK) {
                    installPack(item, status);
                    return;
                }
                String versionId = modTarget == null ? null : modTarget.getValue();
                if (versionId == null || versionId.isBlank()) {
                    Dialogs.warn("先选目标版本", "请选择要把模组装进哪个已安装版本",
                            "可以先去「版本管理」安装一个版本。");
                    return;
                }
                installMod(item, versionId, status, install);
            });

            VBox text = new VBox(4, name, description, meta);
            Region spacer = new Region();
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, text, install);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 6, 8, 6));
            setGraphic(row);
            setText(null);
        }
    }

    private static String formatCount(long value) {
        if (value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1f 万", value / 10_000.0);
        }
        if (value >= 10_000) {
            return String.format(Locale.ROOT, "%.1f 千", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    // ---------- 数据 ----------

    private void refresh() {
        if (installed.isEmpty()) {
            scanInstalled();
        }
        refreshModTargets();
        if (list.getItems().isEmpty()) {
            loadManifest(false);
        }
    }

    private void scanInstalled() {
        Async.run(() -> {
            Set<String> found = new HashSet<>();
            java.nio.file.Path versionsDir = AppPaths.versionsDir();
            if (Files.isDirectory(versionsDir)) {
                try (var stream = Files.list(versionsDir)) {
                    for (java.nio.file.Path dir : stream.filter(Files::isDirectory).toList()) {
                        String id = dir.getFileName().toString();
                        if (InstallEngine.readMarker(id) != null) {
                            found.add(id);
                        }
                    }
                } catch (IOException error) {
                    Log.warn("UI", "扫描已安装版本失败: " + error.getMessage());
                }
            }
            Async.ui(() -> {
                installed.clear();
                installed.addAll(found);
                list.refresh();
            });
        });
    }

    private void loadManifest(boolean force) {
        counter.setText("正在获取版本清单…");
        Async.supply(() -> manifests.get(force)).thenAccept(manifest -> Async.ui(() -> {
            allEntries = manifest.safeVersions();
            applyFilter();
        })).exceptionally(error -> {
            Log.error("UI", "获取版本清单失败", error);
            Async.ui(() -> {
                counter.setText("获取清单失败: " + error.getMessage());
                Dialogs.error("下载中心", "无法获取版本清单",
                        error.getMessage() + "\n\n可稍后点「刷新清单」重试。");
            });
            return null;
        });
    }

    private List<VersionManifest.Entry> allEntries = List.of();

    private void applyFilter() {
        String keyword = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        String type = typeFilter.getValue();
        List<VersionManifest.Entry> filtered = allEntries.stream()
                .filter(entry -> matchesType(entry, type))
                .filter(entry -> keyword.isEmpty()
                        || entry.id.toLowerCase(Locale.ROOT).contains(keyword))
                .toList();
        list.getItems().setAll(filtered);
        counter.setText(filtered.size() + " 个版本 · 已安装 " + installed.size());
        if (filtered.isEmpty()) {
            list.setPlaceholder(new Label("没有匹配的版本"));
        }
    }

    private static boolean matchesType(VersionManifest.Entry entry, String type) {
        if (type == null || ALL.equals(type)) {
            return true;
        }
        String raw = entry.type == null ? "" : entry.type;
        return switch (type) {
            case RELEASE -> "release".equals(raw);
            case SNAPSHOT -> "snapshot".equals(raw);
            default -> !"release".equals(raw) && !"snapshot".equals(raw);
        };
    }

    // ---------- 本地导入 ----------

    /** 选择 .minecraft 或单个版本目录，按「引用外部目录」方式登记。 */
    private void importVersionFolder() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("选择 .minecraft 或单个版本目录");
        java.io.File dir = chooser.showDialog(window());
        if (dir == null) {
            return;
        }
        Path chosen = dir.toPath();
        progressText.setVisible(true);
        progressText.setText("正在扫描 " + chosen + " …");
        Async.run(() -> {
            try {
                VersionFolderImporter.Plan plan = VersionFolderImporter.inspect(chosen);
                if (plan.empty()) {
                    Async.ui(() -> {
                        progressText.setVisible(false);
                        Dialogs.warn("没有可导入的版本", "目录里没有找到版本 JSON",
                                "请选择包含 versions/<版本>/<版本>.json 的 .minecraft 目录，"
                                        + "或某个具体的版本目录。");
                    });
                    return;
                }
                List<String> skipped = downloadedVersionIds();
                List<String> registered = VersionFolderImporter.register(plan, skipped);
                Async.ui(() -> {
                    progressText.setVisible(false);
                    scanInstalled();
                    refresh();
                    if (registered.isEmpty()) {
                        Dialogs.info("没有新增版本", "这些版本已经存在于启动器中",
                                "扫描到 " + plan.versionIds().size() + " 个版本，全部已存在。");
                    } else {
                        Dialogs.info("导入完成（引用）",
                                "已导入 " + registered.size() + " 个版本",
                                "来源: " + plan.root() + "\n"
                                        + String.join("\n", registered.subList(0, Math.min(6, registered.size())))
                                        + (registered.size() > 6 ? "\n…" : "")
                                        + "\n\n没有复制任何文件；原目录移动或删除后这些版本会失效。");
                    }
                });
            } catch (Exception error) {
                Log.error("UI", "导入版本目录失败", error);
                Async.ui(() -> {
                    progressText.setVisible(false);
                    Dialogs.error("导入失败", chosen.toString(), String.valueOf(error.getMessage()));
                });
            }
        });
    }

    /** 本启动器自己下载的版本 id（引用式导入时跳过，避免覆盖）。 */
    private static List<String> downloadedVersionIds() {
        Path versionsDir = AppPaths.versionsDir();
        if (!Files.isDirectory(versionsDir)) {
            return List.of();
        }
        try (var stream = Files.list(versionsDir)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(id -> Files.isRegularFile(versionsDir.resolve(id).resolve(id + ".json")))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private javafx.stage.Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    // ---------- 安装 ----------

    private void install(String versionId) {
        if (installed.contains(versionId)) {
            Dialogs.info("已安装", versionId, "这个版本已经完整安装，可以直接在版本管理里启动。");
            return;
        }
        progress.setVisible(true);
        progressText.setVisible(true);
        progress.setProgress(0);
        progressText.setText("准备安装 " + versionId + " …");

        Async.run(() -> {
            try {
                InstallEngine engine = new InstallEngine(AppPaths.versionsDir(),
                        new Downloader(), mirrors);
                InstallEngine.Result result = engine.install(versionId, InstallEngine.Options.defaults(),
                        snapshot -> Async.ui(() -> {
                            progress.setProgress(snapshot.percent() / 100.0);
                            progressText.setText(String.format(Locale.ROOT,
                                    "%s · %d/%d 文件 · %.1f MB / %.1f MB · %s",
                                    versionId, snapshot.doneFiles(), snapshot.totalFiles(),
                                    snapshot.doneBytes() / 1024.0 / 1024.0,
                                    snapshot.totalBytes() / 1024.0 / 1024.0,
                                    speedText(snapshot.bytesPerSecond())));
                        }));
                Async.ui(() -> {
                    progress.setVisible(false);
                    progressText.setVisible(false);
                    installed.add(versionId);
                    list.refresh();
                    applyFilter();
                    if (result.isClean()) {
                        Dialogs.info("安装完成", versionId,
                                "库 " + result.libraries() + " 个，natives " + result.nativeFiles()
                                        + " 个，资源 " + result.assetFiles() + " 个。\n"
                                        + "现在可以在「版本管理」里启动它。");
                    } else {
                        Dialogs.error("安装未完全成功", versionId,
                                result.failures().size() + " 个文件下载失败，首次启动会自动重试。\n"
                                        + result.failures().get(0).reason());
                    }
                });
            } catch (Exception error) {
                Log.error("UI", "安装版本失败", error);
                Async.ui(() -> {
                    progress.setVisible(false);
                    progressText.setVisible(false);
                    Dialogs.error("安装失败", versionId, String.valueOf(error.getMessage()));
                });
            }
        });
    }

    private static String speedText(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1024.0 / 1024.0);
    }

    private final class VersionCell extends ListCell<VersionManifest.Entry> {

        @Override
        protected void updateItem(VersionManifest.Entry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label name = new Label(item.id);
            name.getStyleClass().add("version-name");

            Label type = new Label(typeText(item));
            type.getStyleClass().addAll("badge", typeStyle(item));

            Label installedBadge = new Label(installed.contains(item.id) ? "已安装" : "未安装");
            installedBadge.getStyleClass().add("badge");

            HBox badges = new HBox(8, type, installedBadge);
            HBox nameRow = new HBox(10, name, badges);
            nameRow.setAlignment(Pos.CENTER_LEFT);

            Label meta = new Label((item.releaseTime == null ? "" : item.releaseTime.replace('T', ' '))
                    + (item.isRelease() ? " · 正式版" : ""));
            meta.getStyleClass().add("version-meta");

            VBox info = new VBox(3, nameRow, meta);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button action = new Button(installed.contains(item.id) ? "已安装" : "安装");
            action.getStyleClass().add(installed.contains(item.id) ? "button-ghost" : "button-primary");
            action.setDisable(installed.contains(item.id));
            action.setOnAction(e -> install(item.id));

            HBox cell = new HBox(12, info, spacer, action);
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.getStyleClass().add("version-card");
            setGraphic(cell);
            setText(null);
            setPadding(Insets.EMPTY);
        }
    }

    private static String typeText(VersionManifest.Entry entry) {
        String raw = entry.type == null ? "" : entry.type;
        return switch (raw) {
            case "release" -> "正式版";
            case "snapshot" -> "快照";
            case "old_beta" -> "旧 Beta";
            case "old_alpha" -> "旧 Alpha";
            default -> raw.isBlank() ? "未知" : raw;
        };
    }

    private static String typeStyle(VersionManifest.Entry entry) {
        String raw = entry.type == null ? "" : entry.type;
        return switch (raw) {
            case "release" -> "badge-fabric";
            case "snapshot" -> "badge-quilt";
            default -> "badge-vanilla";
        };
    }
}
