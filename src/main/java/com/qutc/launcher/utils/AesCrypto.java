package com.qutc.launcher.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-GCM 本地加密（DPAPI 不可用时的备选路线）。
 *
 * <p>总纲要求：Token / RefreshToken 禁止明文存储。Windows 下优先 DPAPI，
 * 本类是跨平台兜底。密钥由上层用机器指纹派生并保管，格式：
 * {@code [12字节 IV][16字节 GCM Tag][密文]}，整体 Base64 输出。
 */
public final class AesCrypto {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesCrypto() {
    }

    public static byte[] encrypt(byte[] key, byte[] plaintext) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(normalizeKey(key), "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        byte[] cipherText = cipher.doFinal(plaintext);
        byte[] output = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, output, 0, iv.length);
        System.arraycopy(cipherText, 0, output, iv.length, cipherText.length);
        return output;
    }

    public static byte[] decrypt(byte[] key, byte[] payload) throws Exception {
        if (payload == null || payload.length <= IV_LENGTH) {
            throw new IllegalArgumentException("密文长度非法");
        }
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(normalizeKey(key), "AES"),
                new GCMParameterSpec(TAG_BITS, iv));
        return cipher.doFinal(cipherText);
    }

    public static String encryptToBase64(byte[] key, String plaintext) throws Exception {
        return java.util.Base64.getEncoder().encodeToString(encrypt(key, plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    public static String decryptFromBase64(byte[] key, String payload) throws Exception {
        return new String(decrypt(key, java.util.Base64.getDecoder().decode(payload)), StandardCharsets.UTF_8);
    }

    public static byte[] randomKey(int bytes) {
        byte[] key = new byte[bytes];
        RANDOM.nextBytes(key);
        return key;
    }

    private static byte[] normalizeKey(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("AES 密钥不能为空");
        }
        if (key.length == 16 || key.length == 24 || key.length == 32) {
            return key;
        }
        // 任意长度输入用 SHA-256 收敛到 256 位
        return HashUtils.sha1Bytes(key);
    }
}
