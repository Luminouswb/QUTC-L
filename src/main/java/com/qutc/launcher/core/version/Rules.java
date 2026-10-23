package com.qutc.launcher.core.version;

import com.qutc.launcher.utils.OsUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 规则求值器。
 *
 * <p>总纲要求：平台 / 特性判定必须覆盖 Windows、macOS、Linux 与 ARM64，
 * 且不得硬编码单一平台。这里把「运行环境」与「特性开关」抽成上下文，
 * 使规则判定可被单元测试完全覆盖。
 */
public final class Rules {

    private Rules() {
    }

    /**
     * 规则求值上下文。
     *
     * @param osName    minecraft 口径的系统名：windows / osx / linux
     * @param osVersion 系统版本号（用于 ^10\. 这类正则）
     * @param arch      架构：x64 / arm64 / x86
     * @param features  特性开关，缺省为 false
     */
    public record Context(String osName, String osVersion, String arch, Map<String, Boolean> features) {

        public static Context system() {
            return new Context(OsUtils.minecraftOsName(), OsUtils.version(), OsUtils.arch(), Map.of());
        }

        public static Context system(Map<String, Boolean> features) {
            return new Context(OsUtils.minecraftOsName(), OsUtils.version(), OsUtils.arch(),
                    features == null ? Map.of() : features);
        }

        public boolean feature(String key) {
            Boolean value = features.get(key);
            return value != null && value;
        }
    }

    /** 空规则视为允许。 */
    public static boolean allows(List<Rule> rules, Context context) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Rule rule : rules) {
            if (rule == null) {
                continue;
            }
            if (matches(rule, context)) {
                allowed = Rule.ALLOW.equals(rule.action);
            }
        }
        return allowed;
    }

    public static boolean matches(Rule rule, Context context) {
        if (rule == null) {
            return false;
        }
        Rule.OsCondition os = rule.os;
        if (os != null) {
            if (!blank(os.name) && !os.name.equalsIgnoreCase(context.osName())) {
                return false;
            }
            if (!blank(os.arch)) {
                String arch = context.arch();
                // 官方只写 x86 表示 32 位，ARM64 需要显式匹配
                if (!os.arch.equalsIgnoreCase(arch)) {
                    return false;
                }
            }
            if (!blank(os.version)) {
                String version = context.osVersion() == null ? "" : context.osVersion();
                try {
                    if (!Pattern.compile(os.version).matcher(version).find()) {
                        return false;
                    }
                } catch (RuntimeException badRegex) {
                    // 规则里的正则写坏了，按不匹配处理，避免整条链崩溃
                    return false;
                }
            }
        }
        if (rule.features != null && !rule.features.isEmpty()) {
            for (Map.Entry<String, Boolean> entry : rule.features.entrySet()) {
                boolean expected = Boolean.TRUE.equals(entry.getValue());
                if (context.feature(entry.getKey()) != expected) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 构建常用特性表，供启动参数中的 has_custom_resolution 等规则使用。 */
    public static Map<String, Boolean> features(boolean demoUser, boolean customResolution,
                                                boolean quickPlaySingleplayer,
                                                boolean quickPlayMultiplayer,
                                                boolean quickPlayRealms) {
        Map<String, Boolean> map = new HashMap<>();
        map.put("is_demo_user", demoUser);
        map.put("has_custom_resolution", customResolution);
        map.put("is_quick_play_singleplayer", quickPlaySingleplayer);
        map.put("is_quick_play_multiplayer", quickPlayMultiplayer);
        map.put("is_quick_play_realms", quickPlayRealms);
        return map;
    }

    private static boolean blank(String value) {
        return value == null || value.isEmpty();
    }
}
