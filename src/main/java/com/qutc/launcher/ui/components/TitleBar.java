package com.qutc.launcher.ui.components;

import com.qutc.launcher.main.Launcher;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/**
 * 无边框窗口的自定义标题栏：品牌、副标题、最小化/最大化/关闭，按住可拖拽。
 */
public final class TitleBar extends HBox {

    private double dragOffsetX;
    private double dragOffsetY;

    public TitleBar(Stage stage, String subtitle) {
        getStyleClass().add("title-bar");
        setAlignment(Pos.CENTER_LEFT);

        Label brand = new Label("QUTC");
        brand.getStyleClass().add("title-brand");
        Label sub = new Label(subtitle == null ? "Minecraft 启动器 v" + Launcher.VERSION : subtitle);
        sub.getStyleClass().add("title-sub");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        var minimize = windowButton("—", "最小化", () -> stage.setIconified(true));
        var maximize = windowButton("▢", "最大化", () -> toggleMaximize(stage));
        var close = windowButton("✕", "关闭", () -> {
            stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            stage.close();
        });
        close.getStyleClass().add("window-button-close");

        HBox titleGroup = new HBox(10, brand, sub);
        titleGroup.setAlignment(Pos.CENTER_LEFT);
        HBox buttons = new HBox(4, minimize, maximize, close);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(titleGroup, spacer, buttons);

        setOnMousePressed(event -> {
            if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY || event.getClickCount() != 1) {
                return;
            }
            dragOffsetX = stage.getX() - event.getScreenX();
            dragOffsetY = stage.getY() - event.getScreenY();
        });
        setOnMouseDragged(event -> {
            if (stage.isMaximized()) {
                return;
            }
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                stage.setX(event.getScreenX() + dragOffsetX);
                stage.setY(event.getScreenY() + dragOffsetY);
            }
        });
        setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                toggleMaximize(stage);
            }
        });
    }

    private static javafx.scene.control.Button windowButton(String glyph, String tooltip, Runnable action) {
        javafx.scene.control.Button button = new javafx.scene.control.Button(glyph);
        button.getStyleClass().add("window-button");
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    public static void toggleMaximize(Stage stage) {
        boolean maximize = !stage.isMaximized();
        stage.setMaximized(maximize);
    }
}
