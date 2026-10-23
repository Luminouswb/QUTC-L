package com.qutc.launcher.ui.components;

import com.qutc.launcher.auth.Account;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;
import java.util.function.Consumer;

/**
 * 账号切换弹层：替代 JavaFX 默认的白色 ContextMenu。
 *
 * <p>暗色圆角面板 + 账号行（当前账号打勾）+ 分隔线 + 操作项，跟随主窗口主题。
 */
public final class AccountMenuPopup {

    private AccountMenuPopup() {
    }

    public static void show(Node anchor,
                            Account current,
                            List<Account> accounts,
                            Consumer<Account> onSwitch,
                            Runnable onOffline,
                            Runnable onMicrosoft,
                            Runnable onYggdrasil,
                            Runnable onRefresh) {
        VBox panel = new VBox(4);
        panel.getStyleClass().add("account-popup");
        panel.setPrefWidth(300);

        panel.getChildren().add(header(current));
        if (!accounts.isEmpty()) {
            panel.getChildren().add(separator());
            for (Account account : accounts) {
                boolean active = current != null && account.storageKey().equals(current.storageKey());
                panel.getChildren().add(accountRow(account, active, () -> onSwitch.accept(account)));
            }
        }
        panel.getChildren().add(separator());
        panel.getChildren().add(actionRow("↪", "离线登录…", "用本地用户名直接进入游戏", onOffline));
        panel.getChildren().add(actionRow("☁", "微软正版登录…", "浏览器登录后粘贴回调地址", onMicrosoft));
        panel.getChildren().add(actionRow("⌁", "第三方登录…", "外置验证服务器（LittleSkin 等）", onYggdrasil));
        panel.getChildren().add(actionRow("⟳", "刷新登录状态", "重新读取令牌有效期", onRefresh));

        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(panel);
        if (anchor.getScene() != null) {
            panel.getStylesheets().addAll(anchor.getScene().getStylesheets());
        }
        javafx.geometry.Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) {
            popup.show(anchor, bounds.getMaxX() - 300, bounds.getMaxY() + 10);
        }
    }

    private static Node header(Account current) {
        String letter = current == null || current.name().isBlank()
                ? "?" : current.name().substring(0, 1).toUpperCase();
        Region avatar = avatar(letter, 42);
        Label name = new Label(current == null ? "未登录" : current.name());
        name.getStyleClass().add("account-popup-name");
        Label type = new Label(current == null ? "选择账号或登录"
                : (current.isMicrosoft() ? "微软正版 · 令牌已加密保存" : "离线账号"));
        type.getStyleClass().add("account-popup-meta");
        VBox info = new VBox(2, name, type);
        HBox box = new HBox(12, avatar, info);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(10, 12, 12, 12));
        return box;
    }

    private static Node accountRow(Account account, boolean active, Runnable action) {
        Region avatar = avatar(account.name().substring(0, 1).toUpperCase(), 30);
        Label name = new Label(account.name());
        name.getStyleClass().add("account-popup-row-name");
        Label type = new Label(account.isMicrosoft() ? "微软正版" : "离线");
        type.getStyleClass().add("account-popup-meta");
        VBox info = new VBox(1, name, type);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label check = new Label(active ? "✓" : "");
        check.getStyleClass().add("account-popup-check");

        HBox row = new HBox(10, avatar, info, spacer, check);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("account-popup-row");
        if (active) {
            row.getStyleClass().add("account-popup-row-active");
        }
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setOnMouseClicked(e -> {
            action.run();
        });
        return row;
    }

    private static Node actionRow(String glyph, String text, String hint, Runnable action) {
        Label icon = new Label(glyph);
        icon.getStyleClass().add("account-popup-icon");
        icon.setMinWidth(22);
        Label label = new Label(text);
        label.getStyleClass().add("account-popup-action");
        Label sub = new Label(hint);
        sub.getStyleClass().add("account-popup-meta");
        VBox info = new VBox(1, label, sub);
        HBox row = new HBox(10, icon, info);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("account-popup-row");
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setOnMouseClicked(e -> action.run());
        return row;
    }

    private static Region separator() {
        Region line = new Region();
        line.getStyleClass().add("account-popup-sep");
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setMaxHeight(1);
        VBox.setMargin(line, new Insets(4, 12, 4, 12));
        return line;
    }

    private static Region avatar(String letter, double size) {
        Label text = new Label(letter);
        text.getStyleClass().add("avatar-text");
        text.setStyle("-fx-font-size: " + Math.round(size * 0.42) + "px;");
        javafx.scene.layout.StackPane circle = new javafx.scene.layout.StackPane(text);
        circle.getStyleClass().add("avatar-circle");
        circle.setMinSize(size, size);
        circle.setPrefSize(size, size);
        circle.setMaxSize(size, size);
        return circle;
    }
}
