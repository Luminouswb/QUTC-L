package com.qutc.launcher.ui.components;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * 无边框窗口的边缘拖拽缩放。
 *
 * <p>JavaFX 的 UNDECORATED / TRANSPARENT 窗口没有系统边框，必须自己实现八向缩放。
 * 只在距边缘 6px 内生效，中间区域不抢事件。
 */
public final class WindowResizer {

    private static final double EDGE = 6;
    private static final double MIN_WIDTH = 1024;
    private static final double MIN_HEIGHT = 640;

    private enum Zone { NONE, N, S, E, W, NE, NW, SE, SW }

    private final Stage stage;
    private Zone zone = Zone.NONE;
    private double startX;
    private double startY;
    private double startW;
    private double startH;
    private double startStageX;
    private double startStageY;

    private WindowResizer(Stage stage) {
        this.stage = stage;
    }

    public static void install(Stage stage, Scene scene) {
        WindowResizer resizer = new WindowResizer(stage);
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, resizer::onMove);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, resizer::onPress);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, resizer::onDrag);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> resizer.zone = Zone.NONE);
    }

    private Zone zoneOf(double sceneX, double sceneY, double width, double height) {
        boolean left = sceneX <= EDGE;
        boolean right = sceneX >= width - EDGE;
        boolean top = sceneY <= EDGE;
        boolean bottom = sceneY >= height - EDGE;
        if (top && left) {
            return Zone.NW;
        }
        if (top && right) {
            return Zone.NE;
        }
        if (bottom && left) {
            return Zone.SW;
        }
        if (bottom && right) {
            return Zone.SE;
        }
        if (top) {
            return Zone.N;
        }
        if (bottom) {
            return Zone.S;
        }
        if (left) {
            return Zone.W;
        }
        if (right) {
            return Zone.E;
        }
        return Zone.NONE;
    }

    private void onMove(MouseEvent event) {
        if (stage.isMaximized()) {
            return;
        }
        Zone current = zoneOf(event.getSceneX(), event.getSceneY(), stage.getWidth(), stage.getHeight());
        stage.getScene().setCursor(switch (current) {
            case N, S -> Cursor.V_RESIZE;
            case E, W -> Cursor.H_RESIZE;
            case NE, SW -> Cursor.NE_RESIZE;
            case NW, SE -> Cursor.NW_RESIZE;
            default -> Cursor.DEFAULT;
        });
    }

    private void onPress(MouseEvent event) {
        if (stage.isMaximized()) {
            return;
        }
        zone = zoneOf(event.getSceneX(), event.getSceneY(), stage.getWidth(), stage.getHeight());
        if (zone == Zone.NONE) {
            return;
        }
        startX = event.getScreenX();
        startY = event.getScreenY();
        startW = stage.getWidth();
        startH = stage.getHeight();
        startStageX = stage.getX();
        startStageY = stage.getY();
    }

    private void onDrag(MouseEvent event) {
        if (zone == Zone.NONE || stage.isMaximized()) {
            return;
        }
        double dx = event.getScreenX() - startX;
        double dy = event.getScreenY() - startY;
        double newX = startStageX;
        double newY = startStageY;
        double newW = startW;
        double newH = startH;

        boolean west = zone == Zone.W || zone == Zone.NW || zone == Zone.SW;
        boolean east = zone == Zone.E || zone == Zone.NE || zone == Zone.SE;
        boolean north = zone == Zone.N || zone == Zone.NW || zone == Zone.NE;
        boolean south = zone == Zone.S || zone == Zone.SW || zone == Zone.SE;

        if (west) {
            newW = Math.max(MIN_WIDTH, startW - dx);
            newX = startStageX + (startW - newW);
        }
        if (east) {
            newW = Math.max(MIN_WIDTH, startW + dx);
        }
        if (north) {
            newH = Math.max(MIN_HEIGHT, startH - dy);
            newY = startStageY + (startH - newH);
        }
        if (south) {
            newH = Math.max(MIN_HEIGHT, startH + dy);
        }
        stage.setX(newX);
        stage.setY(newY);
        stage.setWidth(newW);
        stage.setHeight(newH);
    }
}
