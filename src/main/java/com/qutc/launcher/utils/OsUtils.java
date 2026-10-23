package com.qutc.launcher.utils;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * 操作系统 / 架构探测。
 *
 * <p>总纲要求 natives 按系统位数与 classifier 自动筛选（Windows x64 / ARM64），
 * 因此这里的架构判定必须精确，不能用 {@code os.arch} 的模糊落点。
 */
public final class OsUtils {

    private OsUtils() {
    }

    public static boolean isWindows() {
        return name().contains("win");
    }

    public static boolean isMac() {
        String name = name();
        return name.contains("mac") || name.contains("darwin");
    }

    public static boolean isLinux() {
        return name().contains("linux");
    }

    public static String name() {
        String value = System.getProperty("os.name", "unknown");
        return value.toLowerCase(Locale.ROOT);
    }

    public static String version() {
        return System.getProperty("os.version", "0");
    }

    public static boolean isWin11() {
        if (!isWindows()) {
            return false;
        }
        // Windows 11 的 build 号 >= 22000
        String build = System.getProperty("os.build", "");
        if (!build.isBlank()) {
            try {
                return Integer.parseInt(build) >= 22000;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        try {
            String osVersion = System.getProperty("os.version", "");
            String[] parts = osVersion.split("\\.");
            if (parts.length >= 3) {
                return Integer.parseInt(parts[2]) >= 22000;
            }
        } catch (NumberFormatException ignored) {
            // ignore
        }
        return false;
    }

    /** 架构：x64 / arm64 / x86 / unknown。 */
    public static String arch() {
        String raw = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (raw.contains("aarch64") || raw.contains("arm64")) {
            return "arm64";
        }
        if (raw.equals("amd64") || raw.equals("x86_64") || raw.contains("x64")) {
            return "x64";
        }
        if (raw.equals("x86") || raw.equals("i386") || raw.contains("i686")) {
            return "x86";
        }
        return raw.isEmpty() ? "unknown" : raw;
    }

    public static boolean is64Bit() {
        return arch().equals("x64") || arch().equals("arm64");
    }

    /** Minecraft rules 使用的 os.name 取值。 */
    public static String minecraftOsName() {
        if (isWindows()) {
            return "windows";
        }
        if (isMac()) {
            return "osx";
        }
        if (isLinux()) {
            return "linux";
        }
        return name();
    }

    /** 物理内存总量（字节）。 */
    public static long totalPhysicalMemory() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getTotalMemorySize();
            }
        } catch (Throwable ignored) {
            // 非 HotSpot 或受限环境
        }
        return 0L;
    }

    /** 可用物理内存（字节）。 */
    public static long freePhysicalMemory() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getFreeMemorySize();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0L;
    }

    public static int availableProcessors() {
        return Runtime.getRuntime().availableProcessors();
    }
}
