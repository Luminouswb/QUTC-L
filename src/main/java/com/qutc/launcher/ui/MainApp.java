package com.qutc.launcher.ui;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.main.Launcher;
import com.qutc.launcher.ui.components.NavBar;
import com.qutc.launcher.ui.components.TitleBar;
import com.qutc.launcher.ui.components.WindowResizer;
import com.qutc.launcher.ui.pages.DownloadsPage;
import com.qutc.launcher.ui.pages.HomePage;
import com.qutc.launcher.ui.pages.LogsPage;
import com.qutc.launcher.ui.pages.Page;
import com.qutc.launcher.ui.pages.PlaceholderPage;
import com.qutc.launcher.ui.pages.SettingsPage;
import com.qutc.launcher.ui.pages.VersionsPage;
import com.qutc.launcher.ui.theme.Ui;
import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.OsUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段 7：JavaFX 应用外壳。
 *
 * <p>无边框窗口（TRANSPARENT + 自绘标题栏 + 八向缩放）、左侧导航、页面切换动画。
 * 已实现：主页、版本管理；其余页面按总纲阶段顺序占位。
 */
public final class MainApp extends Application {

    private Stage stage;
    private final StackPane content = new StackPane();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private NavBar navBar;
    private HomePage homePage;
    private String currentPageId;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        installGlobalExceptionHandler();

        boolean transparent = initWindowStyle(stage);
        pages.put("home", homePage = new HomePage());
        pages.put("versions", new VersionsPage(versionId -> {
            showPage("home");
            homePage.selectVersion(versionId);
        }));
        pages.put("downloads", new DownloadsPage());
        pages.put("settings", new SettingsPage());
        pages.put("logs", new LogsPage());

        navBar = new NavBar(this::showPage);

        VBox body = new VBox(0);
        HBox shellBody = new HBox(12, navBar, content);
        shellBody.setPadding(new Insets(4, 14, 14, 14));
        HBox.setHgrow(content, Priority.ALWAYS);
        content.setAlignment(Pos.TOP_LEFT);
        body.getChildren().add(shellBody);
        VBox.setVgrow(shellBody, Priority.ALWAYS);

        VBox shell = new VBox(new TitleBar(stage, null), body);
        shell.getStyleClass().add("app-shell");
        VBox.setVgrow(body, Priority.ALWAYS);

        StackPane root = new StackPane(shell);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: transparent;");

        javafx.geometry.Rectangle2D bounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(1080, Math.max(940, bounds.getWidth() * 0.76));
        double initialHeight = Math.min(640, Math.max(580, bounds.getHeight() * 0.66));
        Scene scene = new Scene(root, initialWidth, initialHeight);
        scene.setFill(Color.TRANSPARENT);
        Ui.applyTheme(scene);
        stage.setScene(scene);
        stage.setTitle(Launcher.NAME);
        stage.setMinWidth(940);
        stage.setMinHeight(560);
        WindowResizer.install(stage, scene);

        stage.maximizedProperty().addListener((observable, oldValue, maximized) -> {
            root.setPadding(maximized ? Insets.EMPTY : new Insets(16));
            shell.setStyle(maximized
                    ? "-fx-background-radius: 0; -fx-border-radius: 0; -fx-effect: none;"
                    : "");
        });

        stage.show();
        showPage("home");
        maybeCheckUpdate();

        Log.info("UI", "主窗口已显示（透明窗口=" + transparent + "），JavaFX="
                + System.getProperty("javafx.version", "unknown")
                + "，系统=" + OsUtils.name() + " " + OsUtils.arch()
                + "，数据目录=" + AppPaths.root());
        Log.info("UI", "布局诊断：scene=" + Math.round(scene.getWidth()) + "x" + Math.round(scene.getHeight())
                + "，stage=" + Math.round(stage.getWidth()) + "x" + Math.round(stage.getHeight())
                + "，屏幕=" + Math.round(bounds.getWidth()) + "x" + Math.round(bounds.getHeight())
                + "，缩放=" + javafx.stage.Screen.getPrimary().getOutputScaleX());
    }

    /** 优先 TRANSPARENT（圆角 + 阴影）；个别驱动不支持时退回 UNDECORATED。 */
    private boolean initWindowStyle(Stage stage) {
        try {
            stage.initStyle(StageStyle.TRANSPARENT);
            return true;
        } catch (Throwable error) {
            Log.warn("UI", "透明窗口不可用，降级为无边框: " + error.getMessage());
            stage.initStyle(StageStyle.UNDECORATED);
            return false;
        }
    }

    private void showPage(String id) {
        Page page = pages.get(id);
        if (page == null) {
            return;
        }
        if (id.equals(currentPageId)) {
            page.onShow();
            return;
        }
        currentPageId = id;
        content.getChildren().setAll(page.view());
        Ui.animatePageIn(page.view());
        navBar.select(id);
        page.onShow();
    }

    @Override
    public void stop() {
        Log.info("UI", "主窗口关闭，开始收尾");
        com.qutc.launcher.thread.Async.shutdown();
    }

    /** 启动时按配置检查更新（后台线程，失败只记日志，不打扰用户）。 */
    private void maybeCheckUpdate() {
        com.qutc.launcher.config.LauncherConfig config = com.qutc.launcher.config.ConfigManager.get();
        if (!config.autoUpdate || config.updateBaseUrl == null || config.updateBaseUrl.isBlank()) {
            return;
        }
        String base = config.updateBaseUrl;
        com.qutc.launcher.thread.Async.run(() -> {
            try {
                com.qutc.launcher.update.UpdateService.CheckResult result =
                        com.qutc.launcher.update.UpdateService.check(base);
                if (!result.available()) {
                    return;
                }
                com.qutc.launcher.thread.Async.ui(() -> {
                    boolean goSettings = com.qutc.launcher.ui.components.Dialogs.confirm(
                            "发现新版本", result.remoteVersion(),
                            "当前 " + result.currentVersion() + "，是否前往设置页更新？", stage);
                    if (goSettings) {
                        showPage("settings");
                    }
                });
            } catch (Exception error) {
                Log.debug("Update", "自动检查更新失败: " + error.getMessage());
            }
        });
    }

    private void installGlobalExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.error("Uncaught", "线程 " + thread.getName() + " 未捕获异常", throwable);
            Platform.runLater(() -> FriendlyDialog.showError("发生未预期的错误",
                    throwable.getClass().getSimpleName() + ": " + throwable.getMessage()
                            + "\n\n详细日志已写入: " + Log.getLogDir()));
        });
    }
}
