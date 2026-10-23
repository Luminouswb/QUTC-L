package com.qutc.launcher.core.launch;

import com.qutc.launcher.core.version.ArgumentEntry;
import com.qutc.launcher.core.version.Library;
import com.qutc.launcher.core.version.VersionMeta;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 启动命令组装器（阶段 3 的核心）。
 *
 * <p>铁律在这里落地：
 * <ul>
 *   <li>六个系统属性一条不少（{@code java.library.path} / {@code jna.tmpdir} /
 *       {@code SharedLibraryExtractPath} / {@code netty.native.workdir} /
 *       {@code launcher.brand} / {@code launcher.version}）。</li>
 *   <li>{@code -cp} 必须在主类<b>之前</b>，classpath 分隔符取 {@link File#pathSeparator}。</li>
 *   <li>主类从版本 JSON 读，<b>不硬编码</b>。</li>
 *   <li>同时支持新版 {@code arguments} 与旧版 {@code minecraftArguments}。</li>
 * </ul>
 */
public final class LaunchBuilder {

    private static final Pattern UNKNOWN_VARIABLE = Pattern.compile("\\$\\{[^}]*}");

    private LaunchBuilder() {
    }

    public static LaunchCommand build(LaunchContext ctx) throws IOException {
        if (ctx.meta == null || !ctx.meta.hasMainClass()) {
            throw new IOException("版本 JSON 缺少 mainClass，无法启动");
        }
        if (ctx.javaExecutable == null) {
            throw new IOException("未指定 Java 可执行文件");
        }

        Path tmpRoot = ctx.versionDir.resolve("tmp");
        Path jnaTmp = tmpRoot.resolve("jna");
        Path lwjglTmp = tmpRoot.resolve("lwjgl");
        Path nettyTmp = tmpRoot.resolve("netty");
        for (Path dir : List.of(jnaTmp, lwjglTmp, nettyTmp)) {
            FsUtils.ensureDir(dir);
        }

        String classpath = buildClasspath(ctx);
        Map<String, String> variables = buildVariables(ctx, classpath);

        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Xmx" + Math.max(ctx.memoryMb, 512) + "M");
        jvmArgs.add("-Xms" + Math.min(Math.max(ctx.memoryMb / 2, 256), 1024) + "M");

        // 总纲三.1 的六个强制属性
        jvmArgs.add("-Djava.library.path=" + ctx.nativesDir);
        jvmArgs.add("-Djna.tmpdir=" + jnaTmp);
        jvmArgs.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + lwjglTmp);
        jvmArgs.add("-Dio.netty.native.workdir=" + nettyTmp);
        jvmArgs.add("-Dminecraft.launcher.brand=" + ctx.launcherName);
        jvmArgs.add("-Dminecraft.launcher.version=" + ctx.launcherVersion);
        // 常规加固：Log4Shell 总开关 + 编码
        jvmArgs.add("-Dlog4j2.formatMsgNoLookups=true");
        jvmArgs.add("-Dfile.encoding=UTF-8");
        if (com.qutc.launcher.utils.OsUtils.isMac()) {
            jvmArgs.add("-XstartOnFirstThread");
        }

        // 版本 JSON 自带的 jvm 参数（含条件参数）
        boolean jsonProvidesClasspath = false;
        if (ctx.meta.arguments != null && ctx.meta.arguments.jvm != null) {
            for (ArgumentEntry entry : ctx.meta.arguments.jvm) {
                for (String raw : entry.expand(ctx.rules)) {
                    String value = substitute(raw, variables);
                    if ("-cp".equals(value) || "-classpath".equals(value)) {
                        jsonProvidesClasspath = true;
                    }
                    jvmArgs.add(value);
                }
            }
        }
        if (!jsonProvidesClasspath) {
            jvmArgs.add("-cp");
            jvmArgs.add(classpath);
        }

        for (String extra : ctx.extraJvmArgs) {
            if (extra != null && !extra.isBlank()) {
                jvmArgs.add(extra.trim());
            }
        }

        // 去重但保序：完全相同的参数只留一份；同名的 -D 属性只留先出现的
        // （官方 JSON 也声明了 jna.tmpdir 等，指向 natives 目录，我们用自己的专用临时目录）
        List<String> dedupedJvm = dedupeJvmArgs(jvmArgs);

        List<String> gameArgs = buildGameArguments(ctx, variables);

        List<String> command = new ArrayList<>(dedupedJvm.size() + gameArgs.size() + 2);
        command.add(ctx.javaExecutable.toString());
        command.addAll(dedupedJvm);
        command.add(ctx.meta.mainClass);
        command.addAll(gameArgs);

        Log.debug("Launch", "启动参数组装完成：JVM " + dedupedJvm.size() + " 项，游戏 " + gameArgs.size() + " 项");
        return new LaunchCommand(List.copyOf(command), ctx.meta.mainClass, classpath);
    }

    /**
     * JVM 参数去重。
     * 完全相同的参数只留一份；{@code -Dkey=value} 按 key 去重且保留<b>先出现</b>的那个，
     * 这样启动器自己注入的临时目录不会被官方 JSON 的 {@code -Djna.tmpdir=${natives_directory}} 覆盖。
     */
    static List<String> dedupeJvmArgs(List<String> args) {
        java.util.Set<String> propertyKeys = new java.util.HashSet<>();
        List<String> result = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            // 只对 -D 属性去重：同名属性启动器的值优先（它排在前面）。
            // 绝不能对 --add-opens / --add-exports / -p 这类「选项 + 值」做整体去重，
            // Forge 会合法地重复出现同一个选项（--add-opens 出现两次），去重会破坏参数配对。
            if (arg.startsWith("-D")) {
                int eq = arg.indexOf('=');
                String key = eq > 2 ? arg.substring(0, eq) : arg;
                if (!propertyKeys.add(key)) {
                    continue;
                }
            }
            result.add(arg);
        }
        return result;
    }

    /** classpath：父版本库在前、子版本库在后，客户端 jar 最后。 */    public static String buildClasspath(LaunchContext ctx) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        if (ctx.selection != null) {
            for (Library lib : ctx.selection.classpath()) {
                Library.Artifact artifact = lib.downloads == null ? null : lib.downloads.artifact;
                String relative = lib.resolvePath(artifact);
                if (relative != null && !relative.isBlank()) {
                    entries.add(ctx.librariesDir.resolve(relative).toString());
                }
            }
        }
        if (ctx.clientJar != null) {
            entries.add(ctx.clientJar.toString());
        }
        return String.join(File.pathSeparator, entries);
    }

    private static Map<String, String> buildVariables(LaunchContext ctx, String classpath) {
        Map<String, String> map = new LinkedHashMap<>();
        String uuid = ctx.uuid == null ? "" : ctx.uuid.replace("-", "");
        String token = ctx.accessToken == null || ctx.accessToken.isBlank() ? "0" : ctx.accessToken;

        map.put("auth_player_name", ctx.username == null ? "Player" : ctx.username);
        map.put("auth_uuid", ctx.uuid == null ? "" : ctx.uuid);
        map.put("auth_access_token", token);
        map.put("auth_session", "token:" + token + ':' + uuid);
        map.put("auth_xuid", ctx.xuid == null ? "" : ctx.xuid);
        map.put("clientid", ctx.clientId == null ? "" : ctx.clientId);
        map.put("user_type", ctx.userType == null ? "legacy" : ctx.userType);
        map.put("user_properties", "{}");
        map.put("version_name", ctx.versionId == null ? "" : ctx.versionId);
        map.put("version_type", ctx.meta == null || ctx.meta.type == null ? "release" : ctx.meta.type);
        map.put("game_directory", ctx.gameDir.toString());
        map.put("assets_root", ctx.assetsDir.toString());
        map.put("game_assets", ctx.assetsDir.toString());
        map.put("assets_index_name", ctx.assetIndexName == null ? "legacy" : ctx.assetIndexName);
        map.put("natives_directory", ctx.nativesDir.toString());
        map.put("classpath", classpath);
        map.put("classpath_separator", File.pathSeparator);
        map.put("library_directory", ctx.librariesDir.toString());
        map.put("launcher_name", ctx.launcherName);
        map.put("launcher_version", ctx.launcherVersion);
        map.put("resolution_width", String.valueOf(ctx.width));
        map.put("resolution_height", String.valueOf(ctx.height));
        return map;
    }

    private static List<String> buildGameArguments(LaunchContext ctx, Map<String, String> variables) {
        List<String> args = new ArrayList<>();

        if (ctx.meta.usesModernArguments()) {
            for (ArgumentEntry entry : ctx.meta.arguments.game) {
                for (String raw : entry.expand(ctx.rules)) {
                    args.add(substitute(raw, variables));
                }
            }
        } else if (ctx.meta.minecraftArguments != null && !ctx.meta.minecraftArguments.isBlank()) {
            // 旧格式：先按空白切分模板，再逐项替换，避免路径中的空格把参数撕开
            for (String token : ctx.meta.minecraftArguments.trim().split("\\s+")) {
                args.add(substitute(token, variables));
            }
        }

        if (ctx.customResolution && !args.contains("--width")) {
            args.add("--width");
            args.add(String.valueOf(ctx.width));
            args.add("--height");
            args.add(String.valueOf(ctx.height));
        }
        if (ctx.fullscreen && !args.contains("--fullscreen")) {
            args.add("--fullscreen");
        }
        for (String extra : ctx.extraGameArgs) {
            if (extra != null && !extra.isBlank()) {
                args.add(extra.trim());
            }
        }
        return args;
    }

    /** 变量替换；未声明的变量清空，绝不把 {@code ${x}} 原样喂给游戏。 */
    public static String substitute(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "${" + entry.getKey() + '}';
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, entry.getValue() == null ? "" : entry.getValue());
            }
        }
        if (result.indexOf('$') >= 0) {
            String cleaned = UNKNOWN_VARIABLE.matcher(result).replaceAll("");
            if (!cleaned.equals(result)) {
                Log.debug("Launch", "参数含未声明变量，已清空: " + template);
                result = cleaned;
            }
        }
        return result;
    }

    /** 版本 JSON 是否声明了该参数（用于校验 classpath 是否由 JSON 自带）。 */
    public static boolean declaresArgument(VersionMeta meta, String flag) {
        if (meta == null || meta.arguments == null || meta.arguments.jvm == null) {
            return false;
        }
        for (ArgumentEntry entry : meta.arguments.jvm) {
            if (entry.value != null && entry.value.contains(flag)) {
                return true;
            }
        }
        return false;
    }
}
