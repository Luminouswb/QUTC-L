package com.qutc.launcher.core.java;

import java.nio.file.Path;

/**
 * 一个可用的 Java 运行时。
 *
 * @param home       运行时根目录（含 {@code bin}、{@code release}）
 * @param executable 实际要执行的 java 可执行文件
 * @param version    完整版本号，例如 {@code 21.0.12} / {@code 1.8.0_381}
 * @param major      主版本：8 / 17 / 21 / 25
 * @param vendor     实现方，例如 {@code Amazon.com Inc.}
 * @param arch       架构：x64 / arm64 / x86
 * @param source     发现来源，便于日志排查（java_home / path / registry / scan）
 */
public record JavaRuntime(Path home, Path executable, String version, int major,
                          String vendor, String arch, String source) {

    public boolean is64Bit() {
        return "x64".equals(arch) || "arm64".equals(arch);
    }

    /** 展示名，例如 {@code Java 21 (Amazon.com Inc., x64)}。 */
    public String displayName() {
        return "Java " + major + " (" + (vendor == null || vendor.isBlank() ? "未知" : vendor) + ", " + arch + ')';
    }

    @Override
    public String toString() {
        return displayName() + " @ " + home;
    }
}
