package com.qutc.launcher.core.loader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 加载器目录的纯逻辑部分：前缀推导、版本比较、类型识别。 */
class LoaderCatalogTest {

    @Test
    void neoForgePrefixFollowsVersioningScheme() {
        assertEquals("20.4.", LoaderCatalog.neoForgePrefix("1.20.4"));
        assertEquals("21.0.", LoaderCatalog.neoForgePrefix("1.21"));
        assertEquals("21.1.", LoaderCatalog.neoForgePrefix("1.21.1"));
        assertEquals("21.8.", LoaderCatalog.neoForgePrefix("1.21.8"));
        assertEquals("20.0.", LoaderCatalog.neoForgePrefix("1.20"));
        assertNull(LoaderCatalog.neoForgePrefix("24w14a"));
        assertNull(LoaderCatalog.neoForgePrefix(null));
    }

    @Test
    void versionComparisonIsNumericAware() {
        assertTrue(LoaderCatalog.compareVersionsDesc("21.1.72", "21.0.7") < 0,
                "21.1.72 应排在 21.0.7 之前（更新）");
        assertTrue(LoaderCatalog.compareVersionsDesc("47.10.0", "47.2.0") < 0,
                "47.10.0 比 47.2.0 新，不能被字符串比较骗了");
        assertTrue(LoaderCatalog.compareVersionsDesc("0.15.11", "0.15.9") < 0);
        assertEquals(0, LoaderCatalog.compareVersionsDesc("1.20.1", "1.20.1"));
        assertTrue(LoaderCatalog.compareVersionsDesc("1.0.0", "1.0.0-beta") < 0,
                "正式版排在预发布版之前");
    }

    @Test
    void loaderKindParsingIsCaseInsensitiveAndValidated() {
        assertEquals(LoaderKind.FABRIC, LoaderKind.parse("fabric"));
        assertEquals(LoaderKind.NEOFORGE, LoaderKind.parse(" NeoForge "));
        assertTrue(LoaderKind.FABRIC.viaMetaProfile());
        assertTrue(LoaderKind.QUILT.viaMetaProfile());
        assertFalse(LoaderKind.FORGE.viaMetaProfile());
        assertThrows(IllegalArgumentException.class, () -> LoaderKind.parse("liteloader"));
        assertThrows(IllegalArgumentException.class, () -> LoaderKind.parse(null));
    }

    @Test
    void kindOfVersionIdRecognisesAllFamilies() {
        assertEquals(LoaderKind.NEOFORGE, LoaderCatalog.kindOfVersionId("1.21.1-neoforge-21.1.72"));
        assertEquals(LoaderKind.FORGE, LoaderCatalog.kindOfVersionId("1.20.1-forge-47.2.0"));
        assertEquals(LoaderKind.FABRIC, LoaderCatalog.kindOfVersionId("fabric-loader-0.15.11-1.20.1"));
        assertEquals(LoaderKind.QUILT, LoaderCatalog.kindOfVersionId("quilt-loader-0.23.1-1.20.1"));
        assertEquals(LoaderKind.OPTIFINE, LoaderCatalog.kindOfVersionId("1.20.1-OptiFine_HD_U_I6"));
        assertNull(LoaderCatalog.kindOfVersionId("1.20.1"));
        assertNull(LoaderCatalog.kindOfVersionId(null));
    }

    @Test
    void forgeCoordinateIncludesGameVersion() {
        LoaderVersion forge = new LoaderVersion(LoaderKind.FORGE, "1.20.1", "47.2.0", "x", null);
        LoaderVersion neoforge = new LoaderVersion(LoaderKind.NEOFORGE, "1.21.1", "21.1.72", "x", null);
        assertEquals("1.20.1-47.2.0", forge.coordinate());
        assertEquals("21.1.72", neoforge.coordinate());
    }
}
