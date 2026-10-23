package com.qutc.launcher.core.version;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Minecraft 版本 JSON 中的规则条目。
 *
 * <p>规则语义与官方一致：
 * <ul>
 *   <li>没有任何规则 → 默认允许；</li>
 *   <li>按顺序匹配，命中则把结果设为该条的 action（allow / disallow），后面的继续覆盖；</li>
 *   <li>一条都不命中 → 视为不允许。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Rule {

    public static final String ALLOW = "allow";
    public static final String DISALLOW = "disallow";

    public String action;

    public OsCondition os;

    /** 特性条件，例如 is_demo_user、has_custom_resolution。 */
    public Map<String, Boolean> features;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class OsCondition {
        public String name;
        public String version;
        public String arch;

        @Override
        public String toString() {
            return "os{name=" + name + ", version=" + version + ", arch=" + arch + '}';
        }
    }

    @Override
    public String toString() {
        return "Rule{" + action + ", " + os + ", features=" + features + '}';
    }
}
