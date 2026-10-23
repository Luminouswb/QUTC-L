package com.qutc.launcher.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 用户态错误提示。
 *
 * <p>总纲要求：错误必须给「中文、可读、可操作」的说明，禁止把 Java 堆栈直接甩给玩家。
 * 堆栈进日志，弹窗只给人话。
 */
public final class FriendlyDialog {

    private FriendlyDialog() {
    }

    public static void showError(String summary, String detail) {
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("QUTC Launcher");
            alert.setHeaderText(summary);
            alert.setContentText(detail == null ? "" : detail);
            alert.getDialogPane().setMinWidth(560);
            alert.showAndWait();
        } catch (Throwable t) {
            // UI 不可用时退回控制台，绝不再抛
            System.err.println(summary + "\n" + detail);
        }
    }

    public static void showErrorWithText(String summary, String detail, String rawText) {
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("QUTC Launcher");
            alert.setHeaderText(summary);
            alert.setContentText(detail == null ? "" : detail);
            if (rawText != null && !rawText.isBlank()) {
                TextArea area = new TextArea(rawText);
                area.setEditable(false);
                area.setWrapText(false);
                area.setMaxHeight(260);
                VBox.setVgrow(area, Priority.ALWAYS);
                alert.getDialogPane().setExpandableContent(new VBox(new Label("详细信息"), area));
            }
            alert.getDialogPane().setMinWidth(560);
            alert.showAndWait();
        } catch (Throwable t) {
            System.err.println(summary + "\n" + detail + "\n" + rawText);
        }
    }

    public static void showInfo(String title, String message) {
        try {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("QUTC Launcher");
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        } catch (Throwable t) {
            System.out.println(title + ": " + message);
        }
    }
}
