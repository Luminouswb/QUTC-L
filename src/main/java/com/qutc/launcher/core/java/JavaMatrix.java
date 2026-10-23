package com.qutc.launcher.core.java;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本 → Java 版本推荐矩阵。
 *
 * <p>依据总纲第 2 节：
 * <ul>
 *   <li>1.8–1.11：习惯 Java 8，同时允许单版本覆盖；</li>
 *   <li>1.12–1.16.5：Java 8；</li>
 *   <li>1.17.x：Java 17；</li>
 *   <li>1.18–1.20.4：Java 17；</li>
 *   <li>1.20.5–1.21.x：Java 21；</li>
 *   <li>26.1+（新版本号规则）：Java 25。</li>
 * </ul>
 *
 * <p>推荐值是「能跑且最稳」的选择，最低值是「低于此版本一定起不来」。
 * 两者分开是因为 1.8 跑在 Java 17 上会因内部 API 变更直接崩，
 * 而 1.16 跑在 Java 21 上通常只是警告。
 */
public final class JavaMatrix {

    private static final Pattern LEGACY = Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?.*$");
    private static final Pattern SNAPSHOT = Pattern.compile("^\\d{2}[a-z]\\d{1,2}[a-z]$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODERN = Pattern.compile("^(\\d+)(?:\\.(\\d+))?.*$");

    /** 推荐运行时。 */
    public record Recommendation(int recommended, int minimum, String reason) {
        public String describe() {
            return "Java " + recommended + "（最低 Java " + minimum + "，" + reason + "）";
        }
    }

    private JavaMatrix() {
    }

    public static Recommendation recommend(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            return new Recommendation(21, 8, "版本未知，给新版本默认值");
        }
        String version = mcVersion.trim();

        Matcher legacy = LEGACY.matcher(version);
        if (legacy.matches()) {
            int minor = parseInt(legacy.group(1));
            int patch = legacy.group(2) == null ? 0 : parseInt(legacy.group(2));
            if (minor < 12) {
                return new Recommendation(8, 8, "1." + minor + " 属于 Java 8 时代");
            }
            if (minor <= 16) {
                return new Recommendation(8, 8, "1." + minor + " 使用 Java 8");
            }
            if (minor == 17) {
                return new Recommendation(17, 16, "1.17 起迁移到 Java 16+");
            }
            if (minor <= 19) {
                return new Recommendation(17, 16, "1." + minor + " 使用 Java 17");
            }
            if (minor == 20) {
                // 1.20.5 开始官方要求 Java 21
                return patch >= 5
                        ? new Recommendation(21, 21, "1.20." + patch + " 起要求 Java 21")
                        : new Recommendation(17, 16, "1.20." + patch + " 使用 Java 17");
            }
            return new Recommendation(21, 21, "1." + minor + " 使用 Java 21");
        }

        if (SNAPSHOT.matcher(version).matches()) {
            return new Recommendation(21, 17, "快照默认按 Java 21 处理");
        }

        Matcher modern = MODERN.matcher(version);
        if (modern.matches()) {
            int major = parseInt(modern.group(1));
            if (major >= 26) {
                return new Recommendation(25, 25, major + ".x 要求 Java 25");
            }
            if (major >= 21) {
                return new Recommendation(21, 21, major + ".x 使用 Java 21");
            }
            if (major >= 18) {
                return new Recommendation(17, 16, major + ".x 使用 Java 17");
            }
        }
        return new Recommendation(21, 8, "无法识别的版本号，按 Java 21 兜底");
    }

    public static int recommendMajor(String mcVersion) {
        return recommend(mcVersion).recommended();
    }

    public static int minimumMajor(String mcVersion) {
        return recommend(mcVersion).minimum();
    }

    /** 判断某个运行时能否运行该版本。 */
    public static boolean compatible(String mcVersion, int runtimeMajor) {
        return runtimeMajor > 0 && runtimeMajor >= minimumMajor(mcVersion);
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 规范化版本号，去掉前缀空白与常见修饰。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
