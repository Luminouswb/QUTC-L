package com.qutc.launcher.core.loader;

/** 总纲 §三.6 全加载器兼容矩阵里的五类加载器。 */
public enum LoaderKind {
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    OPTIFINE("OptiFine");

    private final String displayName;

    LoaderKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** {@code fabric} / {@code quilt} 这类用 meta API 装；其余用安装器 jar。 */
    public boolean viaMetaProfile() {
        return this == FABRIC || this == QUILT;
    }

    public static LoaderKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("加载器类型不能为空");
        }
        for (LoaderKind kind : values()) {
            if (kind.name().equalsIgnoreCase(raw.trim())) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知加载器: " + raw + "（可选 forge / neoforge / fabric / quilt / optifine）");
    }
}
