package com.qutc.launcher.core.loader;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.utils.FsUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 加载器识别（含 OptiFine 纯净/混搭）。 */
class LoaderDetectorTest {

    private static void writeVersionJson(String versionId, String json) throws IOException {
        Path file = AppPaths.versionJson(versionId);
        FsUtils.ensureDir(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    @Test
    void fabricVersionJsonIsDetected() throws IOException {
        String id = "1.20.1-fabric-0.15.11-test";
        writeVersionJson(id, "{\"id\":\"" + id + "\",\"inheritsFrom\":\"1.20.1\","
                + "\"mainClass\":\"net.fabricmc.loader.impl.launch.knot.KnotClient\","
                + "\"libraries\":[{\"name\":\"net.fabricmc:fabric-loader:0.15.11\"}]}");
        LoaderDetector.Detection detection = LoaderDetector.detect(id);
        assertEquals(LoaderKind.FABRIC, detection.primary());
        assertFalse(detection.optifine());
        assertTrue(detection.summary().contains("Fabric"));
    }

    @Test
    void optifineModOnForgeIsDetectedAsMixed() throws IOException {
        String id = "1.20.1-forge-47.2.0-test";
        writeVersionJson(id, "{\"id\":\"" + id + "\",\"inheritsFrom\":\"1.20.1\","
                + "\"mainClass\":\"cpw.mods.bootstraplauncher.BootstrapLauncher\","
                + "\"libraries\":[{\"name\":\"net.minecraftforge:forge:1.20.1-47.2.0\"}]}");
        Path mods = AppPaths.modsDir(id, true);
        FsUtils.ensureDir(mods);
        Files.writeString(mods.resolve("OptiFine_1.20.1_HD_U_I6.jar"), "fake", StandardCharsets.UTF_8);

        LoaderDetector.Detection detection = LoaderDetector.detect(id);
        assertEquals(LoaderKind.FORGE, detection.primary());
        assertTrue(detection.optifine());
        assertTrue(detection.optifineSource().contains("OptiFine_1.20.1_HD_U_I6.jar"));
        assertTrue(detection.summary().startsWith("版本 " + id + "：Forge + OptiFine"));
        assertTrue(detection.summary().contains("mods/OptiFine_1.20.1_HD_U_I6.jar"));
    }

    @Test
    void optifineStandaloneVersionJsonIsDetected() throws IOException {
        String id = "1.20.1-OptiFineTest";
        writeVersionJson(id, "{\"id\":\"" + id + "\",\"inheritsFrom\":\"1.20.1\","
                + "\"libraries\":[{\"name\":\"optifine:OptiFine:1.20.1_HD_U_I6\"}]}");
        LoaderDetector.Detection detection = LoaderDetector.detect(id);
        assertTrue(detection.optifine());
        assertEquals("版本 JSON", detection.optifineSource());
        assertEquals("版本 " + id + "：OptiFine", detection.summary(), "OptiFine 独立版不应重复显示 + OptiFine");
    }

    @Test
    void versionIdAloneIsEnoughWhenJsonMissing() {
        LoaderDetector.Detection fabric = LoaderDetector.detect("1.21.1-fabric-loader-0.16.0");
        assertEquals(LoaderKind.FABRIC, fabric.primary());

        LoaderDetector.Detection quilt = LoaderDetector.detect("1.21.1-quilt-loader-0.26.0");
        assertEquals(LoaderKind.QUILT, quilt.primary());

        LoaderDetector.Detection neoforge = LoaderDetector.detect("1.21.1-neoforge-21.1.72");
        assertEquals(LoaderKind.NEOFORGE, neoforge.primary());
    }

    @Test
    void quiltVersionWithFabricCompatIsStillQuilt() throws IOException {
        String id = "quilt-loader-0.23.0-1.20.1-test";
        writeVersionJson(id, "{\"id\":\"" + id + "\",\"inheritsFrom\":\"1.20.1\","
                + "\"libraries\":[{\"name\":\"org.quiltmc:quilt-loader:0.23.0\"},"
                + "{\"name\":\"net.fabricmc:sponge-mixin:0.12.5\"}]}");
        LoaderDetector.Detection detection = LoaderDetector.detect(id);
        assertEquals(LoaderKind.QUILT, detection.primary(), "Quilt 内嵌 Fabric 兼容层，不能被误判成 Fabric");
        assertTrue(detection.found().contains(LoaderKind.FABRIC), "同时也应识别出内嵌的 Fabric 兼容层");
    }

    @Test
    void vanillaVersionHasNoLoader() {
        LoaderDetector.Detection detection = LoaderDetector.detect("1.20.1-definitely-vanilla");
        assertFalse(detection.hasLoader());
        assertFalse(detection.optifine());
        assertEquals("版本 1.20.1-definitely-vanilla：原版", detection.summary());
    }
}
