package com.qutc.launcher.core.modpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModpackImporterTest {

    private final ModpackImporter importer = new ModpackImporter(null);

    private static final String INDEX = """
            {
              "formatVersion": 1,
              "game": "minecraft",
              "versionId": "1.0.0",
              "name": "Demo Pack",
              "summary": "只是一个测试整合包",
              "files": [
                {"path": "mods/example.jar", "hashes": {"sha1": "abc"},
                 "downloads": ["https://example.com/example.jar"], "fileSize": 1024},
                {"path": "config/demo.toml", "hashes": {}, "downloads": [], "fileSize": 12}
              ],
              "dependencies": {"minecraft": "1.20.1", "fabric-loader": "0.15.11"}
            }
            """;

    @Test
    void readsIndexFromMrpack(@TempDir Path tmp) throws IOException {
        Path mrpack = tmp.resolve("demo.mrpack");
        writeZip(mrpack, "modrinth.index.json", INDEX, "overrides/options.txt", "test");
        ModpackImporter.Index index = importer.readIndex(mrpack);
        assertEquals("Demo Pack", index.name());
        assertEquals("1.20.1", index.minecraft());
        assertEquals("fabric-loader", index.loaderKind());
        assertEquals("0.15.11", index.loaderVersion());
        assertEquals("1.0.0", index.versionId());
    }

    @Test
    void readsFileListWithHashes(@TempDir Path tmp) throws IOException {
        Path mrpack = tmp.resolve("demo.mrpack");
        writeZip(mrpack, "modrinth.index.json", INDEX);
        List<ModpackImporter.FileSpec> files = importer.readFiles(mrpack);
        assertEquals(2, files.size());
        assertEquals("mods/example.jar", files.get(0).path());
        assertEquals("abc", files.get(0).sha1());
        assertEquals(1024, files.get(0).size());
        assertEquals("https://example.com/example.jar", files.get(0).downloads().get(0));
    }

    @Test
    void rejectsNonMrpackZip(@TempDir Path tmp) throws IOException {
        Path mrpack = tmp.resolve("plain.zip");
        writeZip(mrpack, "manifest.json", "{}");
        IOException error = assertThrows(IOException.class, () -> importer.readIndex(mrpack));
        assertTrue(error.getMessage().contains("modrinth.index.json"));
    }

    @Test
    void rejectsUnsupportedFormatVersion(@TempDir Path tmp) throws IOException {
        Path mrpack = tmp.resolve("v2.mrpack");
        writeZip(mrpack, "modrinth.index.json",
                "{\"formatVersion\":2,\"name\":\"x\",\"dependencies\":{\"minecraft\":\"1.20.1\"}}");
        assertThrows(IOException.class, () -> importer.readIndex(mrpack));
    }

    @Test
    void rejectsPackWithoutMinecraftDependency(@TempDir Path tmp) throws IOException {
        Path mrpack = tmp.resolve("nover.mrpack");
        writeZip(mrpack, "modrinth.index.json",
                "{\"formatVersion\":1,\"name\":\"x\",\"dependencies\":{\"fabric-loader\":\"0.15.11\"}}");
        assertThrows(IOException.class, () -> importer.readIndex(mrpack));
    }

    static void writeZip(Path target, String... entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i + 1 < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
