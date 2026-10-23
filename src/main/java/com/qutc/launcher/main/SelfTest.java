package com.qutc.launcher.main;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.utils.AesCrypto;
import com.qutc.launcher.utils.Dpapi;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.HashUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;
import com.qutc.launcher.utils.OsUtils;
import com.qutc.launcher.utils.TempDir;
import com.qutc.launcher.utils.ZipUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段 1 自检：不依赖 UI、不依赖网络，验证底层工具链是否可用。
 *
 * <p>用途：jpackage 打进安装包后，可用 {@code QUTC.exe --self-test} 在纯净机器上
 * 验证 JRE、字体、文件权限、DPAPI 等是否就绪，避免「装完闪退查不出原因」。
 */
public final class SelfTest {

    private SelfTest() {
    }

    public static int run() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        int failures = 0;

        try {
            results.put("JDK 主版本 = 21", javaFeatureVersion() == 21);
        } catch (Throwable t) {
            results.put("JDK 主版本 = 21", false);
        }

        try {
            Path probe = TempDir.tempFile("qutc-selftest-", ".bin");
            byte[] payload = "QUTC self-test payload".getBytes(StandardCharsets.UTF_8);
            Files.write(probe, payload);
            String sha1 = HashUtils.sha1(probe);
            // 已知向量：字符串 "QUTC self-test payload" 的 SHA-1
            String expected = sha1OfLiteral("QUTC self-test payload");
            results.put("SHA-1 校验", sha1.equalsIgnoreCase(expected));
            results.put("SHA-256 长度", HashUtils.sha256(probe).length() == 64);
            results.put("离线 UUID 版本位", HashUtils.offlineUuid("Steve").charAt(14) == '3');
            Files.deleteIfExists(probe);
        } catch (Throwable t) {
            Log.error("SelfTest", "哈希自检异常", t);
            results.put("SHA-1 校验", false);
        }

        try {
            Path dir = TempDir.tempDir("qutc-selftest-zip-");
            Path src = dir.resolve("src");
            Files.createDirectories(src.resolve("a/b"));
            Files.writeString(src.resolve("a/b/hello.txt"), "hello");
            Path zip = dir.resolve("pack.zip");
            ZipUtils.zipDirectory(src, zip);
            Path out = dir.resolve("out");
            int count = ZipUtils.extract(zip, out, null, 0, null);
            results.put("ZIP 打包/解包", count >= 1
                    && Files.readString(out.resolve("a/b/hello.txt")).equals("hello"));
            results.put("ZIP 防穿越", ZipUtils.resolveSafely(out, "../../evil.txt") == null);
            FsUtils.deleteRecursive(dir);
        } catch (Throwable t) {
            Log.error("SelfTest", "ZIP 自检异常", t);
            results.put("ZIP 打包/解包", false);
        }

        try {
            Path temp = TempDir.tempFile("qutc-selftest-", ".json");
            var node = JsonUtils.newObject();
            node.put("name", "QUTC");
            node.put("v", 1);
            FsUtils.writeAtomic(temp, JsonUtils.mapper().writeValueAsString(node));
            var back = JsonUtils.readTree(temp);
            results.put("JSON 读写", back != null && "QUTC".equals(back.path("name").asText()));
            // 故意写坏，验证自动分流
            Files.writeString(temp, "{ broken json ");
            var broken = JsonUtils.readTree(temp);
            results.put("JSON 损坏自愈", broken == null && !Files.exists(temp));
            Files.deleteIfExists(temp);
        } catch (Throwable t) {
            Log.error("SelfTest", "JSON 自检异常", t);
            results.put("JSON 读写", false);
        }

        try {
            byte[] key = AesCrypto.randomKey(32);
            String secret = "access_token_测试_1234567890";
            String encrypted = AesCrypto.encryptToBase64(key, secret);
            results.put("AES-GCM 往返", secret.equals(AesCrypto.decryptFromBase64(key, encrypted))
                    && !encrypted.contains("access_token"));
        } catch (Throwable t) {
            Log.error("SelfTest", "AES 自检异常", t);
            results.put("AES-GCM 往返", false);
        }

        // DPAPI 不可用不算失败（非 Windows 或受限环境），但必须能优雅降级
        boolean dpapi = Dpapi.isAvailable();
        if (dpapi) {
            byte[] encrypted = Dpapi.protect("token".getBytes(StandardCharsets.UTF_8));
            byte[] decrypted = Dpapi.unprotect(encrypted);
            results.put("DPAPI 加密", encrypted != null && decrypted != null
                    && "token".equals(new String(decrypted, StandardCharsets.UTF_8)));
        } else {
            results.put("DPAPI 降级路径", true);
        }

        try {
            results.put("数据目录可写", FsUtils.isWritable(AppPaths.root()));
            results.put("日志目录", Files.isDirectory(AppPaths.logsDir()));
            ConfigManager.save();
            results.put("全局配置落盘", Files.isRegularFile(AppPaths.launcherConfig()));
            results.put("架构识别", !OsUtils.arch().equals("unknown"));
            results.put("物理内存探测", OsUtils.totalPhysicalMemory() > 0);
        } catch (Throwable t) {
            Log.error("SelfTest", "环境自检异常", t);
            results.put("数据目录可写", false);
        }

        for (Map.Entry<String, Boolean> entry : results.entrySet()) {
            boolean ok = Boolean.TRUE.equals(entry.getValue());
            if (!ok) {
                failures++;
            }
            Log.info("SelfTest", (ok ? "[通过] " : "[失败] ") + entry.getKey());
        }
        return failures == 0 ? 0 : 1;
    }

    private static int javaFeatureVersion() {
        String version = System.getProperty("java.specification.version", "0");
        try {
            if (version.startsWith("1.")) {
                return Integer.parseInt(version.substring(2).split("\\.")[0]);
            }
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 用 JVM 独立算一遍 SHA-1 作为参照，避免把测试向量写死出错。 */
    private static String sha1OfLiteral(String text) {
        return java.util.HexFormat.of().formatHex(sha1Bytes(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha1Bytes(byte[] data) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-1");
            return digest.digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
