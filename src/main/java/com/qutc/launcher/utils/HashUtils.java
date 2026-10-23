package com.qutc.launcher.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.LongConsumer;

/**
 * SHA-1 / SHA-256 / MD5 校验工具。
 *
 * <p>总纲要求：所有下载文件必须 SHA-1 + SHA-256 双校验，禁止跳过哈希校验。
 */
public final class HashUtils {

    private static final int BUFFER_SIZE = 64 * 1024;

    private HashUtils() {
    }

    public static String sha1(Path file) throws IOException {
        return digest(file, "SHA-1", null);
    }

    public static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256", null);
    }

    public static String md5(Path file) throws IOException {
        return digest(file, "MD5", null);
    }

    public static String sha1(Path file, LongConsumer progress) throws IOException {
        return digest(file, "SHA-1", progress);
    }

    public static String sha256(Path file, LongConsumer progress) throws IOException {
        return digest(file, "SHA-256", progress);
    }

    public static byte[] sha1Bytes(byte[] data) {
        return newDigest("SHA-1").digest(data);
    }

    /** 游戏离线 UUID：Minecraft 的规则是 name 的 MD5 加版本 3 UUID 标记。 */
    public static String offlineUuid(String playerName) {
        MessageDigest digest = newDigest("MD5");
        byte[] hash = digest.digest(("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x30);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return toDashedHex(hash);
    }

    public static String toDashedHex(byte[] hash) {
        StringBuilder sb = new StringBuilder(36);
        String hex = HexFormat.of().formatHex(hash);
        sb.append(hex, 0, 8).append('-')
                .append(hex, 8, 12).append('-')
                .append(hex, 12, 16).append('-')
                .append(hex, 16, 20).append('-')
                .append(hex, 20, 32);
        return sb.toString();
    }

    /**
     * 校验文件哈希。
     *
     * @param expected 期望值，大小写不敏感；为空视为不校验
     * @return 校验通过或无需校验返回 true
     */
    public static boolean verify(Path file, String expected, Algorithm algorithm) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            String actual = digest(file, algorithm.jceName, null);
            return actual.equalsIgnoreCase(expected.trim());
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean verifySha1(Path file, String expected) {
        return verify(file, expected, Algorithm.SHA1);
    }

    public static boolean verifySha256(Path file, String expected) {
        return verify(file, expected, Algorithm.SHA256);
    }

    public enum Algorithm {
        SHA1("SHA-1"),
        SHA256("SHA-256"),
        MD5("MD5");

        private final String jceName;

        Algorithm(String jceName) {
            this.jceName = jceName;
        }
    }

    private static String digest(Path file, String algorithm, LongConsumer progress) throws IOException {
        MessageDigest digest = newDigest(algorithm);
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                total += read;
                if (progress != null) {
                    progress.accept(total);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少摘要算法: " + algorithm, e);
        }
    }
}
