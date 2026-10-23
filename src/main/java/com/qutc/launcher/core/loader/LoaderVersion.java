package com.qutc.launcher.core.loader;

/**
 * 一个可安装/已安装的加载器版本。
 *
 * @param kind          加载器类型
 * @param gameVersion   对应的原版版本号（如 1.20.1）
 * @param loaderVersion 加载器自身版本（Fabric 0.15.11 / Forge 47.2.0 / NeoForge 21.1.72）
 * @param versionId     写进 versions/ 目录的版本 id（通常由加载器 profile 决定）
 * @param stable        是否标记为稳定版（meta API 会给出，Maven 元数据没有则为 null）
 */
public record LoaderVersion(LoaderKind kind,
                            String gameVersion,
                            String loaderVersion,
                            String versionId,
                            Boolean stable) {

    public String display() {
        return kind.displayName() + ' ' + loaderVersion + " → " + gameVersion;
    }

    /** Forge 的版本坐标是 {@code <game>-<loader>}；其余只写自身版本。 */
    public String coordinate() {
        return kind == LoaderKind.FORGE ? gameVersion + '-' + loaderVersion : loaderVersion;
    }
}
