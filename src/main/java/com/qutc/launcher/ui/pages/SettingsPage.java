package com.qutc.launcher.ui.pages;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.config.LauncherConfig;
import com.qutc.launcher.core.java.JavaProbe;
import com.qutc.launcher.core.java.JavaRuntime;
import com.qutc.launcher.core.memory.MemoryAdvisor;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.thread.Async;
import com.qutc.launcher.ui.components.Dialogs;
import com.qutc.launcher.ui.theme.ThemeManager;
import com.qutc.launcher.ui.theme.Ui;
import com.qutc.launcher.update.UpdateService;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 9：全局设置。
 *
 * <p>覆盖总纲要求：Java 管理（自动扫描/手动指定）、智能内存三种模式、
 * 下载源与并行数、路径、启动参数、分辨率/全屏/无边框、主题与日志等级、缓存清理。
 * 所有修改点「保存设置」后原子落盘到 {@code launcher.json}。
 */
public final class SettingsPage implements Page {

    private final VBox content = new VBox(16);
    private final ScrollPane root = new ScrollPane(content);

    private final ComboBox<LauncherConfig.DownloadSource> sourceBox = new ComboBox<>();
    private final TextField customBaseField = new TextField();
    private final Spinner<Integer> parallelSpinner = new Spinner<>(1, 32, 8);
    private final CheckBox isolatedBox = new CheckBox("新安装的版本默认启用版本隔离");

    private final ComboBox<LauncherConfig.MemoryMode> memoryModeBox = new ComboBox<>();
    private final Slider memorySlider = new Slider(512, 32768, 4096);
    private final Label memoryValue = new Label("4.0 GB");
    private final Label memoryHint = new Label();

    private final CheckBox autoJavaBox = new CheckBox("自动为每个版本匹配 Java（推荐）");
    private final ComboBox<JavaRuntime> javaBox = new ComboBox<>();
    private final Label javaCount = new Label();

    private final TextField microsoftIdField = new TextField();
    private final TextField updateBaseField = new TextField();
    private final CheckBox autoUpdateBox = new CheckBox("启动时自动检查更新");

    private final Spinner<Integer> widthSpinner = new Spinner<>(320, 7680, 1280);
    private final Spinner<Integer> heightSpinner = new Spinner<>(240, 4320, 720);
    private final CheckBox fullscreenBox = new CheckBox("全屏启动");
    private final CheckBox borderlessBox = new CheckBox("无边框全屏（部分版本不支持）");

    private final CheckBox closeAfterStartBox = new CheckBox("游戏启动后关闭启动器");
    private final CheckBox showLogBox = new CheckBox("启动时显示日志窗口");
    private final ComboBox<String> logLevelBox = new ComboBox<>();
    private final ComboBox<String> themeBox = new ComboBox<>();
    private final javafx.scene.control.ColorPicker accentPicker = new javafx.scene.control.ColorPicker();
    private final ComboBox<String> backgroundModeBox = new ComboBox<>();
    private final Label backgroundStatus = new Label();

    private final Label dataDirLabel = new Label();
    private final Label cacheLabel = new Label();
    private final Label javaHint = new Label();

    public SettingsPage() {
        content.setPadding(new Insets(4, 6, 20, 0));
        content.getStyleClass().add("settings-content");

        root.setFitToWidth(true);
        root.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        root.setStyle("-fx-background-color: transparent;");

        memorySlider.setBlockIncrement(512);
        memorySlider.setMajorTickUnit(4096);
        memorySlider.setMinorTickCount(0);
        memorySlider.setShowTickMarks(false);
        memorySlider.valueProperty().addListener((obs, old, value) -> {
            double snapped = Math.round(value.doubleValue() / 512.0) * 512.0;
            memoryValue.setText(String.format("%.1f GB", snapped / 1024.0));
        });

        parallelSpinner.setEditable(true);
        widthSpinner.setEditable(true);
        heightSpinner.setEditable(true);

        sourceBox.setItems(FXCollections.observableArrayList(LauncherConfig.DownloadSource.values()));
        sourceBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(LauncherConfig.DownloadSource value) {
                return switch (value) {
                    case OFFICIAL -> "官方源（Mojang）";
                    case BMCLAPI -> "BMCLAPI 镜像（推荐）";
                    case MCBBS -> "MCBBS 镜像（已停服，仅兜底）";
                    case CUSTOM -> "自定义镜像";
                };
            }

            @Override
            public LauncherConfig.DownloadSource fromString(String text) {
                return LauncherConfig.DownloadSource.BMCLAPI;
            }
        });
        sourceBox.valueProperty().addListener((obs, old, value) -> updateCustomBaseVisibility());

        memoryModeBox.setItems(FXCollections.observableArrayList(LauncherConfig.MemoryMode.values()));
        memoryModeBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(LauncherConfig.MemoryMode mode) {
                return switch (mode) {
                    case AUTO -> "自动推荐（按模组/光影/内存）";
                    case GLOBAL -> "全局手写（所有版本统一）";
                    case PER_VERSION -> "单版本独立（版本设置里各自配置）";
                };
            }

            @Override
            public LauncherConfig.MemoryMode fromString(String text) {
                return LauncherConfig.MemoryMode.AUTO;
            }
        });

        logLevelBox.setItems(FXCollections.observableArrayList("DEBUG", "INFO", "WARN", "ERROR"));
        backgroundModeBox.setItems(FXCollections.observableArrayList("填充（铺满）", "完整显示（不裁）"));
        backgroundModeBox.setValue("填充（铺满）");
        backgroundModeBox.setPrefWidth(200);
        themeBox.setItems(FXCollections.observableArrayList("dark", "light"));
        themeBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String value) {
                return "light".equalsIgnoreCase(value) ? "浅色" : "暗色（默认）";
            }

            @Override
            public String fromString(String text) {
                return "dark";
            }
        });

        javaBox.setPrefWidth(520);
        javaBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(JavaRuntime runtime) {
                return runtime == null ? "" : runtime.displayName() + " · " + runtime.home();
            }

            @Override
            public JavaRuntime fromString(String text) {
                return null;
            }
        });

        Button saveButton = Ui.animate(primary("保存设置"));
        saveButton.setOnAction(event -> save());
        Button resetButton = Ui.animate(ghost("恢复默认"));
        resetButton.setOnAction(event -> {
            if (Dialogs.confirm("恢复默认设置", "将把 launcher.json 恢复为默认值，确定继续？", null, window())) {
                ConfigManager.update(config -> {
                    LauncherConfig defaults = LauncherConfig.defaults();
                    config.downloadSource = defaults.downloadSource;
                    config.customSourceBase = defaults.customSourceBase;
                    config.parallelDownloads = defaults.parallelDownloads;
                    config.isolatedByDefault = defaults.isolatedByDefault;
                    config.memoryMode = defaults.memoryMode;
                    config.globalMemoryMb = defaults.globalMemoryMb;
                    config.autoSelectJava = defaults.autoSelectJava;
                    config.javaPath = defaults.javaPath;
                    config.memoryMode = defaults.memoryMode;
                    config.resolutionWidth = defaults.resolutionWidth;
                    config.resolutionHeight = defaults.resolutionHeight;
                    config.fullscreen = defaults.fullscreen;
                    config.borderless = defaults.borderless;
                    config.closeLauncherAfterGameStart = defaults.closeLauncherAfterGameStart;
                    config.showLogWindow = defaults.showLogWindow;
                    config.logLevel = defaults.logLevel;
                });
                loadFromConfig();
                Dialogs.info("设置", "已恢复默认设置。", null);
            }
        });

        HBox actions = new HBox(10, saveButton, resetButton);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(4, 4, 8, 0));

        content.getChildren().addAll(
                header(),
                downloadCard(),
                memoryCard(),
                javaCard(),
                windowCard(),
                accountCard(),
                updateCard(),
                behaviorCard(),
                pathCard(),
                actions);
        loadFromConfig();
    }

    @Override
    public String id() {
        return "settings";
    }

    @Override
    public String title() {
        return "设置";
    }

    @Override
    public Node view() {
        return root;
    }

    @Override
    public void onShow() {
        loadFromConfig();
        root.setVvalue(0);
    }

    // ---- 布局 ----

    private Node header() {
        VBox box = new VBox(4);
        Label title = new Label("设置");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Java・内存・下载源・路径・启动参数，全部改动保存后为全局默认值");
        subtitle.getStyleClass().add("page-subtitle");
        box.getChildren().addAll(title, subtitle);
        return box;
    }

    private Node downloadCard() {
        customBaseField.setPromptText("例如 https://bmclapi2.bangbang93.com");
        customBaseField.setPrefWidth(420);
        return section("下载与安装",
                row("下载源", sourceBox, new Label("失败自动按 BMCLAPI → mcbbs → 官方 的顺序切换")),
                row("自定义镜像地址", customBaseField, new Label("仅下载源选「自定义镜像」时生效，按官方路径前缀替换")),
                row("并行下载数", parallelSpinner, new Label("1–32，机械盘建议 4–8，固态盘可 16")),
                row("", isolatedBox, new Label("版本隔离 = 每个版本独立的 mods / saves / config")),
                row(""),
                row("", new Label("提示：修改下载源后，重新安装或校验文件即可生效。"), null));
    }

    private Node memoryCard() {
        memoryHint.getStyleClass().add("muted");
        MemoryAdvisor.Recommendation recommendation = MemoryAdvisor.recommend(0, false);
        memoryHint.setText("本机物理内存 " + recommendation.physicalMb() + " MB，"
                + "上限建议 " + recommendation.cap() + " MB（物理内存 60%），"
                + "纯净端推荐 " + recommendation.recommendedMb() + " MB");
        VBox sliderBox = new VBox(6, memorySlider, memoryValue);
        HBox.setHgrow(sliderBox, Priority.ALWAYS);
        return section("内存",
                row("分配模式", memoryModeBox, new Label("自动推荐：按版本 mods 数与光影自动算；单版本独立：在版本设置里覆盖")),
                row("全局内存", sliderBox, memoryHint),
                row("", new Label("超过物理内存 60% 时启动前会给出警告；启动参数按 512 MB 对齐。"), null));
    }

    private Node javaCard() {
        javaCount.getStyleClass().add("muted");
        Button rescanButton = Ui.animate(ghost("重新扫描"));
        rescanButton.setOnAction(event -> rescanJava());
        Button addButton = Ui.animate(ghost("添加 Java 路径"));
        addButton.setOnAction(event -> addJavaPath());
        HBox javaActions = new HBox(8, rescanButton, addButton);
        javaHint.getStyleClass().add("muted");
        return section("Java 管理",
                row("自动匹配", autoJavaBox, new Label("关闭后所有版本都用下面选中的 Java")),
                row("默认 Java", javaBox, javaCount),
                row("", javaActions, javaHint));
    }

    private Node windowCard() {
        HBox resolution = new HBox(8, widthSpinner, new Label("×"), heightSpinner);
        resolution.setAlignment(Pos.CENTER_LEFT);
        return section("游戏窗口",
                row("默认分辨率", resolution, new Label("全屏启动时忽略此项")),
                row("", new VBox(8, fullscreenBox, borderlessBox), null));
    }

    private Node accountCard() {
        microsoftIdField.setPromptText("可留空：默认用内置微软客户端（无需注册 Azure 应用）");
        microsoftIdField.setPrefWidth(520);
        return section("账号",
                row("微软 Client ID（可选）", microsoftIdField,
                        new Label("留空 = 内置客户端（浏览器登录后粘贴回调地址）；填写自建 Azure 应用的 Client ID 则自动回调 http://localhost:25585/auth/callback")),
                row("", new Label("离线账号无需任何配置；多个账号的令牌使用 Windows DPAPI 加密保存在 accounts/。"), null));
    }

    private Node updateCard() {
        updateBaseField.setPromptText("例如 https://example.com/qutc/updates");
        updateBaseField.setPrefWidth(420);
        Button checkButton = Ui.animate(ghost("检查更新并应用"));
        checkButton.setOnAction(event -> applyUpdate());
        return section("更新",
                row("更新源", updateBaseField,
                        new Label("静态目录，里面放 files.json + 新版本 jar；留空则关闭在线更新")),
                row("", new HBox(10, autoUpdateBox, checkButton),
                        new Label("下载到 update/staging/，重启后替换；旧 jar 备份在 update/backup/ 可回滚")));
    }

    private void applyUpdate() {
        String base = updateBaseField.getText() == null ? "" : updateBaseField.getText().trim();
        Async.run(() -> {
            try {
                UpdateService.CheckResult result = UpdateService.check(base);
                Async.ui(() -> {
                    if (!result.available()) {
                        Dialogs.info("检查更新", result.message(), null);
                        return;
                    }
                    if (!Dialogs.confirm("发现新版本", result.remoteVersion(),
                            "立即下载并应用？应用会自动重启完成替换。", window())) {
                        return;
                    }
                    downloadAndApply(result);
                });
            } catch (Exception error) {
                Async.ui(() -> Dialogs.info("检查更新失败", String.valueOf(error.getMessage()), null));
            }
        });
    }

    private void downloadAndApply(UpdateService.CheckResult result) {
        Async.run(() -> {
            try {
                Path jar = UpdateService.download(new Downloader(), MirrorResolver.of(null), result.manifest());
                UpdateService.FileEntry entry = UpdateService.mainJar(result.manifest());
                if (!UpdateService.verify(jar, entry)) {
                    Async.ui(() -> Dialogs.info("更新失败", "下载的文件与 files.json 里的 sha256 不一致", null));
                    return;
                }
                Path pendingFile = UpdateService.prepare(jar, result.manifest());
                UpdateService.Pending pending = JsonUtils.read(pendingFile, UpdateService.Pending.class, null);
                if (pending == null) {
                    Async.ui(() -> Dialogs.info("更新失败", "pending.json 写入异常", null));
                    return;
                }
                UpdateService.spawnApplyScript(pending, restartCommand());
                Async.ui(() -> {
                    Dialogs.info("更新已就绪", "需要重启应用完成替换，点击确定后自动重启。", null);
                    javafx.application.Platform.exit();
                });
            } catch (Exception error) {
                Log.error("Settings", "更新失败", error);
                Async.ui(() -> Dialogs.info("更新失败", String.valueOf(error.getMessage()), null));
            }
        });
    }

    /** 打包后的可执行文件路径（jpackage 的 QUTC.exe）；开发态退化为 java。 */
    private String restartCommand() {
        return ProcessHandle.current().info().command().orElse("");
    }

    private Node behaviorCard() {
        Button pickBackground = Ui.animate(ghost("选择图片…"));
        pickBackground.setOnAction(event -> chooseBackground());
        Button clearBackground = Ui.animate(ghost("清除背景图"));
        clearBackground.setOnAction(event -> clearBackground());
        backgroundStatus.getStyleClass().add("muted");
        accentPicker.setPrefWidth(150);
        return section("行为与日志",
                row("启动后", closeAfterStartBox, null),
                row("日志", showLogBox, null),
                row("日志等级", logLevelBox, new Label("DEBUG 会记录完整下载 URL，排查镜像问题时用")),
                row("主题", themeBox, new Label("切换后立即生效并写入配置")),
                row("主题色", accentPicker, new Label("自定义强调色（按钮 / 高亮 / 进度条）")),
                row("主页背景", new HBox(10, pickBackground, clearBackground, backgroundStatus),
                        new Label("PNG / JPG / WEBP · 建议 3:2（如 1536×1024）")),
                row("背景方式", backgroundModeBox,
                        new Label("填充 = 铺满内容区、边缘裁切；完整显示 = 等比缩放、不裁边")));
    }

    private void chooseBackground() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择主页背景图");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                "图片 (*.png, *.jpg, *.jpeg, *.webp)", "*.png", "*.jpg", "*.jpeg", "*.webp"));
        java.io.File file = chooser.showOpenDialog(window());
        if (file == null) {
            return;
        }
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        String ext = name.endsWith(".jpg") || name.endsWith(".jpeg") ? ".jpg"
                : name.endsWith(".webp") ? ".webp" : ".png";
        try {
            java.nio.file.Path target = AppPaths.root().resolve("background" + ext);
            java.nio.file.Files.createDirectories(AppPaths.root());
            java.nio.file.Files.copy(file.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ConfigManager.update(config -> config.homeBackground = target.getFileName().toString());
            backgroundStatus.setText("当前：" + target.getFileName());
            Dialogs.info("背景图已设置", "主页背景已更新",
                    "等比缩放、完整显示，不会因为图片过大或过小而显示不全。\n下次进入主页即可看到。");
        } catch (Exception error) {
            Dialogs.error("设置背景图失败", file.getName(), String.valueOf(error.getMessage()));
        }
    }

    private void clearBackground() {
        LauncherConfig config = ConfigManager.get();
        if (config.homeBackground != null && !config.homeBackground.isBlank()) {
            try {
                java.nio.file.Files.deleteIfExists(AppPaths.root().resolve(config.homeBackground));
            } catch (Exception error) {
                com.qutc.launcher.utils.Log.warn("UI", "删除背景图失败: " + error.getMessage());
            }
        }
        ConfigManager.update(c -> c.homeBackground = null);
        backgroundStatus.setText("未设置");
    }

    private Node pathCard() {
        dataDirLabel.getStyleClass().add("muted");
        cacheLabel.getStyleClass().add("muted");
        Button openData = Ui.animate(ghost("打开数据目录"));
        openData.setOnAction(event -> openDir(AppPaths.root()));
        Button openVersions = Ui.animate(ghost("打开版本目录"));
        openVersions.setOnAction(event -> openDir(AppPaths.versionsDir()));
        Button openLogs = Ui.animate(ghost("打开日志目录"));
        openLogs.setOnAction(event -> openDir(AppPaths.logsDir()));
        Button clearCache = Ui.animate(danger("清理下载缓存"));
        clearCache.setOnAction(event -> clearCache());
        return section("路径与清理",
                row("数据目录", dataDirLabel, null),
                row("目录", new HBox(8, openData, openVersions, openLogs), null),
                row("缓存", new HBox(10, cacheLabel, clearCache), new Label("清理后已下载的库/资源会重新下载（不影响已安装版本）")));
    }

    private Node section(String title, Node... rows) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        card.getChildren().add(titleLabel);
        for (Node row : rows) {
            if (row != null) {
                card.getChildren().add(row);
            }
        }
        return card;
    }

    private Node row(String label, Node control, Node hint) {
        Label name = new Label(label);
        name.setMinWidth(110);
        name.setStyle("-fx-text-fill: #b8bfd6;");
        HBox box = new HBox(12, name, control);
        if (hint != null) {
            hint.getStyleClass().add("muted");
            box.getChildren().add(hint);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node row(String label) {
        return row(label, new Label(), null);
    }

    private Button primary(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-primary");
        return button;
    }

    private Button ghost(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-ghost");
        return button;
    }

    private Button danger(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-danger");
        return button;
    }

    // ---- 数据读写 ----

    private void updateCustomBaseVisibility() {
        boolean custom = sourceBox.getValue() == LauncherConfig.DownloadSource.CUSTOM;
        customBaseField.setDisable(!custom);
    }

    private void loadFromConfig() {
        LauncherConfig config = ConfigManager.get();
        sourceBox.setValue(config.downloadSource);
        customBaseField.setText(config.customSourceBase == null ? "" : config.customSourceBase);
        parallelSpinner.getValueFactory().setValue(config.parallelDownloads);
        isolatedBox.setSelected(config.isolatedByDefault);
        memoryModeBox.setValue(config.memoryMode);
        memorySlider.setValue(config.globalMemoryMb);
        memoryValue.setText(String.format("%.1f GB", config.globalMemoryMb / 1024.0));
        autoJavaBox.setSelected(config.autoSelectJava);
        microsoftIdField.setText(config.microsoftClientId == null ? "" : config.microsoftClientId);
        updateBaseField.setText(config.updateBaseUrl == null ? "" : config.updateBaseUrl);
        autoUpdateBox.setSelected(config.autoUpdate);
        widthSpinner.getValueFactory().setValue(config.resolutionWidth);
        heightSpinner.getValueFactory().setValue(config.resolutionHeight);
        fullscreenBox.setSelected(config.fullscreen);
        borderlessBox.setSelected(config.borderless);
        closeAfterStartBox.setSelected(config.closeLauncherAfterGameStart);
        showLogBox.setSelected(config.showLogWindow);
        logLevelBox.setValue(config.logLevel);
        themeBox.setValue("light".equalsIgnoreCase(config.theme) ? "light" : "dark");
        accentPicker.setValue(javafx.scene.paint.Color.web(ThemeManager.normalizeColor(config.themeColor)));
        backgroundStatus.setText(config.homeBackground == null || config.homeBackground.isBlank()
                ? "未设置" : "当前：" + config.homeBackground);
        backgroundModeBox.setValue("contain".equalsIgnoreCase(config.backgroundMode)
                ? "完整显示（不裁）" : "填充（铺满）");
        dataDirLabel.setText(AppPaths.root().toString());
        cacheLabel.setText("当前占用 " + formatSize(Downloader.sizeOf(AppPaths.cacheDir())));
        updateCustomBaseVisibility();
        rescanJava();
        updateDataDirHint(config);
    }

    private void updateDataDirHint(LauncherConfig config) {
        if (config.gameRoot != null && !config.gameRoot.isBlank()) {
            dataDirLabel.setText(AppPaths.root() + "（gameRoot 覆盖：" + config.gameRoot + "）");
        }
    }

    private void rescanJava() {
        List<JavaRuntime> runtimes = new ArrayList<>(JavaProbe.discover());
        javaBox.setItems(FXCollections.observableArrayList(runtimes));
        LauncherConfig config = ConfigManager.get();
        if (config.javaPath != null) {
            runtimes.stream()
                    .filter(runtime -> runtime.home().toString().equals(config.javaPath))
                    .findFirst()
                    .ifPresent(javaBox::setValue);
        } else if (!runtimes.isEmpty() && javaBox.getValue() == null) {
            JavaRuntime preferred = JavaProbe.best(runtimes, 21);
            javaBox.setValue(preferred == null ? runtimes.get(0) : preferred);
        }
        javaCount.setText("检测到 " + runtimes.size() + " 个运行时（已写入 cache/java-runtimes.json）");
        javaHint.setText(runtimes.isEmpty()
                ? "未检测到 Java，请先安装 JDK 21 或点「添加 Java 路径」手动指定"
                : "64 位优先；1.8–1.11 会自动选 Java 8，1.17+ 自动选 17/21");
    }

    private void addJavaPath() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择 Java 主目录（包含 bin/java.exe）");
        java.nio.file.Path start = java.nio.file.Path.of("C:\\Program Files\\Java");
        if (Files.isDirectory(start)) {
            chooser.setInitialDirectory(start.toFile());
        }
        java.io.File selected = chooser.showDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (selected == null) {
            return;
        }
        Path home = selected.toPath();
        JavaRuntime runtime = JavaProbe.probe(home, "手动添加");
        if (runtime == null) {
            Dialogs.info("添加 Java", "该目录下没有找到 bin/java.exe，请选择 JDK/JRE 主目录。", null);
            return;
        }
        ConfigManager.update(config -> {
            if (config.javaScanPaths == null) {
                config.javaScanPaths = new ArrayList<>();
            }
            config.javaScanPaths.add(home.toString());
            config.javaPath = home.toString();
            config.autoSelectJava = false;
        });
        loadFromConfig();
        Dialogs.info("添加 Java", "已添加并设为默认：" + runtime.displayName(), null);
    }

    private void openDir(Path dir) {
        try {
            FsUtils.ensureDir(dir);
            new ProcessBuilder("explorer.exe", dir.toString()).start();
        } catch (IOException error) {
            Log.warn("Settings", "打开目录失败: " + error.getMessage());
            Dialogs.info("打开目录", "打开失败：" + error.getMessage(), null);
        }
    }

    private void clearCache() {
        if (!Dialogs.confirm("清理下载缓存",
                "将删除 " + AppPaths.cacheDir() + " 下的全部内容（已安装的版本与存档不受影响）。确定继续？",
                null, window())) {
            return;
        }
        try {
            FsUtils.deleteRecursive(AppPaths.cacheDir());
            FsUtils.ensureDir(AppPaths.cacheDir());
            Log.info("Settings", "下载缓存已清理");
            cacheLabel.setText("当前占用 " + formatSize(Downloader.sizeOf(AppPaths.cacheDir())));
            Dialogs.info("清理缓存", "缓存已清空。", null);
        } catch (IOException error) {
            Dialogs.info("清理缓存", "清理失败：" + error.getMessage(), null);
        }
    }

    private void save() {
        int memoryMb = (int) Math.round(memorySlider.getValue() / 512.0) * 512;
        MemoryAdvisor.Recommendation recommendation = MemoryAdvisor.recommend(0, false);
        if (memoryMb > recommendation.cap()) {
            if (!Dialogs.confirm("内存超出建议上限",
                    "当前分配 " + memoryMb + " MB，超过物理内存 60%（" + recommendation.cap()
                            + " MB），可能导致系统卡顿。仍然保存？", null, window())) {
                return;
            }
        }
        LauncherConfig.DownloadSource source = sourceBox.getValue();
        String customBase = customBaseField.getText() == null ? "" : customBaseField.getText().trim();
        if (source == LauncherConfig.DownloadSource.CUSTOM && !customBase.startsWith("http")) {
            Dialogs.info("保存设置", "自定义镜像地址必须以 http:// 或 https:// 开头。", null);
            return;
        }
        ConfigManager.update(config -> {
            config.downloadSource = source;
            config.customSourceBase = customBase.isBlank() ? null : customBase;
            config.parallelDownloads = parallelSpinner.getValue();
            config.isolatedByDefault = isolatedBox.isSelected();
            config.memoryMode = memoryModeBox.getValue();
            config.globalMemoryMb = memoryMb;
            config.autoSelectJava = autoJavaBox.isSelected();
            JavaRuntime selectedJava = javaBox.getValue();
            config.javaPath = selectedJava == null ? null : selectedJava.home().toString();
            config.microsoftClientId = microsoftIdField.getText() == null || microsoftIdField.getText().isBlank()
                    ? null : microsoftIdField.getText().trim();
            config.updateBaseUrl = updateBaseField.getText() == null || updateBaseField.getText().isBlank()
                    ? null : updateBaseField.getText().trim();
            config.autoUpdate = autoUpdateBox.isSelected();
            config.resolutionWidth = widthSpinner.getValue();
            config.resolutionHeight = heightSpinner.getValue();
            config.fullscreen = fullscreenBox.isSelected();
            config.borderless = borderlessBox.isSelected();
            config.closeLauncherAfterGameStart = closeAfterStartBox.isSelected();
            config.showLogWindow = showLogBox.isSelected();
            config.logLevel = logLevelBox.getValue();
            config.theme = "light".equalsIgnoreCase(themeBox.getValue()) ? "light" : "dark";
            config.backgroundMode = "完整显示（不裁）".equals(backgroundModeBox.getValue()) ? "contain" : "cover";
            javafx.scene.paint.Color accent = accentPicker.getValue();
            config.themeColor = accent == null ? ThemeManager.DEFAULT_ACCENT
                    : String.format(java.util.Locale.ROOT, "#%02x%02x%02x",
                            Math.round(accent.getRed() * 255),
                            Math.round(accent.getGreen() * 255),
                            Math.round(accent.getBlue() * 255));
        });
        Log.info("Settings", "设置已保存：" + AppPaths.launcherConfig());
        Ui.reapplyTheme(root.getScene());
        cacheLabel.setText("当前占用 " + formatSize(Downloader.sizeOf(AppPaths.cacheDir())));
        Dialogs.info("保存设置", "已保存到 " + AppPaths.launcherConfig(), null);
    }

    private javafx.stage.Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }
}
