package com.qutc.launcher.core.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 安装器解包：现代/古老两种布局 + maven 铺设 + 防路径穿越。 */
class ForgeInstallerTest {

    @TempDir
    Path dir;

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private Path zip(String name, String... entriesAndContent) throws IOException {
        Path jar = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i + 1 < entriesAndContent.length; i += 2) {
                put(zip, entriesAndContent[i], entriesAndContent[i + 1]);
            }
        }
        return jar;
    }

    @Test
    void extractsModernLayoutAndCopiesMaven() throws Exception {
        Path jar = zip("forge-installer.jar",
                "version.json", "{\"id\":\"1.20.1-forge-47.2.0\",\"inheritsFrom\":\"1.20.1\","
                        + "\"mainClass\":\"cpw.mods.bootstraplauncher.BootstrapLauncher\",\"libraries\":[]}",
                "maven/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar", "fake-jar");

        Path versions = dir.resolve("versions");
        Path libraries = dir.resolve("libraries");
        ForgeInstaller.Result result = ForgeInstaller.extract(jar, versions, libraries);

        assertEquals("1.20.1-forge-47.2.0", result.versionId());
        assertTrue(result.modernLayout());
        assertEquals(1, result.mavenFiles());

        Path json = versions.resolve("1.20.1-forge-47.2.0").resolve("1.20.1-forge-47.2.0.json");
        assertTrue(Files.isRegularFile(json));
        assertTrue(Files.readString(json).contains("BootstrapLauncher"));
        assertTrue(Files.isRegularFile(libraries.resolve(
                "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar")));
    }

    @Test
    void extractsLegacyInstallProfileLayout() throws Exception {
        String profile = "{\"install\":{\"path\":\"net.minecraftforge:forge:1.7.10-10.13.4.1614\"},"
                + "\"versionInfo\":{\"id\":\"1.7.10-Forge10.13.4.1614-1.7.10\",\"inheritsFrom\":\"1.7.10\","
                + "\"mainClass\":\"net.minecraft.launchwrapper.Launch\",\"libraries\":[]}}";
        Path jar = zip("forge-1.7.10-installer.jar",
                "install_profile.json", profile,
                "maven/net/minecraftforge/forge/1.7.10-10.13.4.1614/forge-1.7.10-10.13.4.1614-universal.jar", "old");

        Path versions = dir.resolve("versions");
        Path libraries = dir.resolve("libraries");
        ForgeInstaller.Result result = ForgeInstaller.extract(jar, versions, libraries);

        assertEquals("1.7.10-Forge10.13.4.1614-1.7.10", result.versionId());
        assertFalse(result.modernLayout());
        assertTrue(Files.isRegularFile(versions.resolve("1.7.10-Forge10.13.4.1614-1.7.10")
                .resolve("1.7.10-Forge10.13.4.1614-1.7.10.json")));
        assertTrue(Files.isRegularFile(libraries.resolve(
                "net/minecraftforge/forge/1.7.10-10.13.4.1614/forge-1.7.10-10.13.4.1614-universal.jar")));
    }

    @Test
    void rejectsPathTraversalInMavenEntries() throws Exception {
        Path jar = zip("evil-installer.jar",
                "version.json", "{\"id\":\"1.20.1-evil-1.0\",\"libraries\":[]}",
                "maven/../../evil.txt", "escaped");

        Path versions = dir.resolve("versions");
        Path libraries = dir.resolve("outside-target").resolve("libraries");
        ForgeInstaller.Result result = ForgeInstaller.extract(jar, versions, libraries);

        assertEquals(0, result.mavenFiles(), "越界条目必须被丢弃");
        assertFalse(Files.exists(dir.resolve("evil.txt")), "不能写到库目录之外");
        assertFalse(Files.exists(dir.resolve("outside-target").resolve("evil.txt")));
    }

    @Test
    void missingVersionJsonFailsClearly() throws Exception {
        Path jar = zip("broken-installer.jar", "readme.txt", "not an installer");
        IOException error = assertThrows(IOException.class,
                () -> ForgeInstaller.extract(jar, dir.resolve("versions"), dir.resolve("libraries")));
        assertTrue(error.getMessage().contains("install_profile.json"), "错误信息应指出缺哪个文件");
    }
}
