package com.qutc.launcher;

import com.qutc.launcher.core.java.JavaMatrix;
import com.qutc.launcher.core.java.JavaProbe;
import com.qutc.launcher.core.version.Library;
import com.qutc.launcher.core.version.LibraryCollector;
import com.qutc.launcher.core.version.Rule;
import com.qutc.launcher.core.version.Rules;
import com.qutc.launcher.core.version.VersionMeta;
import com.qutc.launcher.core.version.VersionResolver;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.TempDir;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 2 验证：继承合并、库顺序、规则判定、natives 选择、Java 矩阵。
 */
class VersionEngineTest {

    private static final String PARENT_JSON = """
            {
              "id": "1.20.1",
              "type": "release",
              "mainClass": "net.minecraft.client.main.Main",
              "assetIndex": {"id": "5", "sha1": "s", "size": 1, "totalSize": 2, "url": "https://x/5.json"},
              "assets": "5",
              "javaVersion": {"component": "java-runtime-gamma", "majorVersion": 17},
              "libraries": [
                {"name": "org.ow2.asm:asm:9.3",
                 "downloads": {"artifact": {"path": "org/ow2/asm/asm/9.3/asm-9.3.jar", "url": "https://l/asm-9.3.jar", "size": 1}}},
                {"name": "org.lwjgl:lwjgl:3.3.1",
                 "downloads": {
                   "artifact": {"path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar", "url": "https://l/lwjgl.jar", "size": 1},
                   "classifiers": {
                     "natives-windows": {"path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar", "url": "https://l/lwjgl-nw.jar", "size": 1},
                     "natives-windows-arm64": {"path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows-arm64.jar", "url": "https://l/lwjgl-nwa.jar", "size": 1},
                     "natives-linux": {"path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar", "url": "https://l/lwjgl-nl.jar", "size": 1}
                   }
                 },
                 "natives": {"windows": "natives-windows", "linux": "natives-linux"},
                 "rules": [{"action": "allow"}, {"action": "disallow", "os": {"name": "osx"}}]},
                {"name": "com.mojang:authlib:3.11.49",
                 "downloads": {"artifact": {"path": "com/mojang/authlib/3.11.49/authlib.jar", "url": "https://l/authlib.jar", "size": 1}}}
              ],
              "arguments": {
                "game": ["--username", {"rules": [{"action": "allow", "features": {"has_custom_resolution": true}}], "value": ["--width", "854"]}, "--version"],
                "jvm": ["-Djava.library.path=${natives_directory}"]
              }
            }
            """;

    private static final String CHILD_JSON = """
            {
              "id": "forge-49.0.1",
              "type": "release",
              "inheritsFrom": "1.20.1",
              "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
              "libraries": [
                {"name": "org.ow2.asm:asm:9.5",
                 "downloads": {"artifact": {"path": "org/ow2/asm/asm/9.5/asm-9.5.jar", "url": "https://l/asm-9.5.jar", "size": 1}}},
                {"name": "net.minecraftforge:forge:49.0.1",
                 "downloads": {"artifact": {"path": "net/minecraftforge/forge/49.0.1/forge.jar", "url": "https://l/forge.jar", "size": 1}}}
              ],
              "arguments": {
                "game": ["--launchTarget", "forgeclient"],
                "jvm": ["-DlegacyClassPath=${classpath_separator}"]
              }
            }
            """;

    @Test
    void inheritanceMergeKeepsParentLibrariesFirst() throws Exception {
        Path dir = newVersionsDir("qutc-versions-");
        try {
            writeVersion(dir, "1.20.1", PARENT_JSON);
            writeVersion(dir, "forge-49.0.1", CHILD_JSON);

            VersionResolver resolver = new VersionResolver(dir);
            VersionMeta merged = resolver.resolve("forge-49.0.1");

            // 主类必须被子版本覆盖
            assertEquals("cpw.mods.bootstraplauncher.BootstrapLauncher", merged.mainClass);
            // assetIndex 从父版本继承
            assertEquals("5", merged.assetIndex.id);

            List<Library> libraries = merged.safeLibraries();
            assertEquals(4, libraries.size(), "父 3 + 子新增 1，重复项去重");

            // 铁律：父在前、子在后
            assertEquals("org.ow2.asm:asm:9.5", libraries.get(0).name,
                    "同名库去重后应保留子版本内容，且仍在父版本的位置");
            assertEquals("org.lwjgl:lwjgl:3.3.1", libraries.get(1).name);
            assertEquals("com.mojang:authlib:3.11.49", libraries.get(2).name);
            assertEquals("net.minecraftforge:forge:49.0.1", libraries.get(3).name,
                    "子版本新增库必须在最后");

            List<Library> parents = List.of(
                    parsed(dir, "1.20.1").safeLibraries().get(0),
                    parsed(dir, "1.20.1").safeLibraries().get(1),
                    parsed(dir, "1.20.1").safeLibraries().get(2));
            List<Library> children = parsed(dir, "forge-49.0.1").safeLibraries();
            assertTrue(VersionResolver.isParentFirst(libraries, parents, children),
                    "合并结果必须满足父版本库全部位于子版本独有库之前");

            // 参数：父在前、子在后（子版本新增 2 条，共 5 条）
            assertEquals(5, merged.arguments.game.size());
            assertEquals("--username", merged.arguments.game.get(0).value.get(0));
            assertEquals("--version", merged.arguments.game.get(2).value.get(0));
            assertEquals("--launchTarget", merged.arguments.game.get(3).value.get(0));
            assertEquals(2, merged.arguments.jvm.size());
        } finally {
            FsUtils.deleteRecursive(dir);
        }
    }

    /**
     * 1.8.9 的坑：同一个版本 JSON 里会同时列 {@code lwjgl:2.9.4-nightly}（通用）与
     * {@code lwjgl:2.9.2-nightly}（仅 osx）。单版本内部若做去重，2.9.2 会顶掉 2.9.4，
     * 再被规则丢弃，classpath 里就没有 lwjgl 了。
     */
    @Test
    void duplicateLibrariesWithinOneVersionAreKeptForRuleFiltering() throws Exception {
        Path dir = newVersionsDir("qutc-dup-");
        try {
            writeVersion(dir, "1.8.9", """
                    {
                      "id": "1.8.9",
                      "mainClass": "net.minecraft.client.main.Main",
                      "libraries": [
                        {"name": "org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209",
                         "rules": [{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}],
                         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar"}}},
                        {"name": "org.lwjgl.lwjgl:lwjgl:2.9.2-nightly-20140822",
                         "rules": [{"action":"allow","os":{"name":"osx"}}],
                         "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/lwjgl/2.9.2-nightly-20140822/lwjgl-2.9.2-nightly-20140822.jar"}}}
                      ]
                    }
                    """);
            VersionResolver resolver = new VersionResolver(dir);
            VersionMeta merged = resolver.resolve("1.8.9");
            assertEquals(2, merged.safeLibraries().size(), "单版本内部的重复坐标不能合并");

            LibraryCollector.Selection selection =
                    LibraryCollector.select(merged, Rules.Context.system());
            assertEquals(1, selection.classpath().size());
            assertEquals("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209",
                    selection.classpath().get(0).name, "Windows 上必须留下通用版 lwjgl");
        } finally {
            FsUtils.deleteRecursive(dir);
        }
    }

    @Test
    void inheritanceLoopIsDetected() throws Exception {
        Path dir = newVersionsDir("qutc-loop-");
        try {
            writeVersion(dir, "a", "{\"id\":\"a\",\"inheritsFrom\":\"b\",\"mainClass\":\"A\"}");
            writeVersion(dir, "b", "{\"id\":\"b\",\"inheritsFrom\":\"a\",\"mainClass\":\"B\"}");
            VersionResolver resolver = new VersionResolver(dir);
            VersionResolver.VersionException error = assertThrows(VersionResolver.VersionException.class,
                    () -> resolver.resolve("a"));
            assertTrue(error.getMessage().contains("环路"), error.getMessage());
        } finally {
            FsUtils.deleteRecursive(dir);
        }
    }

    @Test
    void missingMainClassIsRejected() throws Exception {
        Path dir = newVersionsDir("qutc-nomain-");
        try {
            writeVersion(dir, "broken", "{\"id\":\"broken\",\"libraries\":[]}");
            VersionResolver resolver = new VersionResolver(dir);
            assertThrows(VersionResolver.VersionException.class, () -> resolver.resolve("broken"));
        } finally {
            FsUtils.deleteRecursive(dir);
        }
    }

    @Test
    void rulesRespectOsAndFeatures() {
        Rules.Context windows = new Rules.Context("windows", "10.0.22631", "x64", Map.of());
        Rules.Context linux = new Rules.Context("linux", "6.5.0", "x64", Map.of());
        Rules.Context armWindows = new Rules.Context("windows", "10.0.22631", "arm64", Map.of());

        // 空规则 = 允许
        assertTrue(Rules.allows(List.of(), windows));

        // 只写 allow 的规则：命中即允许，未命中按默认不允许
        var winOnly = List.of(rule("allow", "windows", null, null));
        assertTrue(Rules.allows(winOnly, windows));
        assertFalse(Rules.allows(winOnly, linux));

        // 无条件 disallow 会覆盖前面的 allow（官方就是按顺序覆盖）
        var winThenDenyAll = List.of(rule("allow", "windows", null, null), rule("disallow", null, null, null));
        assertFalse(Rules.allows(winThenDenyAll, windows));

        // 版本号正则
        var win11Only = List.of(rule("allow", "windows", "^10\\.0\\.2[2-9]", null));
        assertTrue(Rules.allows(win11Only, windows));
        assertFalse(Rules.allows(win11Only, new Rules.Context("windows", "10.0.19045", "x64", Map.of())));

        // 架构
        var x64Only = List.of(rule("allow", null, null, "x64"));
        assertTrue(Rules.allows(x64Only, windows));
        assertFalse(Rules.allows(x64Only, armWindows));

        // 特性开关
        var demoOnly = new com.qutc.launcher.core.version.ArgumentEntry();
        demoOnly.rules = List.of(featureRule(true));
        demoOnly.value = List.of("--demo");
        assertFalse(demoOnly.applies(windows));
        assertTrue(demoOnly.applies(new Rules.Context("windows", "10", "x64",
                Rules.features(true, false, false, false, false))));
        assertNotNull(Rules.Context.system());
    }

    @Test
    void nativesAreSelectedPerPlatformAndArch() throws Exception {
        Path dir = newVersionsDir("qutc-natives-");
        try {
            writeVersion(dir, "1.20.1", PARENT_JSON);
            VersionMeta meta = new VersionResolver(dir).resolve("1.20.1");

            Rules.Context win64 = new Rules.Context("windows", "10.0.22631", "x64", Map.of());
            LibraryCollector.Selection selection = LibraryCollector.select(meta, win64);
            assertEquals(3, selection.classpath().size(), "三个库都有普通 jar");
            assertEquals(1, selection.natives().size());
            assertEquals("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar",
                    selection.natives().get(0).artifact().path);

            Rules.Context winArm = new Rules.Context("windows", "10.0.22631", "arm64", Map.of());
            LibraryCollector.Selection armSelection = LibraryCollector.select(meta, winArm);
            assertEquals("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows-arm64.jar",
                    armSelection.natives().get(0).artifact().path,
                    "ARM64 必须优先选 natives-windows-arm64");

            Rules.Context linux = new Rules.Context("linux", "6.5.0", "x64", Map.of());
            LibraryCollector.Selection linuxSelection = LibraryCollector.select(meta, linux);
            assertEquals("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux.jar",
                    linuxSelection.natives().get(0).artifact().path);

            Rules.Context mac = new Rules.Context("osx", "14.0", "arm64", Map.of());
            LibraryCollector.Selection macSelection = LibraryCollector.select(meta, mac);
            assertTrue(macSelection.natives().isEmpty(), "规则 disallow osx 的库不应被选中");
            assertEquals(2, macSelection.classpath().size());
        } finally {
            FsUtils.deleteRecursive(dir);
        }
    }

    @Test
    void javaMajorParsingHandlesLegacyAndModern() {
        assertEquals(8, JavaProbe.majorOf("1.8.0_381"));
        assertEquals(7, JavaProbe.majorOf("1.7.0_80"));
        assertEquals(9, JavaProbe.majorOf("9.0.4"));
        assertEquals(17, JavaProbe.majorOf("17.0.10"));
        assertEquals(21, JavaProbe.majorOf("21.0.12"));
        assertEquals(25, JavaProbe.majorOf("25.0.1"));
        assertEquals(0, JavaProbe.majorOf(""));
    }

    @Test
    void javaVersionComparisonIsNumericAware() {
        assertTrue(JavaProbe.compareVersion("21.0.12.1", "21.0.7") > 0,
                "补丁号必须按数值比较，21.0.12.1 高于 21.0.7");
        assertTrue(JavaProbe.compareVersion("1.8.0_381", "1.8.0_51") > 0);
        assertTrue(JavaProbe.compareVersion("17.0.10", "17.0.9") > 0);
        assertEquals(0, JavaProbe.compareVersion("21.0.2", "21.0.2"));
        assertTrue(JavaProbe.compareVersion("21.0.1", "21.0.1-ea") > 0,
                "正式版应排在 EA 之前");
    }

    @Test
    void javaMatrixMatchesSpecTable() {
        assertEquals(8, JavaMatrix.recommendMajor("1.8.9"));
        assertEquals(8, JavaMatrix.recommendMajor("1.11.2"));
        assertEquals(8, JavaMatrix.recommendMajor("1.12.2"));
        assertEquals(8, JavaMatrix.recommendMajor("1.16.5"));
        assertEquals(17, JavaMatrix.recommendMajor("1.17.1"));
        assertEquals(17, JavaMatrix.recommendMajor("1.18.2"));
        assertEquals(17, JavaMatrix.recommendMajor("1.20.1"));
        assertEquals(17, JavaMatrix.recommendMajor("1.20.4"));
        assertEquals(21, JavaMatrix.recommendMajor("1.20.5"));
        assertEquals(21, JavaMatrix.recommendMajor("1.21.4"));
        assertEquals(25, JavaMatrix.recommendMajor("26.1"));
        assertEquals(21, JavaMatrix.recommendMajor("24w14a"));
        assertFalse(JavaMatrix.compatible("1.20.5", 17));
        assertTrue(JavaMatrix.compatible("1.20.5", 21));
        assertTrue(JavaMatrix.compatible("1.16.5", 17));
    }

    // ---- 辅助 ----

    private static Rule rule(String action, String osName, String osVersion, String arch) {
        Rule rule = new Rule();
        rule.action = action;
        if (osName != null || osVersion != null || arch != null) {
            rule.os = new Rule.OsCondition();
            rule.os.name = osName;
            rule.os.version = osVersion;
            rule.os.arch = arch;
        }
        return rule;
    }

    private static Rule featureRule(boolean demo) {
        Rule rule = new Rule();
        rule.action = "allow";
        rule.features = Map.of("is_demo_user", demo);
        return rule;
    }

    private static Path newVersionsDir(String prefix) throws Exception {
        return TempDir.tempDir(prefix);
    }

    private static void writeVersion(Path versionsDir, String id, String json) throws Exception {
        Path dir = versionsDir.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), json, StandardCharsets.UTF_8);
    }

    private static VersionMeta parsed(Path dir, String id) throws Exception {
        return new VersionResolver(dir).loadLocal(id);
    }
}
