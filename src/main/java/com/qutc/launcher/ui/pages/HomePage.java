package com.qutc.launcher.ui.pages;

import com.qutc.launcher.auth.Account;
import com.qutc.launcher.auth.AuthException;
import com.qutc.launcher.auth.AuthService;
import com.qutc.launcher.auth.Credentials;
import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.config.LauncherConfig;
import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.config.VersionConfig;
import com.qutc.launcher.core.diagnose.CrashAnalyzer;
import com.qutc.launcher.core.java.JavaStatus;
import com.qutc.launcher.core.loader.LoaderKind;
import com.qutc.launcher.core.launch.GameProcess;
import com.qutc.launcher.core.launch.LaunchService;
import com.qutc.launcher.core.memory.MemoryAdvisor;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.thread.Async;
import com.qutc.launcher.ui.components.AccountMenuPopup;
import com.qutc.launcher.ui.components.Dialogs;
import com.qutc.launcher.ui.components.GameLogWindow;
import com.qutc.launcher.ui.service.VersionService;
import com.qutc.launcher.ui.service.SkinService;
import com.qutc.launcher.utils.HashUtils;
import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.OsUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 主页：快速启动、版本选择、智能内存、Java 状态、账号快速切换。
 */
public final class HomePage implements Page {

    private final javafx.scene.layout.StackPane root = new javafx.scene.layout.StackPane();
    private final javafx.scene.layout.Region bgLayer = new javafx.scene.layout.Region();
    private final javafx.scene.layout.Region bgDim = new javafx.scene.layout.Region();
    private final VBox page = new VBox(14);
    private final VersionService versionService = new VersionService();

    private final ComboBox<VersionService.Entry> versionBox = new ComboBox<>();
    private final Button playButton = new Button("开始游戏");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label statusLine = new Label("就绪");
    private final Label versionHint = new Label();

    private final Label memoryValue = new Label("—");
    private final Label memoryHint = new Label();
    private final ProgressBar memoryBar = new ProgressBar(0);

    private final Label javaValue = new Label("—");
    private final Label javaHint = new Label();

    private final StackPane avatar = new StackPane();
    private final Label avatarText = new Label("?");
    private final com.qutc.launcher.ui.components.SkinHeadView headView =
            new com.qutc.launcher.ui.components.SkinHeadView(34, 9);
    private volatile Account currentAccount;
    private final Label accountName = new Label("未登录");
    private final Label accountType = new Label("点击切换账号");
    private final HBox accountChip = new HBox(10);

    private final Label isolationBadge = new Label("版本隔离：开");
    private final Label infoLoader = new Label("—");
    private final Label infoIsolation = new Label("—");
    private final Label infoMods = new Label("—");
    private final Label infoSource = new Label("—");

    private AuthService auth = AuthService.defaults();
    private volatile boolean busy;
    private volatile GameProcess runningGame;
    private MemoryAdvisor.Recommendation lastRecommendation;

    public HomePage() {
        build();
    }

    @Override
    public String id() {
        return "home";
    }

    @Override
    public String title() {
        return "主页";
    }

    @Override
    public javafx.scene.Node view() {
        return root;
    }

    @Override
    public void onShow() {
        applyBackground();
        refreshVersions();
        refreshAccountChip();
    }

    // ---------- 布局 ----------

    private void build() {
        page.setPadding(new Insets(16, 20, 18, 20));

        Label title = new Label("开始你的冒险");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("选择版本，一键启动");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(2, title, subtitle);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        buildAccountChip();
        HBox header = new HBox(14, heading, headerSpacer, accountChip);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox playCard = buildPlayCard();

        VBox side = new VBox(10, buildMemoryCard(), buildJavaCard());
        side.setPrefWidth(280);
        side.setMinWidth(260);
        side.setMaxWidth(280);
        HBox.setHgrow(side, Priority.NEVER);

        HBox content = new HBox(12, playCard, side);
        HBox.setHgrow(playCard, Priority.ALWAYS);
        content.setAlignment(Pos.TOP_LEFT);

        VBox column = new VBox(14, header, content);
        column.setMaxWidth(1040);
        column.setMaxHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(column, Priority.ALWAYS);

        HBox centered = new HBox(column);
        centered.setAlignment(Pos.CENTER);
        HBox.setHgrow(column, Priority.ALWAYS);
        VBox.setVgrow(centered, Priority.ALWAYS);

        page.getChildren().add(centered);

        // 背景层（等比缩放、完整显示）+ 压暗层 + 内容层
        bgLayer.setMouseTransparent(true);
        bgDim.setMouseTransparent(true);
        bgDim.setStyle("-fx-background-color: linear-gradient(to bottom,"
                + " rgba(0, 0, 0, 0.55) 0%, rgba(0, 0, 0, 0.18) 45%, rgba(0, 0, 0, 0.42) 100%);");
        root.getChildren().setAll(bgLayer, bgDim, page);
        applyBackground();
    }

    /** 按配置加载主页背景图：等比缩放铺满内容区（cover），不拉伸、不变形。 */
    private void applyBackground() {
        LauncherConfig config = ConfigManager.get();
        String name = config.homeBackground;
        if (name == null || name.isBlank()) {
            bgLayer.setBackground(null);
            bgDim.setVisible(false);
            root.getStyleClass().remove("has-bg");
            return;
        }
        Path file = AppPaths.root().resolve(name);
        if (!java.nio.file.Files.isRegularFile(file)) {
            bgLayer.setBackground(null);
            bgDim.setVisible(false);
            root.getStyleClass().remove("has-bg");
            return;
        }
        javafx.scene.image.Image image = new javafx.scene.image.Image(file.toUri().toString(), true);
        boolean contain = "contain".equalsIgnoreCase(config.backgroundMode);
        // cover=等比放大铺满（裁边）；contain=等比缩小完整显示（留边），都不拉伸变形
        javafx.scene.layout.BackgroundSize size = contain
                ? new javafx.scene.layout.BackgroundSize(0, 0, false, false, true, false)
                : new javafx.scene.layout.BackgroundSize(0, 0, false, false, false, true);
        bgLayer.setBackground(new javafx.scene.layout.Background(
                new javafx.scene.layout.BackgroundImage(image,
                        javafx.scene.layout.BackgroundRepeat.NO_REPEAT,
                        javafx.scene.layout.BackgroundRepeat.NO_REPEAT,
                        javafx.scene.layout.BackgroundPosition.CENTER,
                        size)));
        bgDim.setVisible(true);
        if (!root.getStyleClass().contains("has-bg")) {
            root.getStyleClass().add("has-bg");
        }
    }

    private VBox buildPlayCard() {
        Label label = new Label("快速启动");
        label.getStyleClass().add("card-title");

        isolationBadge.getStyleClass().addAll("badge", "badge-vanilla");
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        HBox topRow = new HBox(10, label, topSpacer, isolationBadge);
        topRow.setAlignment(Pos.CENTER_LEFT);

        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setPrefHeight(46);
        versionBox.setVisibleRowCount(10);
        versionBox.setCellFactory(list -> new ListCell<>() {
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
                Label meta = new Label(item.subtitle());
                meta.getStyleClass().add("version-meta");
                setGraphic(new VBox(2, name, meta));
                setText(null);
            }
        });
        versionBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(VersionService.Entry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "（还没有安装版本）" : item.displayName());
            }
        });
        versionBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(VersionService.Entry entry) {
                return entry == null ? "" : entry.displayName();
            }

            @Override
            public VersionService.Entry fromString(String string) {
                return null;
            }
        });
        versionBox.valueProperty().addListener((observable, oldValue, newValue) -> onVersionSelected(newValue));

        versionHint.getStyleClass().add("version-meta");

        Region divider = new Region();
        divider.setPrefHeight(1);
        divider.setMinHeight(1);
        divider.setMaxWidth(Double.MAX_VALUE);
        divider.setStyle("-fx-background-color: rgba(255,255,255,0.07);");

        VBox details = new VBox(8,
                infoRow("加载器", infoLoader),
                infoRow("版本隔离", infoIsolation),
                infoRow("模组数量", infoMods),
                infoRow("来源", infoSource));

        playButton.getStyleClass().add("button-primary");
        playButton.setMaxWidth(Double.MAX_VALUE);
        playButton.setPrefHeight(48);
        com.qutc.launcher.ui.theme.Ui.animate(playButton);
        playButton.setOnAction(e -> launch());

        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        statusLine.getStyleClass().add("version-meta");

        VBox card = new VBox(10, topRow, versionBox, versionHint, divider, details,
                playButton, progress, statusLine);
        card.getStyleClass().addAll("card", "hero-card");
        card.setPadding(new Insets(18, 20, 16, 20));
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        return card;
    }

    /** 详情行：左键右值，撑满整行。 */
    private static HBox infoRow(String name, Label value) {
        Label key = new Label(name);
        key.getStyleClass().add("version-meta");
        key.setMinWidth(76);
        value.getStyleClass().add("version-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(10, key, spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox buildMemoryCard() {
        Label title = new Label("智能内存");
        title.getStyleClass().add("card-title");
        memoryValue.getStyleClass().add("value-big");
        memoryHint.getStyleClass().add("version-meta");
        memoryHint.setWrapText(true);
        memoryBar.setMaxWidth(Double.MAX_VALUE);
        VBox card = new VBox(6, title, memoryValue, memoryBar, memoryHint);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(14, 16, 14, 16));
        return card;
    }

    private VBox buildJavaCard() {
        Label title = new Label("Java 状态");
        title.getStyleClass().add("card-title");
        javaValue.getStyleClass().add("value-big");
        javaHint.getStyleClass().add("version-meta");
        javaHint.setWrapText(true);
        VBox card = new VBox(6, title, javaValue, javaHint);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(14, 16, 14, 16));
        return card;
    }

    private void buildAccountChip() {
        avatar.getStyleClass().add("avatar-circle");
        avatarText.getStyleClass().add("avatar-text");
        avatarText.setVisible(false);
        headView.setVisible(false);
        avatar.getChildren().setAll(headView, avatarText);
        accountName.getStyleClass().add("version-name");
        accountType.getStyleClass().add("version-meta");
        VBox info = new VBox(2, accountName, accountType);
        accountChip.getChildren().setAll(avatar, info);
        accountChip.setAlignment(Pos.CENTER_LEFT);
        accountChip.getStyleClass().add("account-chip");
        Tooltip.install(accountChip, new Tooltip("点击切换账号 / 登录"));
        accountChip.setOnMouseClicked(e -> showAccountMenu());

        SkinService.addListener(this::applyAvatarSkin);
        SkinService.refreshAsync();
    }

    /** 用当前皮肤头部替换字母头像。 */
    private void applyAvatarSkin() {
        Account account = currentAccount;
        if (account == null) {
            headView.setVisible(false);
            avatarText.setVisible(true);
            return;
        }
        headView.setSkin(SkinService.skinImage());
        headView.setVisible(true);
        avatarText.setVisible(false);
    }

    // ---------- 数据刷新 ----------

    private void refreshVersions() {
        statusLine.setText("正在扫描版本…");
        Async.supply(versionService::scan).thenAccept(entries -> Async.ui(() -> {
            String selectedId = versionBox.getValue() == null ? null : versionBox.getValue().versionId();
            List<VersionService.Entry> visible = entries.stream().filter(entry -> !entry.hidden()).toList();
            versionBox.getItems().setAll(visible);
            if (visible.isEmpty()) {
                versionBox.setValue(null);
                statusLine.setText("还没有安装任何版本 —— 去「下载中心」挑一个版本安装即可。");
                versionHint.setText("可用 --install <版本> 先装一个试试");
                updateMemoryPlaceholder();
                updateJavaPlaceholder();
                return;
            }
            VersionService.Entry pick = visible.stream()
                    .filter(entry -> entry.versionId().equals(selectedId))
                    .findFirst()
                    .orElse(visible.get(0));
            versionBox.setValue(pick);
            statusLine.setText("就绪 · 共 " + visible.size() + " 个版本");
            config = ConfigManager.get();
            onVersionSelected(pick);
        })).exceptionally(error -> {
            Log.error("UI", "扫描版本失败", error);
            Async.ui(() -> statusLine.setText("扫描版本失败: " + error.getMessage()));
            return null;
        });
    }

    private LauncherConfig config = LauncherConfig.defaults();

    private void onVersionSelected(VersionService.Entry entry) {
        if (entry == null) {
            return;
        }
        VersionConfig versionConfig = entry.config();
        versionHint.setText((versionConfig.isolated ? "版本隔离：开" : "版本隔离：关")
                + (entry.modCount() > 0 ? " · mods " + entry.modCount() + " 个" : "")
                + (entry.detection().optifine() ? " · 含 OptiFine" : ""));

        // 详情行 + 隔离徽标
        isolationBadge.setText(versionConfig.isolated ? "版本隔离：开" : "版本隔离：关");
        isolationBadge.getStyleClass().removeAll("badge-vanilla", "badge-forge", "badge-fabric", "badge-quilt");
        isolationBadge.getStyleClass().add(badgeClass(entry.detection().primary()));
        infoLoader.setText(entry.detection().hasLoader()
                ? entry.detection().primary().displayName()
                        + (entry.detection().optifine() && entry.detection().primary() != LoaderKind.OPTIFINE
                                ? " + OptiFine" : "")
                : "原版");
        infoIsolation.setText(versionConfig.isolated ? "开（推荐）" : "关（与其它版本共用存档）");
        infoMods.setText(entry.modCount() + " 个");
        infoSource.setText(entry.sourceText());

        Async.supply(() -> {
            MemoryAdvisor.Recommendation memory = MemoryAdvisor.recommend(entry.modCount(),
                    JavaStatus.hasShaders(entry.versionId()));
            Path javaHome = versionConfig.javaPath == null || versionConfig.javaPath.isBlank()
                    ? null : Path.of(versionConfig.javaPath);
            JavaStatus.Report java = JavaStatus.forVersion(entry.versionId(), javaHome);
            return new Snapshot(memory, java);
        }).thenAccept(snapshot -> Async.ui(() -> applySnapshot(snapshot)));
    }

    private static String badgeClass(LoaderKind kind) {
        if (kind == null) {
            return "badge-vanilla";
        }
        return switch (kind) {
            case FABRIC -> "badge-fabric";
            case QUILT -> "badge-quilt";
            case FORGE, NEOFORGE -> "badge-forge";
            case OPTIFINE -> "badge-vanilla";
        };
    }

    private record Snapshot(MemoryAdvisor.Recommendation memory, JavaStatus.Report java) {
    }

    private void applySnapshot(Snapshot snapshot) {
        lastRecommendation = snapshot.memory();
        MemoryAdvisor.Recommendation memory = snapshot.memory();
        VersionService.Entry entry = versionBox.getValue();
        int current = entry == null ? 0 : entry.config().memoryMb;
        int effective = current > 0 ? current : memory.recommendedMb();
        memoryValue.setText((effective / 1024.0 > 0 ? String.format("%.1f GB", effective / 1024.0) : "—")
                + (current > 0 ? "（单版本覆盖）" : "（推荐）"));
        memoryBar.setProgress(Math.min(1.0, effective * 1.0 / Math.max(2048, memory.cap())));
        memoryHint.setText(memory.reason() + " · 推荐 " + memory.recommendedMb() + " MB · 上限 "
                + memory.cap() + " MB（物理内存的 60%）");

        JavaStatus.Report java = snapshot.java();
        javaValue.setText(java.missing() ? "缺失" : ("Java " + java.recommendedMajor() + (java.ok() ? " 就绪" : " 不匹配")));
        javaHint.setText(java.describe() + (java.executable() == null ? "" : "\n" + java.executable()));
    }

    private void updateMemoryPlaceholder() {
        MemoryAdvisor.Recommendation memory = MemoryAdvisor.recommend(0, false);
        memoryValue.setText(String.format("%.1f GB", memory.recommendedMb() / 1024.0));
        memoryBar.setProgress(0.25);
        memoryHint.setText("物理内存 " + memory.physicalMb() + " MB · 推荐 " + memory.recommendedMb() + " MB");
    }

    private void updateJavaPlaceholder() {
        javaValue.setText("待检测");
        javaHint.setText("选择版本后显示 Java 匹配状态");
    }

    private void refreshAccountChip() {
        Async.supply(() -> {
            AuthService service = AuthService.defaults();
            return service.current();
        }).thenAccept(maybe -> Async.ui(() -> {
            auth = AuthService.defaults();
            Account account = maybe.orElse(null);
            currentAccount = account;
            if (account == null) {
                avatarText.setText("?");
                accountName.setText("未登录");
                accountType.setText("点击登录 / 切换");
                applyAvatarSkin();
                SkinService.refreshAsync();
                return;
            }
            avatarText.setText(account.name().substring(0, 1).toUpperCase());
            accountName.setText(account.name());
            accountType.setText(switch (account.type()) {
                case MICROSOFT -> "微软正版";
                case YGGDRASIL -> "第三方登录" + (account.authServerName() == null
                        || account.authServerName().isBlank() ? "" : " · " + account.authServerName());
                case OFFLINE -> "离线账号";
            });
            applyAvatarSkin();
            SkinService.refreshAsync(this::applyAvatarSkin);
        }));
    }

    // ---------- 账号菜单 ----------

    private void showAccountMenu() {
        Async.supply(() -> auth.store().accounts()).thenAccept(accounts -> Async.ui(() -> {
            Optional<Account> current = auth.current();
            AccountMenuPopup.show(accountChip, current.orElse(null), accounts,
                    this::switchAccount, this::loginOffline, this::loginMicrosoft,
                    this::loginYggdrasil, this::refreshAccountChip);
        }));
    }

    /** 第三方外置登录：服务器地址 + 账号 + 密码（密码不落盘）。 */
    private void loginYggdrasil() {
        Window owner = accountChip.getScene() == null ? null : accountChip.getScene().getWindow();
        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("第三方登录（外置验证服务器）");
        javafx.scene.control.DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        Dialogs.styleLater(dialog);

        javafx.scene.control.TextField server = new javafx.scene.control.TextField();
        server.setPromptText("https://littleskin.cn/api/yggdrasil");
        server.setPrefWidth(420);
        javafx.scene.control.TextField user = new javafx.scene.control.TextField();
        user.setPromptText("验证服务器账号（邮箱 / 用户名）");
        javafx.scene.control.PasswordField pass = new javafx.scene.control.PasswordField();
        pass.setPromptText("密码（只用于本次登录，不会保存）");

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("服务器"), server);
        grid.addRow(1, new Label("账号"), user);
        grid.addRow(2, new Label("密码"), pass);
        Label hint = new Label("支持任意 Yggdrasil / authlib-injector 服务器；\n"
                + "也可以直接粘贴 authlib-injector 的拖拽地址。");
        hint.getStyleClass().add("version-meta");
        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(12, hint, grid);
        content.setPadding(new Insets(4));
        pane.setContent(content);
        pane.setPrefWidth(520);

        if (dialog.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
                != javafx.scene.control.ButtonType.OK) {
            return;
        }
        String serverText = server.getText();
        String userText = user.getText();
        String passText = pass.getText();
        statusLine.setText("正在登录外置验证服务器…");
        Async.run(() -> {
            try {
                var result = auth.loginYggdrasil(serverText, userText, passText);
                Async.ui(() -> {
                    statusLine.setText("登录成功：" + result.account().name());
                    refreshAccountChip();
                });
            } catch (AuthException error) {
                Async.ui(() -> {
                    statusLine.setText("第三方登录失败");
                    Dialogs.error("第三方登录失败", error.getMessage(), "详细日志见 logs 目录");
                });
            } catch (Exception error) {
                Log.error("UI", "第三方登录异常", error);
                Async.ui(() -> Dialogs.error("第三方登录失败", "发生异常", String.valueOf(error.getMessage())));
            }
        });
    }

    private void switchAccount(Account account) {
        Async.run(() -> {
            try {
                auth.setCurrent(account);
                Async.ui(this::refreshAccountChip);
            } catch (IOException error) {
                Async.ui(() -> Dialogs.error("切换账号失败", account.display(), error.getMessage()));
            }
        });
    }

    private void loginOffline() {
        Window owner = accountChip.getScene() == null ? null : accountChip.getScene().getWindow();
        Dialogs.prompt("离线登录", "输入玩家名（3–16 位字母数字下划线）", "", owner).ifPresent(name -> Async.run(() -> {
            try {
                auth.loginOffline(name.trim());
                Async.ui(() -> {
                    refreshAccountChip();
                    refreshVersions();
                });
            } catch (Exception error) {
                Async.ui(() -> Dialogs.error("离线登录失败", "用户名不合法", error.getMessage()));
            }
        }));
    }

    private void loginMicrosoft() {
        LauncherConfig cfg = ConfigManager.get();
        String existing = cfg.microsoftClientId;
        Window owner = accountChip.getScene() == null ? null : accountChip.getScene().getWindow();
        AuthService service = AuthService.defaults();

        // 旧版内置客户端：不用 Azure 应用注册，浏览器登录后粘贴回调地址
        if (service.usesLegacyFlow()) {
            if (!openInBrowser(service.legacyAuthorizeUri())) {
                Dialogs.info("需要手动打开登录页",
                        "无法自动打开浏览器，请手动访问下面这个地址完成登录",
                        service.legacyAuthorizeUri().toString());
            }
            statusLine.setText("已在浏览器打开微软登录页，等待粘贴回调地址…");
            Optional<String> pasted = Dialogs.prompt("微软正版登录",
                    "登录完成后，把浏览器地址栏里的完整地址粘贴到这里\n（以 https://login.live.com/oauth20_desktop.srf 开头）",
                    "", owner);
            if (pasted.isEmpty() || pasted.get().isBlank()) {
                statusLine.setText("已取消微软登录");
                return;
            }
            String callbackUrl = pasted.get();
            Async.run(() -> {
                try {
                    var result = service.loginMicrosoftWithPastedUrl(callbackUrl);
                    Async.ui(() -> {
                        statusLine.setText("登录成功：" + result.account().name());
                        refreshAccountChip();
                    });
                } catch (AuthException error) {
                    Async.ui(() -> {
                        statusLine.setText("微软登录失败");
                        Dialogs.error("微软登录失败", error.getMessage(), "详细日志见 logs 目录");
                    });
                } catch (Exception error) {
                    Log.error("UI", "微软登录异常", error);
                    Async.ui(() -> Dialogs.error("微软登录失败", "发生异常", String.valueOf(error.getMessage())));
                }
            });
            return;
        }

        // 自建 Azure 应用（PKCE + loopback 自动回调）
        String clientId = existing;
        if (clientId == null || clientId.isBlank()) {
            Optional<String> input = Dialogs.prompt("微软登录需要 Client ID",
                    "请填入 Azure 应用注册的客户端 ID（清空设置里这一项即可改用内置客户端，不需要注册）", "", owner);
            if (input.isEmpty() || input.get().isBlank()) {
                return;
            }
            clientId = input.get().trim();
            String finalClientId = clientId;
            ConfigManager.update(c -> c.microsoftClientId = finalClientId);
        }
        statusLine.setText("等待浏览器完成微软登录…");
        Async.run(() -> {
            try {
                AuthService azure = AuthService.defaults();
                var result = azure.loginMicrosoft(Duration.ofMinutes(5));
                Async.ui(() -> {
                    statusLine.setText("登录成功：" + result.account().name());
                    refreshAccountChip();
                });
            } catch (AuthException error) {
                Async.ui(() -> {
                    statusLine.setText("微软登录失败");
                    Dialogs.error("微软登录失败", error.getMessage(),
                            error.requiresRelogin() ? "请重试并完成浏览器登录" : "详细日志见 logs 目录");
                });
            } catch (Exception error) {
                Log.error("UI", "微软登录异常", error);
                Async.ui(() -> Dialogs.error("微软登录失败", "发生异常", String.valueOf(error.getMessage())));
            }
        });
    }

    private static boolean openInBrowser(java.net.URI uri) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(uri);
                return true;
            }
        } catch (Throwable t) {
            Log.warn("UI", "打开浏览器失败: " + t.getClass().getSimpleName());
        }
        return false;
    }

    // ---------- 启动 ----------

    private void launch() {
        if (busy) {
            return;
        }
        VersionService.Entry entry = versionBox.getValue();
        if (entry == null) {
            Dialogs.warn("无法启动", "没有可用版本", "请先在版本管理或下载中心安装一个版本");
            return;
        }
        busy = true;
        playButton.setDisable(true);
        playButton.setText("启动中…");
        progress.setVisible(true);
        progress.setManaged(true);
        progress.setProgress(-1);
        statusLine.setText("准备启动 " + entry.displayName() + " …");
        // 日志窗口必须在 FX 线程创建，先于后台任务
        GameLogWindow logWindow = openLogWindow(entry);

        Async.run(() -> {
            try {
                LaunchService.Options options = buildOptions(entry);
                Downloader.Listener listener = this::onDownloadProgress;
                LaunchService.Prepared prepared = LaunchService.prepare(options, listener, MirrorResolver.of(null));
                if (!prepared.preflight().ok()) {
                    String summary = prepared.preflight().summary();
                    Async.ui(() -> {
                        if (logWindow != null) {
                            logWindow.setStatus("启动前检查未通过");
                        }
                        Dialogs.error("启动前检查未通过", summary,
                                "请先在版本管理中修复缺失文件，或重新安装该版本");
                        endBusy("检查未通过");
                    });
                    return;
                }
                GameProcess process = LaunchService.start(prepared, line -> {
                    if (logWindow != null) {
                        Async.ui(() -> logWindow.append(line));
                    }
                    // 状态栏只显示关键行，避免刷屏
                    if (line.contains("Setting user") || line.contains("ERROR") || line.contains("Exception")) {
                        Async.ui(() -> statusLine.setText(line.length() > 80 ? line.substring(0, 80) : line));
                    }
                }, true);
                runningGame = process;
                Async.ui(() -> {
                    playButton.setText("运行中…");
                    progress.setVisible(false);
                    progress.setManaged(false);
                    statusLine.setText("游戏运行中 · 日志 " + process.logFile().getFileName());
                    if (logWindow != null) {
                        logWindow.setStatus("游戏运行中 · " + process.logFile());
                    }
                });
                int code = process.waitFor();
                CrashAnalyzer.Diagnosis crash = code == 0 ? null : diagnoseCrash(process.logFile());
                Async.ui(() -> {
                    statusLine.setText(code == 0 ? "游戏已正常退出" : "游戏异常退出，退出码 " + code);
                    runningGame = null;
                    busy = false;
                    playButton.setDisable(false);
                    playButton.setText("开始游戏");
                    if (logWindow != null) {
                        logWindow.setStatus(code == 0 ? "游戏已正常退出" : "游戏异常退出，退出码 " + code);
                        if (crash != null) {
                            logWindow.showDiagnosis(crash.title(), crash.suggestion());
                        }
                    }
                    if (crash != null) {
                        Dialogs.error("游戏崩溃诊断", crash.title(),
                                crash.suggestion() + System.lineSeparator() + System.lineSeparator()
                                        + "日志：" + process.logFile());
                    }
                });
            } catch (Throwable error) {
                Log.error("UI", "启动失败", error);
                Async.ui(() -> {
                    if (logWindow != null) {
                        logWindow.setStatus("启动失败：" + error.getMessage());
                    }
                    Dialogs.error("启动失败", "无法启动 " + entry.displayName(),
                            String.valueOf(error.getMessage()));
                    endBusy("启动失败");
                });
            }
        });
    }

    private void onDownloadProgress(Downloader.Progress progress) {
        String text = progress.totalFiles() == 0
                ? "正在准备…"
                : "下载 " + progress.doneFiles() + "/" + progress.totalFiles()
                + "  " + progress.percent() + "%  " + progress.bytesPerSecond() / 1024 + " KB/s";
        Async.ui(() -> statusLine.setText(text));
    }

    /** 按全局设置决定是否打开实时日志窗口。 */
    private GameLogWindow openLogWindow(VersionService.Entry entry) {
        if (!ConfigManager.get().showLogWindow) {
            return null;
        }
        javafx.stage.Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        GameLogWindow window = new GameLogWindow(entry.versionId(), owner);
        window.show();
        return window;
    }

    /** 读日志尾部跑崩溃匹配表，返回最可能的根因（没有命中时返回 null）。 */
    private CrashAnalyzer.Diagnosis diagnoseCrash(java.nio.file.Path logFile) {
        try {
            if (logFile == null || !java.nio.file.Files.isRegularFile(logFile)) {
                return null;
            }
            long size = java.nio.file.Files.size(logFile);
            long start = Math.max(0, size - 768 * 1024);
            byte[] bytes;
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile.toFile(), "r")) {
                raf.seek(start);
                bytes = new byte[(int) (size - start)];
                raf.readFully(bytes);
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            List<CrashAnalyzer.Diagnosis> diagnoses = CrashAnalyzer.analyze(text);
            return diagnoses.isEmpty() ? null : diagnoses.get(0);
        } catch (IOException error) {
            Log.warn("UI", "崩溃诊断读取日志失败: " + error.getMessage());
            return null;
        }
    }

    private void endBusy(String message) {
        busy = false;
        playButton.setDisable(false);
        playButton.setText("开始游戏");
        progress.setVisible(false);
        progress.setManaged(false);
        statusLine.setText(message);
    }

    private LaunchService.Options buildOptions(VersionService.Entry entry) throws IOException {
        VersionConfig config = entry.config();
        LauncherConfig global = ConfigManager.get();

        Account account = auth.current().orElse(null);
        if (account != null && account.isMicrosoft()) {
            try {
                account = auth.ensureFresh(account);
            } catch (AuthException error) {
                throw new IOException("账号续期失败：" + error.getMessage()
                        + (error.requiresRelogin() ? "，请在主页重新登录" : ""), error);
            }
        }
        String username = account != null ? account.name() : "Player";
        String uuid = account != null ? account.uuid() : HashUtils.offlineUuid(username);
        String accessToken = "0";
        String userType = account != null ? account.userType() : "legacy";
        String xuid = account == null ? null : account.xuid();
        String clientId = account != null && account.isMicrosoft()
                ? AuthService.effectiveClientId(global)
                : null;
        if (account != null && account.online()) {
            Optional<Credentials> credentials = auth.credentialsFor(account);
            if (credentials.isEmpty()) {
                throw new IOException("账号 " + account.name() + " 的登录凭据不可用，请在主页重新登录");
            }
            accessToken = credentials.get().accessToken();
        }

        MemoryAdvisor.Recommendation recommendation = MemoryAdvisor.recommend(entry.modCount(),
                JavaStatus.hasShaders(entry.versionId()));
        int memory = config.memoryMb > 0 ? config.memoryMb : recommendation.recommendedMb();
        if (global.memoryMode == LauncherConfig.MemoryMode.GLOBAL && config.memoryMb <= 0
                && global.globalMemoryMb > 0) {
            memory = global.globalMemoryMb;
        }
        int width = config.width > 0 ? config.width : global.resolutionWidth;
        int height = config.height > 0 ? config.height : global.resolutionHeight;
        boolean fullscreen = config.fullscreen || global.fullscreen;
        Path javaHome = config.javaPath == null || config.javaPath.isBlank() ? null : Path.of(config.javaPath);

        return new LaunchService.Options(
                entry.versionId(),
                username,
                uuid,
                memory,
                width,
                height,
                fullscreen,
                config.isolated,
                true,
                true,
                Math.max(1, global.parallelDownloads),
                javaHome,
                List.copyOf(config.jvmArgs),
                List.copyOf(config.gameArgs),
                true,
                accessToken,
                userType,
                xuid,
                clientId,
                entry.imported() ? Path.of(entry.externalRoot()) : null);
    }

    /** 供外部（例如版本管理页）切到主页并选版本。 */
    public void selectVersion(String versionId) {
        for (VersionService.Entry entry : versionBox.getItems()) {
            if (entry.versionId().equals(versionId)) {
                versionBox.setValue(entry);
                return;
            }
        }
    }

    public static String systemSummary() {
        return OsUtils.name() + " " + OsUtils.arch() + " · 物理内存 "
                + OsUtils.totalPhysicalMemory() / 1024 / 1024 + " MB";
    }
}
