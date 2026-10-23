package com.qutc.launcher.ui.components;

import com.qutc.launcher.ui.theme.Ui;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Window;

import java.util.Optional;

/** 统一风格的对话框（暗色主题 + 圆角）。 */
public final class Dialogs {

    private Dialogs() {
    }

    public static void style(DialogPane pane) {
        Ui.applyThemeStyleSheets(pane.getScene());
    }

    public static Optional<String> prompt(String title, String header, String defaultValue, Window owner) {
        TextInputDialog dialog = new TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.setContentText(null);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        style(dialog.getDialogPane());
        return dialog.showAndWait();
    }

    public static boolean confirm(String title, String header, String content, Window owner) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (owner != null) {
            alert.initOwner(owner);
        }
        style(alert.getDialogPane());
        return alert.showAndWait().filter(type -> type == ButtonType.OK).isPresent();
    }

    public static void info(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        style(alert.getDialogPane());
        alert.showAndWait();
    }

    public static void warn(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        style(alert.getDialogPane());
        alert.showAndWait();
    }

    public static void error(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        style(alert.getDialogPane());
        alert.showAndWait();
    }

    /** 给 Dialog 用：DialogPane 的场景要等 show 之后才有，先挂样式表。 */
    public static void styleLater(Dialog<?> dialog) {
        dialog.setOnShowing(event -> Ui.applyThemeStyleSheets(dialog.getDialogPane().getScene()));
    }
}
