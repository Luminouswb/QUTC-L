package com.qutc.launcher.core.mods;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackScannerTest {

    private final PackScanner scanner = new PackScanner();

    @Test
    void readsResourcePackFormatFromZip(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("NicePack.zip");
        writeZip(zip, "pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"demo\"}}");
        List<PackScanner.Pack> packs = scanner.scan(tmp, PackScanner.Kind.RESOURCEPACK);
        assertEquals(1, packs.size());
        PackScanner.Pack pack = packs.get(0);
        assertEquals("NicePack", pack.name());
        assertEquals(15, pack.packFormat());
        assertTrue(pack.enabled());
        assertTrue(pack.formatText().contains("15"));
    }

    @Test
    void disabledPackIsListedAsDisabled(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("Old.zip.disabled");
        writeZip(zip, "pack.mcmeta", "{\"pack\":{\"pack_format\":8}}");
        List<PackScanner.Pack> packs = scanner.scan(tmp, PackScanner.Kind.RESOURCEPACK);
        assertEquals(1, packs.size());
        assertFalse(packs.get(0).enabled());
    }

    @Test
    void shaderPackWithoutPackMetaStillListed(@TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("BSL.zip");
        writeZip(zip, "shaders/shader.fsh", "// shader");
        List<PackScanner.Pack> packs = scanner.scan(tmp, PackScanner.Kind.SHADER);
        assertEquals(1, packs.size());
        assertEquals("BSL", packs.get(0).name());
        assertEquals("光影包", packs.get(0).formatText());
    }

    @Test
    void directoryPackAndToggleWork(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("DirPack");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":34}}");
        List<PackScanner.Pack> packs = scanner.scan(tmp, PackScanner.Kind.RESOURCEPACK);
        assertEquals(1, packs.size());
        assertEquals(34, packs.get(0).packFormat());

        Path renamed = scanner.setEnabled(packs.get(0), false);
        assertEquals("DirPack.disabled", renamed.getFileName().toString());
        assertTrue(Files.isDirectory(renamed));
    }

    static void writeZip(Path target, String entryName, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
