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

class ModScannerTest {

    private final ModScanner scanner = new ModScanner();

    @Test
    void readsFabricMetadataAndMissingDependency(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("example-1.2.3.jar");
        writeJar(jar, "fabric.mod.json", """
                {"schemaVersion":1,"id":"examplemod","name":"Example Mod","version":"1.2.3",
                 "authors":["Alice","Bob"],
                 "depends":{"minecraft":"*","fabricloader":">=0.14","otherlib":">=1.0"}}
                """);
        ModScanner.Report report = scanner.scan(tmp);
        assertEquals(1, report.mods().size());
        ModScanner.Mod mod = report.mods().get(0);
        assertEquals("examplemod", mod.id());
        assertEquals("Example Mod", mod.name());
        assertEquals("1.2.3", mod.version());
        assertEquals(List.of("Alice", "Bob"), mod.authors());
        assertTrue(mod.enabled());
        assertEquals(List.of("fabric"), mod.loaders());
        assertEquals(1, report.missing(mod).size());
        assertEquals("otherlib", report.missing(mod).get(0).modId());
    }

    @Test
    void readsForgeTomlWithMandatoryDependency(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("forgemod.jar");
        writeJar(jar, "META-INF/mods.toml", """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="forgemod"
                version="2.0.0"
                displayName="Forge Mod"
                authors="Carol"
                [[dependencies.forgemod]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"
                [[dependencies.forgemod]]
                modId="missinglib"
                mandatory=true
                versionRange="[1.0,)"
                [[dependencies.forgemod]]
                modId="optionallib"
                mandatory=false
                """);
        ModScanner.Report report = scanner.scan(tmp);
        assertEquals(1, report.mods().size());
        ModScanner.Mod mod = report.mods().get(0);
        assertEquals("forgemod", mod.id());
        assertEquals("Forge Mod", mod.name());
        assertEquals(List.of("forge"), mod.loaders());
        List<String> missing = report.missing(mod).stream()
                .map(ModScanner.Dependency::modId).toList();
        assertEquals(List.of("missinglib"), missing);
    }

    @Test
    void disabledJarIsListedButNotEnabled(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("off.jar.disabled");
        writeJar(jar, "fabric.mod.json",
                "{\"id\":\"offmod\",\"name\":\"Off\",\"version\":\"1.0\",\"depends\":{}}");
        ModScanner.Report report = scanner.scan(tmp);
        assertEquals(1, report.mods().size());
        assertFalse(report.mods().get(0).enabled());
        assertTrue(report.enabled().isEmpty());
    }

    @Test
    void toggleRenamesExtension(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("toggle.jar");
        writeJar(jar, "fabric.mod.json",
                "{\"id\":\"toggle\",\"name\":\"Toggle\",\"version\":\"1.0\",\"depends\":{}}");
        List<ModScanner.Mod> mods = scanner.scan(tmp).mods();
        assertTrue(mods.get(0).enabled());

        Path disabled = scanner.setEnabled(mods.get(0).jar(), false);
        assertEquals("toggle.jar.disabled", disabled.getFileName().toString());
        assertTrue(Files.exists(disabled));
        assertFalse(scanner.scan(tmp).mods().get(0).enabled());

        Path enabled = scanner.setEnabled(disabled, true);
        assertEquals("toggle.jar", enabled.getFileName().toString());
        assertTrue(scanner.scan(tmp).mods().get(0).enabled());
    }

    @Test
    void brokenJarDegradesToUnknownMod(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("garbage.jar"), "not a zip");
        ModScanner.Report report = scanner.scan(tmp);
        assertEquals(1, report.mods().size());
        assertEquals("garbage", report.mods().get(0).name());
        assertEquals(List.of("unknown"), report.mods().get(0).loaders());
    }

    static void writeJar(Path target, String entryName, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
