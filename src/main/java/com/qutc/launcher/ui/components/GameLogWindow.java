package com.qutc.launcher.ui.components;

import com.qutc.launcher.ui.theme.Ui;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 10：游戏实时日志窗口。
 *
 * <p>ERROR 标红、WARN 标黄、其余默认色；最多保留 5000 行，支持复制与清空。
 * 游戏异常退出时由调用方把 {@link #showDiagnosis} 的结论贴到底部。
 */
public final class GameLogWindow {

    private static final int MAX_LINES = 5000;

    private final ObservableList<String> lines = FXCollections.observableArrayList();
    private final List<String> allLines = new ArrayList<>();
    private final Label status = new Label("正在启动…");
    private final Label diagnosis = new Label();
    private final Stage stage = new Stage();
    private final ListView<String> list = new ListView<>(lines);

    public GameLogWindow(String title, Window owner) {
        stage.initStyle(StageStyle.UTILITY);
        stage.initModality(Modality.NONE);
        stage.setTitle("游戏日志 · " + title);
        if (owner != null) {
            stage.initOwner(owner);
        }

        list.setStyle("-fx-font-family: 'Cascadia Mono', 'Consolas', monospace; -fx-font-size: 12px;");
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (empty || item == null) {
                    setStyle("");
                } else if (item.contains("/ERROR") || item.contains("[ERROR]") || item.contains("Exception")) {
                    setStyle("-fx-text-fill: #ff7a7a;");
                } else if (item.contains("/WARN") || item.contains("[WARN]")) {
                    setStyle("-fx-text-fill: #e8c46a;");
                } else {
                    setStyle("-fx-text-fill: #cfd5e6;");
                }
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        status.getStyleClass().add("muted");
        diagnosis.setWrapText(true);
        diagnosis.setStyle("-fx-text-fill: #ff9b9b; -fx-font-weight: bold;");

        Button copy = Ui.animate(ghost("复制全部"));
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(String.join(System.lineSeparator(), allLines));
            Clipboard.getSystemClipboard().setContent(content);
            status.setText("已复制 " + allLines.size() + " 行");
        });
        Button clear = Ui.animate(ghost("清空"));
        clear.setOnAction(event -> {
            allLines.clear();
            lines.clear();
        });

        HBox buttons = new HBox(8, copy, clear);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(8, list, diagnosis, status, buttons);
        box.setPadding(new Insets(12));
        box.getStyleClass().add("app-shell");

        Scene scene = new Scene(box, 900, 560);
        stage.setScene(scene);
        Ui.applyTheme(scene);
    }

    public void show() {
        stage.show();
    }

    /** 追加一行（必须在 UI 线程调用）。 */
    public void append(String line) {
        if (line == null) {
            return;
        }
        allLines.add(line);
        if (allLines.size() > MAX_LINES) {
            allLines.subList(0, allLines.size() - MAX_LINES).clear();
        }
        lines.add(line);
        if (lines.size() > MAX_LINES) {
            lines.remove(0, lines.size() - MAX_LINES);
        }
        list.scrollTo(lines.size() - 1);
    }

    public void setStatus(String text) {
        status.setText(text);
    }

    /** 游戏异常退出时展示诊断结论。 */
    public void showDiagnosis(String title, String suggestion) {
        diagnosis.setText("⚠ " + title + " —— " + suggestion);
    }

    public void clearDiagnosis() {
        diagnosis.setText("");
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    public void close() {
        stage.close();
    }

    public Stage stage() {
        return stage;
    }

    private static Button ghost(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-ghost");
        return button;
    }
}
