package com.qutc.launcher.ui.theme;

import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.config.LauncherConfig;

import javafx.scene.Scene;
import javafx.scene.paint.Color;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 运行时主题：自定义强调色。
 *
 * <p>JavaFX CSS 没有变量，这里把强调色相关的规则拼成一段临时样式表，
 * 用 {@code data:text/css;base64,...} 的形式挂到 Scene 上（JavaFX 支持 data URI 样式表），
 * 换色时先移除旧的那条再挂新的。
 */
public final class ThemeManager {

    public static final String DEFAULT_ACCENT = "#7C5CFF";

    private static String installedAccentSheet;

    private ThemeManager() {
    }

    /** 校验 & 规范化颜色（供设置页与配置 normalize 复用）。 */
    public static String normalizeColor(String raw) {
        if (raw == null) {
            return DEFAULT_ACCENT;
        }
        String value = raw.trim().toLowerCase();
        if (!value.startsWith("#")) {
            value = "#" + value;
        }
        return value.matches("#[0-9a-f]{6}") ? value : DEFAULT_ACCENT;
    }

    public static String accentColor() {
        LauncherConfig config = ConfigManager.get();
        return normalizeColor(config.themeColor);
    }

    /** 把当前强调色挂到场景上（重复调用会自动替换）。 */
    public static void install(Scene scene) {
        if (scene == null) {
            return;
        }
        if (installedAccentSheet != null) {
            scene.getStylesheets().remove(installedAccentSheet);
            installedAccentSheet = null;
        }
        String sheet = dataUri(buildSheet());
        scene.getStylesheets().add(sheet);
        installedAccentSheet = sheet;
    }

    /** 强调色 + 字体选择合成一张样式表。 */
    public static String buildSheet() {
        return accentCss(accentColor()) + "\n" + fontCss();
    }

    /** 用户自选字体：导航 / 主页 / 标注 三类，留空表示用主题默认。 */
    public static String fontCss() {
        LauncherConfig config = ConfigManager.get();
        StringBuilder css = new StringBuilder();
        appendFontRule(css, ".page-title, .page-subtitle, .button-primary", config.homeFont);
        appendFontRule(css,
                ".nav-button, .nav-button-selected, .sub-nav-button, .sub-nav-button-selected",
                config.navFont);
        appendFontRule(css,
                ".card-title, .version-name, .version-meta, .badge, .value-big,"
                        + " .tab-pane > .tab-header-area .tab .tab-label",
                config.labelFont);
        return css.toString();
    }

    private static void appendFontRule(StringBuilder css, String selectors, String family) {
        if (family == null || family.isBlank()) {
            return;
        }
        css.append(selectors)
                .append(" { -fx-font-family: \"")
                .append(family)
                .append("\", \"Microsoft YaHei UI\"; }\n");
    }

    /** 重新套用（设置页保存后用）。 */
    public static void reapply(Scene scene) {
        install(scene);
    }

    /** 生成强调色 CSS（公开给测试）。 */
    public static String accentCss(String accentRaw) {
        String accent = normalizeColor(accentRaw);
        String hover = blend(accent, "#FFFFFF", 0.18);
        String soft = withAlpha(accent, 0.16);
        String softer = withAlpha(accent, 0.10);
        String border = withAlpha(accent, 0.55);
        return String.join("\n",
                ".button-primary {"
                        + " -fx-background-color: linear-gradient(to right, " + hover + ", " + accent + ");"
                        + " -fx-text-fill: #FFFFFF; }",
                ".button-primary:hover {"
                        + " -fx-background-color: linear-gradient(to right, " + blend(accent, "#FFFFFF", 0.30)
                        + ", " + hover + "); }",
                ".nav-button-selected { -fx-background-color: " + soft + "; }",
                ".sub-nav-button-selected { -fx-background-color: " + soft + "; }",
                ".progress-bar > .bar { -fx-background-color: linear-gradient(to right, " + hover + ", " + accent + "); }",
                ".account-chip { -fx-border-color: " + border + "; }",
                ".version-tile-selected { -fx-border-color: " + border + "; -fx-background-color: " + softer + "; }",
                ".hero-card { -fx-border-color: " + border + "; }");
    }

    private static String dataUri(String css) {
        String encoded = Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
        return "data:text/css;base64," + encoded;
    }

    private static String withAlpha(String hex, double alpha) {
        Color color = Color.web(hex);
        return String.format(java.util.Locale.ROOT, "rgba(%d, %d, %d, %.2f)",
                Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255), alpha);
    }

    /** 向白色混合，得到 hover/渐变用的浅色。 */
    private static String blend(String hex, String otherHex, double ratio) {
        Color base = Color.web(hex);
        Color other = Color.web(otherHex);
        double r = base.getRed() * (1 - ratio) + other.getRed() * ratio;
        double g = base.getGreen() * (1 - ratio) + other.getGreen() * ratio;
        double b = base.getBlue() * (1 - ratio) + other.getBlue() * ratio;
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x",
                Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }
}
