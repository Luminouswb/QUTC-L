package com.qutc.launcher.ui.pages;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.config.VersionConfig;
import com.qutc.launcher.core.java.JavaProbe;
import com.qutc.launcher.core.java.JavaRuntime;
import com.qutc.launcher.ui.components.Dialogs;
import com.qutc.launcher.ui.components.ModManagerPane;
import com.qutc.launcher.ui.service.VersionService;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 单版本设置：隔离、内存、Java、分辨率、JVM/游戏参数、显示名。
 */
public final class VersionSettingsDialog {

    private VersionSettingsDialog() {
    }

    public static void show(Window owner, VersionService.Entry entry, Runnable onSaved) {
        VersionConfig config = entry.config();

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("版本设置");
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Dialogs.styleLater(dialog);

        Label header = new Label(entry.displayName());
        header.getStyleClass().add("page-title");

        TextField displayName = new TextField(config.displayName == null ? "" : config.displayName);
        displayName.setPromptText("留空则使用目录名 " + entry.versionId());

        CheckBox isolated = new CheckBox("启用版本隔离（存档 / mods / 配置独立）");
        isolated.setSelected(config.isolated);

        ComboBox<String> memoryMode = new ComboBox<>();
        memoryMode.getItems().addAll("自动推荐", "自定义");
        memoryMode.getSelectionModel().select(config.memoryMb > 0 ? 1 : 0);
        Spinner<Integer> memory = new Spinner<>(512, 65536, config.memoryMb > 0 ? config.memoryMb : 4096, 512);
        memory.setEditable(true);
        memory.disableProperty().bind(memoryMode.getSelectionModel().selectedIndexProperty().isNotEqualTo(1));

        List<JavaRuntime> runtimes = JavaProbe.discover();
        List<String> javaLabels = new ArrayList<>();
        javaLabels.add("自动选择（按版本矩阵）");
        for (JavaRuntime runtime : runtimes) {
            javaLabels.add(runtime.displayName() + "  ·  " + runtime.home());
        }
        ComboBox<String> javaBox = new ComboBox<>();
        javaBox.getItems().setAll(javaLabels);
        int selectedJava = 0;
        if (config.javaPath != null && !config.javaPath.isBlank()) {
            for (int i = 0; i < runtimes.size(); i++) {
                if (runtimes.get(i).home().toString().equalsIgnoreCase(config.javaPath)) {
                    selectedJava = i + 1;
                    break;
                }
            }
        }
        javaBox.getSelectionModel().select(selectedJava);
        javaBox.setMaxWidth(Double.MAX_VALUE);

        Spinner<Integer> width = new Spinner<>(0, 7680, config.width > 0 ? config.width : 0, 64);
        Spinner<Integer> height = new Spinner<>(0, 4320, config.height > 0 ? config.height : 0, 64);
        width.setEditable(true);
        height.setEditable(true);
        CheckBox fullscreen = new CheckBox("全屏启动");
        fullscreen.setSelected(config.fullscreen);

        TextArea jvmArgs = new TextArea(String.join("\n", config.jvmArgs));
        jvmArgs.setPrefRowCount(3);
        jvmArgs.setPromptText("每行一个 JVM 参数，例如 -XX:+UseG1GC");
        TextArea gameArgs = new TextArea(String.join("\n", config.gameArgs));
        gameArgs.setPrefRowCount(2);
        gameArgs.setPromptText("每行一个游戏参数，例如 --demo");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(8, 4, 4, 4));

        int row = 0;
        grid.addRow(row++, new Label("显示名"), displayName);
        grid.addRow(row++, new Label("版本隔离"), isolated);
        grid.addRow(row++, new Label("内存"), memoryMode);
        grid.addRow(row++, new Label(""), memory);
        grid.addRow(row++, new Label("Java"), javaBox);
        grid.addRow(row++, new Label("分辨率宽"), width);
        grid.addRow(row++, new Label("分辨率高"), height);
        grid.addRow(row++, new Label("全屏"), fullscreen);
        grid.addRow(row++, new Label("目录"), versionDirs(entry, config));
        grid.addRow(row++, new Label("JVM 参数"), jvmArgs);
        grid.addRow(row++, new Label("游戏参数"), gameArgs);

        javafx.scene.layout.VBox basic = new javafx.scene.layout.VBox(10, header, grid);
        basic.setPadding(new Insets(4));

        // 左侧竖排页签：基本设置 / 模组 / 资源包 / 光影
        ModManagerPane mods = new ModManagerPane();
        mods.setVersion(entry);
        javafx.scene.control.TabPane tabs = new javafx.scene.control.TabPane();
        tabs.setSide(javafx.geometry.Side.LEFT);
        tabs.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new javafx.scene.control.Tab("基本设置", basic),
                new javafx.scene.control.Tab("模组", mods.modsView()),
                new javafx.scene.control.Tab("资源包", mods.resourcePacksView()),
                new javafx.scene.control.Tab("光影", mods.shaderPacksView()));
        for (javafx.scene.control.Tab tab : tabs.getTabs()) {
            tab.getStyleClass().add("tab-dark");
        }
        tabs.getSelectionModel().selectFirst();
        tabs.setPrefSize(860, 560);

        pane.setContent(tabs);
        pane.setPrefWidth(880);
        dialog.setResizable(true);

        Optional<ButtonType> result = dialog.showAndWait();        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        String name = displayName.getText() == null ? "" : displayName.getText().trim();
        config.displayName = name.isBlank() ? null : name;
        config.isolated = isolated.isSelected();
        config.memoryMb = memoryMode.getSelectionModel().getSelectedIndex() == 1 ? memory.getValue() : 0;
        int javaIndex = javaBox.getSelectionModel().getSelectedIndex();
        config.javaPath = javaIndex <= 0 ? null : runtimes.get(javaIndex - 1).home().toString();
        config.width = width.getValue();
        config.height = height.getValue();
        config.fullscreen = fullscreen.isSelected();
        config.jvmArgs = splitLines(jvmArgs.getText());
        config.gameArgs = splitLines(gameArgs.getText());
        config.save();
        if (onSaved != null) {
            onSaved.run();
        }
    }

    private static List<String> splitLines(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 单个版本的目录快捷操作（游戏目录 / 版本目录 / 日志目录）。 */
    private static HBox versionDirs(VersionService.Entry entry, VersionConfig config) {
        Button gameDir = dirButton("游戏目录", AppPaths.gameDir(entry.versionId(), config.isolated));
        Button versionDir = dirButton("版本目录", AppPaths.versionDir(entry.versionId()));
        Button logDir = dirButton("日志目录", AppPaths.versionLogsDir(entry.versionId()));
        HBox row = new HBox(8, gameDir, versionDir, logDir);
        return row;
    }

    private static Button dirButton(String text, Path dir) {
        Button button = new Button(text);
        button.getStyleClass().add("button-ghost");
        button.setStyle("-fx-font-size: 12px; -fx-padding: 4 10 4 10;");
        button.setOnAction(e -> openDir(dir));
        return button;
    }

    private static void openDir(Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
        } catch (IOException error) {
            com.qutc.launcher.utils.Log.warn("UI", "打开目录失败: " + dir + " | " + error.getMessage());
        }
    }
}
