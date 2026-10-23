package com.qutc.launcher.ui.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 引用式导入的目录识别（不写登记表，只测识别逻辑）。 */
class VersionFolderImporterTest {

    @Test
    void recognizesWholeMinecraftDirectory(@TempDir Path temp) throws Exception {
        Path root = temp.resolve(".minecraft");
        Path versions = root.resolve("versions");
        createVersion(versions, "1.20.1");
        createVersion(versions, "1.8.9");

        VersionFolderImporter.Plan plan = VersionFolderImporter.inspect(root);
        assertEquals(root, plan.root());
        assertEquals(2, plan.versionIds().size());
        assertTrue(plan.versionIds().containsAll(List.of("1.20.1", "1.8.9")));
    }

    @Test
    void recognizesVersionsDirectoryItself(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("PCL");
        Path versions = root.resolve("versions");
        createVersion(versions, "1.21.4-Fabric");

        VersionFolderImporter.Plan plan = VersionFolderImporter.inspect(versions);
        assertEquals(root, plan.root());
        assertEquals(List.of("1.21.4-Fabric"), plan.versionIds());
    }

    @Test
    void recognizesSingleVersionDirectory(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("官方启动器");
        Path versionDir = root.resolve("versions").resolve("26.1.2-Fabric");
        createVersion(root.resolve("versions"), "26.1.2-Fabric");

        VersionFolderImporter.Plan plan = VersionFolderImporter.inspect(versionDir);
        assertEquals(root, plan.root());
        assertEquals(List.of("26.1.2-Fabric"), plan.versionIds());
    }

    @Test
    void rejectsDirectoryWithoutVersions(@TempDir Path temp) {
        Path empty = temp.resolve("下载");
        assertThrows(Exception.class, () -> {
            Files.createDirectories(empty);
            VersionFolderImporter.inspect(empty);
        });
    }

    private static void createVersion(Path versions, String id) throws Exception {
        Path dir = versions.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), "{\"id\":\"" + id + "\"}");
    }
}
