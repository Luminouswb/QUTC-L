package com.qutc.launcher.core.launch;

import com.qutc.launcher.core.version.ArgumentEntry;
import com.qutc.launcher.core.version.Library;
import com.qutc.launcher.core.version.LibraryCollector;
import com.qutc.launcher.core.version.Rules;
import com.qutc.launcher.core.version.VersionMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动命令组装的硬约束测试：不联网、不启动进程。
 */
class LaunchBuilderTest {

    private static Library library(String name, String path, String sha1, long size) {
        Library lib = new Library();
        lib.name = name;
        lib.downloads = new Library.Downloads();
        lib.downloads.artifact = new Library.Artifact();
        lib.downloads.artifact.path = path;
        lib.downloads.artifact.sha1 = sha1;
        lib.downloads.artifact.size = size;
        lib.downloads.artifact.url = "https://libraries.minecraft.net/" + path;
        return lib;
    }

    private static VersionMeta modernMeta() {
        VersionMeta meta = new VersionMeta();
        meta.id = "1.20.1";
        meta.type = "release";
        meta.mainClass = "net.minecraft.client.main.Main";
        meta.assetIndex = new VersionMeta.AssetIndexRef();
        meta.assetIndex.id = "5";
        meta.arguments = VersionMeta.VersionArguments.empty();
        meta.arguments.jvm = List.of(
                ArgumentEntry.plain("-Djava.library.path=${natives_directory}"),
                ArgumentEntry.plain("-Djna.tmpdir=${natives_directory}"),
                ArgumentEntry.plain("-cp"),
                ArgumentEntry.plain("${classpath}"));
        meta.arguments.game = List.of(
                ArgumentEntry.plain("--username"),
                ArgumentEntry.plain("${auth_player_name}"),
                ArgumentEntry.plain("--version"),
                ArgumentEntry.plain("${version_name}"),
                ArgumentEntry.plain("--gameDir"),
                ArgumentEntry.plain("${game_directory}"),
                ArgumentEntry.plain("--assetsDir"),
                ArgumentEntry.plain("${assets_root}"),
                ArgumentEntry.plain("--assetIndex"),
                ArgumentEntry.plain("${assets_index_name}"),
                ArgumentEntry.plain("--uuid"),
                ArgumentEntry.plain("${auth_uuid}"),
                ArgumentEntry.plain("--accessToken"),
                ArgumentEntry.plain("${auth_access_token}"),
                ArgumentEntry.plain("--userType"),
                ArgumentEntry.plain("${user_type}"));
        return meta;
    }

    private static LaunchContext context(Path root, VersionMeta meta, List<Library> libs) {
        Path libraries = root.resolve("libraries");
        Path versionDir = root.resolve("versions/1.20.1");
        LaunchContext ctx = new LaunchContext()
                .version("1.20.1", meta, Rules.Context.system(Map.of("has_custom_resolution", false)),
                        new LibraryCollector.Selection(libs, List.of()))
                .java(root.resolve("jdk/bin/java.exe"), 4096)
                .directories(root.resolve("game"), libraries, root.resolve("assets"), "5",
                        versionDir.resolve("natives"))
                .account("Steve", "069a79f4-44e9-4726-a5be-fca90e38aaf5", "0", "legacy")
                .display(0, 0, false)
                .extras(List.of(), List.of());
        ctx.versionDir = versionDir;
        ctx.clientJar = root.resolve("versions/1.20.1/1.20.1.jar");
        return ctx;
    }

    @Test
    void nativeJarsForOtherArchitecturesAreFilteredFromClasspath() {
        VersionMeta meta = new VersionMeta();
        meta.id = "1.20.1";
        meta.libraries = new java.util.ArrayList<>();
        for (String classifier : List.of("natives-windows", "natives-windows-arm64", "natives-windows-x86",
                "natives-linux", "natives-macos")) {
            Library lib = library("org.lwjgl:lwjgl-glfw:3.3.1:" + classifier,
                    "org/lwjgl/lwjgl-glfw/3.3.1/lwjgl-glfw-3.3.1-" + classifier + ".jar", "cc", 5);
            String osName = classifier.contains("linux") ? "linux"
                    : classifier.contains("macos") ? "osx" : "windows";
            lib.rules = List.of(allowOs(osName));
            meta.libraries.add(lib);
        }
        LibraryCollector.Selection selection = LibraryCollector.select(meta, Rules.Context.system());
        List<String> names = selection.classpath().stream().map(lib -> lib.name).toList();
        assertEquals(List.of("org.lwjgl:lwjgl-glfw:3.3.1:natives-windows"), names,
                "x64 上只应保留 natives-windows");
    }

    private static com.qutc.launcher.core.version.Rule allowOs(String osName) {
        com.qutc.launcher.core.version.Rule rule = new com.qutc.launcher.core.version.Rule();
        rule.action = "allow";
        rule.os = new com.qutc.launcher.core.version.Rule.OsCondition();
        rule.os.name = osName;
        return rule;
    }

    @Test
    void duplicateSystemPropertiesKeepLauncherValues(@TempDir Path root) throws Exception {
        VersionMeta meta = modernMeta();
        meta.arguments.jvm = List.of(
                ArgumentEntry.plain("-Djna.tmpdir=${natives_directory}"),
                ArgumentEntry.plain("-cp"),
                ArgumentEntry.plain("${classpath}"));
        LaunchCommand cmd = LaunchBuilder.build(context(root, meta, List.of()));
        List<String> jnaArgs = cmd.command().stream().filter(a -> a.startsWith("-Djna.tmpdir=")).toList();
        assertEquals(1, jnaArgs.size(), "同名 -D 只能保留一个");
        assertTrue(jnaArgs.get(0).contains("tmp"), "应保留启动器自己的临时目录: " + jnaArgs.get(0));
    }

    /**
     * Forge 1.20.1 的启动参数里 {@code --add-opens} / {@code --add-exports} 会合法地出现两次，
     * 参数去重绝不能把它们折叠掉——否则 JVM 会把第二个值当成主类（真实踩过的 BUG）。
     */
    @Test
    void repeatedJvmOptionsKeepTheirValues(@TempDir Path root) throws Exception {
        VersionMeta meta = modernMeta();
        meta.mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher";
        meta.arguments.jvm = List.of(
                ArgumentEntry.plain("-cp"),
                ArgumentEntry.plain("${classpath}"),
                ArgumentEntry.plain("--add-opens"),
                ArgumentEntry.plain("java.base/java.util.jar=cpw.mods.securejarhandler"),
                ArgumentEntry.plain("--add-opens"),
                ArgumentEntry.plain("java.base/java.lang.invoke=cpw.mods.securejarhandler"),
                ArgumentEntry.plain("--add-exports"),
                ArgumentEntry.plain("java.base/sun.security.util=cpw.mods.securejarhandler"),
                ArgumentEntry.plain("--add-exports"),
                ArgumentEntry.plain("jdk.naming.dns/com.sun.jndi.dns=java.naming"));

        LaunchCommand cmd = LaunchBuilder.build(context(root, meta, List.of()));
        List<String> args = cmd.command();

        assertEquals(2, (int) args.stream().filter("--add-opens"::equals).count(),
                "重复的 --add-opens 必须各留一份");
        assertEquals(2, (int) args.stream().filter("--add-exports"::equals).count());
        int first = args.indexOf("--add-opens");
        assertEquals("java.base/java.util.jar=cpw.mods.securejarhandler", args.get(first + 1),
                "选项与它的值必须紧邻");
        int second = -1;
        for (int i = first + 1; i < args.size(); i++) {
            if ("--add-opens".equals(args.get(i))) {
                second = i;
                break;
            }
        }
        assertTrue(second > first, "第二个 --add-opens 不能丢");
        assertEquals("java.base/java.lang.invoke=cpw.mods.securejarhandler", args.get(second + 1));
        assertEquals("cpw.mods.bootstraplauncher.BootstrapLauncher", args.get(cmd.mainClassIndex()));
    }

    @Test
    void classpathStaysBeforeMainClassAndUsesPlatformSeparator(@TempDir Path root) throws Exception {
        List<Library> libs = List.of(
                library("org.lwjgl:lwjgl:3.3.1", "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar", "aa", 10),
                library("com.mojang:authlib:4.0.43", "com/mojang/authlib/4.0.43/authlib-4.0.43.jar", "bb", 20));
        LaunchCommand cmd = LaunchBuilder.build(context(root, modernMeta(), libs));

        int cpIndex = cmd.command().indexOf("-cp");
        int mainIndex = cmd.mainClassIndex();
        assertTrue(cpIndex > 0, "必须显式带 -cp");
        assertTrue(cpIndex < mainIndex, "classpath 必须在主类之前");
        assertEquals("-cp", cmd.command().get(cpIndex));
        String classpath = cmd.command().get(cpIndex + 1);
        assertTrue(classpath.contains("lwjgl-3.3.1.jar"), classpath);
        assertTrue(classpath.contains("authlib-4.0.43.jar"), classpath);
        assertTrue(classpath.contains(File.pathSeparator), "分隔符必须是平台分隔符");
        assertTrue(classpath.endsWith("1.20.1.jar"), "客户端 jar 必须在 classpath 末尾");
        assertEquals("net.minecraft.client.main.Main", cmd.mainClass());
    }

    @Test
    void sixMandatorySystemPropertiesArePresent(@TempDir Path root) throws Exception {
        VersionMeta meta = modernMeta();
        // 故意让 JSON 不声明任何 -D，验证启动器自己补齐
        meta.arguments.jvm = List.of(ArgumentEntry.plain("-cp"), ArgumentEntry.plain("${classpath}"));
        LaunchCommand cmd = LaunchBuilder.build(context(root, meta, List.of()));

        List<String> args = cmd.command();
        assertTrue(args.stream().anyMatch(a -> a.startsWith("-Djava.library.path=")), "-Djava.library.path");
        assertTrue(args.stream().anyMatch(a -> a.startsWith("-Djna.tmpdir=")), "-Djna.tmpdir");
        assertTrue(args.stream().anyMatch(a -> a.startsWith("-Dorg.lwjgl.system.SharedLibraryExtractPath=")),
                "lwjgl 解压目录");
        assertTrue(args.stream().anyMatch(a -> a.startsWith("-Dio.netty.native.workdir=")), "netty 工作目录");
        assertTrue(args.contains("-Dminecraft.launcher.brand=QUTC"), "启动器品牌");
        assertTrue(args.stream().anyMatch(a -> a.startsWith("-Dminecraft.launcher.version=")), "启动器版本");
        assertTrue(args.contains("-Xmx4096M"), "内存参数");
    }

    @Test
    void modernArgumentsAreExpandedAndNoPlaceholderLeaks(@TempDir Path root) throws Exception {
        LaunchCommand cmd = LaunchBuilder.build(context(root, modernMeta(), List.of()));
        List<String> args = cmd.command();
        for (String arg : args) {
            assertFalse(arg.contains("${"), "参数里不能残留占位符: " + arg);
        }
        int userIndex = args.indexOf("--username");
        assertEquals("Steve", args.get(userIndex + 1));
        int dirIndex = args.indexOf("--gameDir");
        assertNotNull(dirIndex);
        assertTrue(args.get(dirIndex + 1).endsWith("game"), args.get(dirIndex + 1));
        int indexIndex = args.indexOf("--assetIndex");
        assertEquals("5", args.get(indexIndex + 1));
    }

    @Test
    void legacyArgumentsSplitBeforeSubstitutionSoSpacesSurvive(@TempDir Path root) throws Exception {
        VersionMeta meta = new VersionMeta();
        meta.id = "1.8.9";
        meta.type = "release";
        meta.mainClass = "net.minecraft.client.main.Main";
        meta.assets = "1.8";
        meta.minecraftArguments = "--username ${auth_player_name} --gameDir ${game_directory} "
                + "--assetsDir ${assets_root} --assetIndex ${assets_index_name} "
                + "--uuid ${auth_uuid} --accessToken ${auth_access_token} --versionType ${version_type}";

        Path gameRoot = root.resolve("game dir with spaces");
        LaunchContext ctx = new LaunchContext()
                .version("1.8.9", meta, Rules.Context.system(), new LibraryCollector.Selection(List.of(), List.of()))
                .java(root.resolve("jdk/bin/java.exe"), 2048)
                .directories(gameRoot, root.resolve("libraries"), root.resolve("assets"), "1.8",
                        root.resolve("versions/1.8.9/natives"))
                .account("Alex", "00000000-0000-3000-8000-000000000000", "0", "legacy")
                .display(0, 0, false)
                .extras(List.of(), List.of());
        ctx.versionDir = root.resolve("versions/1.8.9");

        LaunchCommand cmd = LaunchBuilder.build(ctx);
        List<String> args = cmd.command();
        int dirIndex = args.indexOf("--gameDir");
        assertTrue(dirIndex > 0, "旧格式也要有 --gameDir");
        assertEquals(gameRoot.toString(), args.get(dirIndex + 1),
                "含空格的路径必须保持为单个参数");
        assertTrue(args.contains("--versionType"));
        int typeIndex = args.indexOf("--versionType");
        assertEquals("release", args.get(typeIndex + 1));
    }

    @Test
    void customResolutionAndFullscreenAreAppendedOnce(@TempDir Path root) throws Exception {
        LaunchContext ctx = context(root, modernMeta(), List.of());
        ctx.display(1280, 720, true);
        LaunchCommand cmd = LaunchBuilder.build(ctx);
        List<String> args = cmd.command();
        assertEquals(1, args.stream().filter("--width"::equals).count());
        assertEquals("1280", args.get(args.indexOf("--width") + 1));
        assertEquals(1, args.stream().filter("--fullscreen"::equals).count());
    }

    @Test
    void substitutionDropsUnknownVariables() {
        String out = LaunchBuilder.substitute("-Dfoo=${unknown_variable}", Map.of("known", "1"));
        assertEquals("-Dfoo=", out);
    }

    @Test
    void latin1DecodingSurvivesUtf8AndGbkLogs() {
        String utf8 = "[12:00:00] [Client thread/INFO]: 正在加载世界";
        String latin1 = new String(utf8.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals(utf8, com.qutc.launcher.core.launch.GameProcess.decode(latin1));

        String gbkText = "启动失败：找不到 Java";
        String gbkBytes = new String(gbkText.getBytes(java.nio.charset.Charset.forName("GBK")),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals(gbkText, com.qutc.launcher.core.launch.GameProcess.decode(gbkBytes));

        String ansi = "\u001B[32m[12:00:00]\u001B[0m done";
        assertEquals("[12:00:00] done", com.qutc.launcher.core.launch.GameProcess.decode(ansi));
    }

    @Test
    void quoteWrapsPathsWithSpaces() {
        assertEquals("\"C:\\Program Files\\Java\\bin\\java.exe\"",
                LaunchCommand.quote("C:\\Program Files\\Java\\bin\\java.exe"));
        assertEquals("-Xmx2G", LaunchCommand.quote("-Xmx2G"));
    }

    @Test
    void buildingWithoutMainClassFails(@TempDir Path root) {
        VersionMeta meta = modernMeta();
        meta.mainClass = null;
        LaunchContext ctx = context(root, meta, List.of());
        boolean threw = false;
        try {
            LaunchBuilder.build(ctx);
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "缺 mainClass 必须拒绝启动");
    }

    @Test
    void temporaryDirectoriesAreCreated(@TempDir Path root) throws Exception {
        LaunchContext ctx = context(root, modernMeta(), List.of());
        LaunchBuilder.build(ctx);
        assertTrue(Files.isDirectory(ctx.versionDir.resolve("tmp/jna")));
        assertTrue(Files.isDirectory(ctx.versionDir.resolve("tmp/lwjgl")));
        assertTrue(Files.isDirectory(ctx.versionDir.resolve("tmp/netty")));
    }
}
