package com.qutc.launcher.utils;

import com.sun.jna.platform.win32.Crypt32Util;

/**
 * Windows DPAPI 数据保护（CurrentUser 作用域）。
 *
 * <p>总纲要求：令牌禁止明文落盘，优先 DPAPI，AES 兜底。
 * DPAPI 的密钥由 Windows 按当前用户账户派生，配置文件被拷到别的机器上也解不开，
 * 这是它优于自管 AES 密钥的根本原因。
 *
 * <p>本类所有失败路径都只返回 null / false，绝不抛给调用方——加密不可用必须能降级，
 * 但降级前要落一条 WARN 日志，方便排查。
 */
public final class Dpapi {

    private static volatile Boolean available;

    private Dpapi() {
    }

    /** DPAPI 是否可用（仅 Windows 且 Crypt32 可加载）。 */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean result;
        if (!OsUtils.isWindows()) {
            result = false;
        } else {
            try {
                byte[] probe = "qutc-probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] encrypted = Crypt32Util.cryptProtectData(probe);
                byte[] decrypted = Crypt32Util.cryptUnprotectData(encrypted);
                result = java.util.Arrays.equals(probe, decrypted);
            } catch (Throwable t) {
                Log.warn("DPAPI", "可用性探测失败，将降级 AES: " + t.getClass().getSimpleName());
                result = false;
            }
        }
        available = result;
        return result;
    }

    /** 加密，失败返回 null。 */
    public static byte[] protect(byte[] plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            return Crypt32Util.cryptProtectData(plaintext);
        } catch (Throwable t) {
            Log.warn("DPAPI", "加密失败: " + t.getClass().getSimpleName(), t);
            return null;
        }
    }

    /** 解密，失败返回 null。 */
    public static byte[] unprotect(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            return Crypt32Util.cryptUnprotectData(payload);
        } catch (Throwable t) {
            Log.warn("DPAPI", "解密失败（可能换了用户或换机）", t);
            return null;
        }
    }
}
