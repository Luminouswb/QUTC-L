package com.qutc.launcher;

import com.qutc.launcher.update.UpdateService;
import com.qutc.launcher.utils.HashUtils;
import com.qutc.launcher.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateServiceTest {

    @Test
    void versionCompareIsNumeric() {
        assertTrue(UpdateService.isNewer("0.2.0", "0.1.0"));
        assertTrue(UpdateService.isNewer("0.10.0", "0.9.9"));
        assertTrue(UpdateService.isNewer("1.0", "0.99.99"));
        assertTrue(UpdateService.isNewer("v0.1.1", "0.1.0"));
        assertFalse(UpdateService.isNewer("0.1.0", "0.1.0"));
        assertFalse(UpdateService.isNewer("0.0.9", "0.1.0"));
    }

    @Test
    void manifestParsesAndPicksMainJar() throws IOException {
        String json = """
                {
                  "version": "0.2.0",
                  "notes": "修复若干问题",
                  "files": [
                    {"path": "readme.txt", "url": "https://example.com/readme.txt", "sha256": "aa", "size": 10},
                    {"path": "qutc-launcher.jar", "url": "https://example.com/qutc-launcher-0.2.0.jar",
                     "sha256": "bb", "size": 12345}
                  ]
                }
                """;
        UpdateService.Manifest manifest = JsonUtils.parse(json.getBytes(StandardCharsets.UTF_8),
                UpdateService.Manifest.class);
        assertEquals("0.2.0", manifest.version);
        assertEquals(2, manifest.files.size());
        UpdateService.FileEntry entry = UpdateService.mainJar(manifest);
        assertEquals("qutc-launcher.jar", entry.path);
        assertEquals("https://example.com/qutc-launcher-0.2.0.jar", entry.url);
        assertEquals(12345, entry.size);
    }

    @Test
    void manifestWithoutJarIsRejected() {
        UpdateService.Manifest manifest = new UpdateService.Manifest();
        manifest.version = "0.2.0";
        assertThrows(IOException.class, () -> UpdateService.mainJar(manifest));
    }

    @Test
    void sha256Verification(@TempDir Path tempDir) throws IOException {
        Path jar = tempDir.resolve("qutc-launcher.jar");
        Files.writeString(jar, "fake jar content", StandardCharsets.UTF_8);
        String sha = HashUtils.sha256(jar);
        assertTrue(UpdateService.verify(jar, new UpdateService.FileEntry("qutc-launcher.jar", null, sha, -1)));
        assertFalse(UpdateService.verify(jar, new UpdateService.FileEntry("qutc-launcher.jar", null, "deadbeef", -1)));
        assertTrue(UpdateService.verify(jar, new UpdateService.FileEntry("qutc-launcher.jar", null, null, -1)));
    }
}
