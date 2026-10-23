package com.qutc.launcher.auth;

import com.qutc.launcher.utils.HashUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * OAuth2 PKCE（RFC 7636）+ state 生成器。
 *
 * <p>总纲硬要求：授权码模式必须带 PKCE（S256）与 state。PKCE 让「授权码被截获」
 * 失去意义，state 挡住跨站伪造回调。两者都用 {@link SecureRandom}，不复用。
 */
public final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] VERIFIER_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toCharArray();

    private Pkce() {
    }

    /** RFC 7636 要求 43–128 个字符，这里取 64。 */
    public static String newVerifier() {
        return randomString(64);
    }

    /** code_challenge = BASE64URL-ENCODE(SHA256(ASCII(verifier)))，无填充。 */
    public static String challengeOf(String verifier) {
        byte[] digest = sha256(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /** 32 字节随机数转十六进制，用于 state 与 nonce。 */
    public static String newState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 校验 PKCE 不变量：challenge 必须等于 verifier 的 S256。 */
    public static boolean verify(String verifier, String challenge) {
        return verifier != null && challenge != null
                && MessageDigest.isEqual(challengeOf(verifier).getBytes(StandardCharsets.UTF_8),
                challenge.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomString(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = VERIFIER_ALPHABET[RANDOM.nextInt(VERIFIER_ALPHABET.length)];
        }
        return new String(out);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    /** 供单测复用的独立 SHA-256（避免与被测实现共用代码路径以外的逻辑）。 */
    static byte[] sha256ForTest(byte[] input) {
        return sha256(input);
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static String sha1Hex(String text) {
        return hex(HashUtils.sha1Bytes(text.getBytes(StandardCharsets.UTF_8)));
    }
}
