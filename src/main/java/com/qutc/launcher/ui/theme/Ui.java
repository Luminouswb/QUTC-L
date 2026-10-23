package com.qutc.launcher.ui.theme;

import com.qutc.launcher.config.ConfigManager;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.util.Duration;

import java.net.URL;

/**
 * 主题与轻量动效工具。
 *
 * <p>总纲要求「全局圆角控件、阴影层级、页面切换动画、按钮 hover/点击反馈」，
 * JavaFX 的 CSS 没有 transition，所以动效用 Transition 在代码里补。
 * 主题：暗色 `qutc.css`，浅色 `qutc-light.css`（在暗色之后加载覆盖颜色）。
 */
public final class Ui {

    private static final String CSS_PATH = "/com/qutc/launcher/ui/theme/qutc.css";
    private static final String LIGHT_CSS_PATH = "/com/qutc/launcher/ui/theme/qutc-light.css";

    private Ui() {
    }

    public static void applyTheme(Scene scene) {
        if (scene == null) {
            return;
        }
        applyStylesheet(scene, CSS_PATH);
        boolean light = "light".equalsIgnoreCase(currentTheme());
        applyStylesheet(scene, light ? LIGHT_CSS_PATH : null);
        ThemeManager.install(scene);
    }

    private static String currentTheme() {
        try {
            return ConfigManager.get().theme;
        } catch (RuntimeException error) {
            return "dark";
        }
    }

    private static void applyStylesheet(Scene scene, String path) {
        if (path == null) {
            return;
        }
        URL css = Ui.class.getResource(path);
        if (css != null && !scene.getStylesheets().contains(css.toExternalForm())) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    /** 切换主题后重新套用到场景（先清空再按当前配置加载）。 */
    public static void reapplyTheme(Scene scene) {
        if (scene == null) {
            return;
        }
        scene.getStylesheets().clear();
        applyTheme(scene);
    }

    /** 给弹窗等独立场景套同一套主题。 */
    public static void applyThemeStyleSheets(Scene scene) {
        applyTheme(scene);
    }

    /** 按钮 hover 放大、按下回弹。 */
    public static <T extends Button> T animate(T button) {
        ScaleTransition grow = new ScaleTransition(Duration.millis(120), button);
        grow.setToX(1.025);
        grow.setToY(1.025);
        ScaleTransition shrink = new ScaleTransition(Duration.millis(100), button);
        shrink.setToX(1.0);
        shrink.setToY(1.0);
        button.setOnMouseEntered(e -> {
            if (!button.isDisabled()) {
                grow.playFromStart();
            }
        });
        button.setOnMouseExited(e -> shrink.playFromStart());
        button.setOnMousePressed(e -> {
            if (!button.isDisabled()) {
                ScaleTransition press = new ScaleTransition(Duration.millis(70), button);
                press.setToX(0.97);
                press.setToY(0.97);
                press.playFromStart();
            }
        });
        button.setOnMouseReleased(e -> {
            if (!button.isDisabled()) {
                grow.playFromStart();
            }
        });
        return button;
    }

    /** 页面切换：淡入 + 轻微上移。 */
    public static void animatePageIn(Node node) {
        node.setOpacity(0);
        node.setTranslateY(10);
        FadeTransition fade = new FadeTransition(Duration.millis(220), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(240), node);
        slide.setFromY(10);
        slide.setToY(0);
        fade.play();
        slide.play();
    }

    /** 卡片出现：淡入 + 放大一点。 */
    public static void animatePopIn(Node node) {
        node.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(260), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        ScaleTransition scale = new ScaleTransition(Duration.millis(260), node);
        scale.setFromX(0.97);
        scale.setFromY(0.97);
        scale.setToX(1.0);
        scale.setToY(1.0);
        fade.play();
        scale.play();
    }
}
