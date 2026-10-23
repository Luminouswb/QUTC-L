package com.qutc.launcher;

import com.qutc.launcher.utils.AesCrypto;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.HashUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.TempDir;
import com.qutc.launcher.utils.ZipUtils;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 1 底层工具单元测试。
 *
 * <p>这些断言对应总纲里「禁止跳过哈希校验」「解压防穿越」「JSON 可自动修复」
 * 「令牌禁止明文」几条硬要求的可回归验证。
 */
class UtilsTest {

    @Test
    void sha1AndSha256MatchKnownVectors() throws Exception {
        Path file = TempDir.tempFile("hash-", ".txt");
        try {
            Files.writeString(file, "abc", StandardCharsets.UTF_8);
            // 公开测试向量："abc"
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", HashUtils.sha1(file));
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    HashUtils.sha256(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void offlineUuidFollowsVersion3Layout() {
        String uuid = HashUtils.offlineUuid("Notch");
        assertEquals(36, uuid.length());
        assertEquals('3', uuid.charAt(14));
        assertTrue(uuid.charAt(19) == '8' || uuid.charAt(19) == '9'
                || uuid.charAt(19) == 'a' || uuid.charAt(19) == 'b');
    }

    @Test
    void zipSlipIsRejected() {
        Path root = Path.of("C:/tmp/qutc-root").toAbsolutePath();
        assertNull(ZipUtils.resolveSafely(root, "../../evil.txt"));
        assertNull(ZipUtils.resolveSafely(root, "C:/Windows/evil.txt"));
        assertNotNull(ZipUtils.resolveSafely(root, "mods/a.jar"));
    }

    @Test
    void zipRoundTripWorks() throws Exception {
        Path dir = TempDir.tempDir("zip-round-");
        try {
            Path src = dir.resolve("src");
            Files.createDirectories(src.resolve("sub"));
            Files.writeString(src.resolve("sub/a.txt"), "hello");
            Path packed = dir.resolve("p.zip");
            ZipUtils.zipDirectory(src, packed);

            Path out = dir.resolve("out");
            int count = ZipUtils.extract(packed, out, null, 0, null);
            assertTrue(count >= 1);
            assertEquals("hello", Files.readString(out.resolve("sub/a.txt")));
        } finally {
            FsUtils.deleteRecursive(dir);
        }
    }

    @Test
    void corruptJsonIsQuarantinedAndNullReturned() throws Exception {
        Path file = TempDir.tempFile("json-", ".json");
        try {
            Files.writeString(file, "{ this is not json");
            assertNull(JsonUtils.readTree(file));
            assertFalse(Files.exists(file), "损坏文件应被改名留档");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void aesRoundTripDoesNotLeakPlaintext() throws Exception {
        byte[] key = AesCrypto.randomKey(32);
        String secret = "eyJhbGciOiJSUzI1NiJ9.access.token";
        String encrypted = AesCrypto.encryptToBase64(key, secret);
        assertEquals(secret, AesCrypto.decryptFromBase64(key, encrypted));
        assertFalse(encrypted.contains("access"));
    }

    @Test
    void atomicWriteReplacesContent() throws Exception {
        Path dir = TempDir.tempDir("atomic-");
        try {
            Path target = dir.resolve("cfg.json");
            FsUtils.writeAtomic(target, "{\"v\":1}");
            assertEquals("{\"v\":1}", Files.readString(target));
            FsUtils.writeAtomic(target, "{\"v\":2}");
            assertEquals("{\"v\":2}", Files.readString(target));
            try (var stream = Files.list(dir)) {
                assertEquals(1, stream.count(), "原子写不应残留临时文件");
            }
        } finally {
            FsUtils.deleteRecursive(dir);
        }
    }
}
