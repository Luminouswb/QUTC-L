package com.qutc.launcher.auth;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.utils.AesCrypto;
import com.qutc.launcher.utils.Dpapi;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 凭据封存：把明文令牌变成可落盘的密文。
 *
 * <p>总纲铁律「禁止明文存储账号令牌」的实现点：
 * <ol>
 *   <li>Windows 且 DPAPI 可用 → {@code CryptProtectData}（CurrentUser 作用域），
 *       密钥由用户账户派生，拷到别的机器/别的用户都解不开；</li>
 *   <li>否则 → AES-GCM，密钥是本机随机 32 字节，落在 {@code accounts/secret.key}，
 *       权限尽力收紧并打 WARN——安全性弱于 DPAPI，但绝不是明文。</li>
 * </ol>
 * 两种方案都失败时<b>抛异常</b>，宁可不让用户登录，也不明文落盘。
 */
public final class SecretBox {

    public record Sealed(String scheme, String payload) {
    }

    public static final String SCHEME_DPAPI = "dpapi";
    public static final String SCHEME_AES = "aes";

    private final Path keyFile;
    private final boolean allowDpapi;
    private byte[] aesKey;

    public SecretBox(Path keyFile, boolean allowDpapi) {
        this.keyFile = keyFile;
        this.allowDpapi = allowDpapi;
    }

    public static SecretBox defaults() {
        return new SecretBox(AppPaths.accountsDir().resolve("secret.key"), true);
    }

    /** 加密；两种方案都不可用时抛 IOException（绝不返回明文）。 */
    public Sealed seal(String plaintext) throws IOException {
        if (plaintext == null) {
            return null;
        }
        byte[] raw = plaintext.getBytes(StandardCharsets.UTF_8);
        if (allowDpapi && Dpapi.isAvailable()) {
            byte[] protectedBytes = Dpapi.protect(raw);
            if (protectedBytes != null && protectedBytes.length > 0) {
                return new Sealed(SCHEME_DPAPI, Base64.getEncoder().encodeToString(protectedBytes));
            }
            Log.warn("SecretBox", "DPAPI 加密失败，改用 AES 兜底");
        }
        try {
            String payload = AesCrypto.encryptToBase64(key(), plaintext);
            return new Sealed(SCHEME_AES, payload);
        } catch (Exception e) {
            throw new IOException("无法加密账号凭据（DPAPI 与 AES 均失败）", e);
        }
    }

    /** 解密；失败返回 null，调用方按「没有凭据」处理并引导重新登录。 */
    public String open(Sealed sealed) {
        if (sealed == null || sealed.payload() == null) {
            return null;
        }
        try {
            if (SCHEME_DPAPI.equals(sealed.scheme())) {
                byte[] plain = Dpapi.unprotect(Base64.getDecoder().decode(sealed.payload()));
                return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
            }
            if (SCHEME_AES.equals(sealed.scheme())) {
                return AesCrypto.decryptFromBase64(key(), sealed.payload());
            }
        } catch (Exception e) {
            Log.warn("SecretBox", "凭据解密失败（换机/换用户/密钥丢失），需要重新登录", e);
            return null;
        }
        Log.warn("SecretBox", "未知的凭据封装方案: " + sealed.scheme());
        return null;
    }

    private synchronized byte[] key() throws IOException {
        if (aesKey != null) {
            return aesKey;
        }
        if (keyFile != null && Files.isRegularFile(keyFile)) {
            byte[] existing = Files.readAllBytes(keyFile);
            if (existing.length >= 16) {
                aesKey = existing;
                return aesKey;
            }
        }
        byte[] created = AesCrypto.randomKey(32);
        if (keyFile != null) {
            FsUtils.ensureDir(keyFile.getParent());
            Files.write(keyFile, created);
            try {
                Files.setAttribute(keyFile, "dos:hidden", true);
            } catch (Exception ignored) {
                // 非 Windows 或文件系统不支持：忽略即可，不影响加密本身
            }
            Log.warn("SecretBox", "DPAPI 不可用，AES 密钥已生成于 " + keyFile + "（请勿泄露该文件）");
        }
        aesKey = created;
        return aesKey;
    }
}
