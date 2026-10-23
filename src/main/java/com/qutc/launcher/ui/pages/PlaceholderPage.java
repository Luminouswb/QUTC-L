package com.qutc.launcher.ui.pages;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** 尚未实现的页面占位（明确告知对应阶段，不假装可用）。 */
public final class PlaceholderPage implements Page {

    private final String id;
    private final String title;
    private final String description;
    private final VBox root = new VBox(12);

    public PlaceholderPage(String id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
        root.setPadding(new Insets(26));
        root.setAlignment(Pos.TOP_LEFT);

        Label heading = new Label(title);
        heading.getStyleClass().add("page-title");
        Label text = new Label(description);
        text.getStyleClass().add("placeholder-text");
        text.setWrapText(true);
        text.setMaxWidth(720);
        Label note = new Label("该页面按总纲阶段顺序实现，当前阶段的重点是启动链路与版本管理。");
        note.getStyleClass().add("placeholder-text");
        note.setWrapText(true);
        note.setMaxWidth(720);

        root.getChildren().addAll(heading, text, note);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Node view() {
        return root;
    }
}
