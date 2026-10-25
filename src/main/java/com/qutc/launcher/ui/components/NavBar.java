package com.qutc.launcher.ui.components;

import com.qutc.launcher.main.Launcher;
import com.qutc.launcher.utils.Log;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 左侧导航栏（七个页面入口）。
 */
public final class NavBar extends VBox {

    public record Item(String id, String glyph, String label) {
    }

    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private final Consumer<String> onSelect;

    public NavBar(Consumer<String> onSelect) {
        this.onSelect = onSelect;
        getStyleClass().add("nav-bar");
        setSpacing(4);

        Label header = new Label("导航");
        header.getStyleClass().add("nav-footer");
        header.setPadding(new javafx.geometry.Insets(6, 12, 8, 14));
        getChildren().add(header);

        add(new Item("home", "⌂", "主页"));
        add(new Item("versions", "▤", "版本管理"));
        add(new Item("downloads", "⇩", "下载中心"));
        add(new Item("settings", "⚙", "设置"));
        add(new Item("logs", "☰", "日志"));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);

        // 导航栏底部：当前账号皮肤的 3D 全身预览
        SkinFlat2D skinPreview = new SkinFlat2D(168, 150);
        skinPreview.setMaxWidth(Double.MAX_VALUE);
        getChildren().add(skinPreview);
        com.qutc.launcher.ui.service.SkinService.addListener(
                () -> skinPreview.setSkin(
                        com.qutc.launcher.ui.service.SkinService.skinImage(),
                        com.qutc.launcher.ui.service.SkinService.slim()));
        com.qutc.launcher.ui.service.SkinService.refreshAsync();

        Label footer = new Label("QUTC " + Launcher.VERSION + "\n数据目录见设置");
        footer.getStyleClass().add("nav-footer");
        footer.setPadding(new javafx.geometry.Insets(8, 12, 12, 14));
        getChildren().add(footer);
    }

    private void add(Item item) {
        Button button = new Button(item.glyph() + "   " + item.label());
        button.getStyleClass().add("nav-button");
        button.setAlignment(Pos.CENTER_LEFT);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> {
            Log.debug("UI", "切换到页面: " + item.id());
            onSelect.accept(item.id());
        });
        buttons.put(item.id(), button);
        getChildren().add(button);
    }

    /** 高亮当前页。 */
    public void select(String id) {
        for (Map.Entry<String, Button> entry : buttons.entrySet()) {
            Button button = entry.getValue();
            button.getStyleClass().remove("nav-button-selected");
            if (entry.getKey().equals(id)) {
                button.getStyleClass().add("nav-button-selected");
            }
        }
    }
}
