package com.qutc.launcher.core.diagnose;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 崩溃日志诊断：把常见崩溃特征映射成人话 + 处理建议。
 *
 * <p>阶段 10 的「崩溃匹配表」。规则按「越具体越靠前」排列，同一份日志可能命中多条，
 * 按命中顺序输出，第一条通常就是根因。
 */
public final class CrashAnalyzer {

    /** 严重程度。 */
    public enum Severity {
        FATAL, ERROR, WARN
    }

    /**
     * @param id         规则标识（便于测试与去重）
     * @param title      人话结论
     * @param detail     命中的日志片段（已脱敏）
     * @param suggestion 处理建议
     */
    public record Diagnosis(String id, String title, Severity severity, String detail, String suggestion) {
    }

    private record Rule(String id, Pattern pattern, String title, Severity severity, String suggestion) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("jvm-crash",
                    Pattern.compile("EXCEPTION_ACCESS_VIOLATION|SIGSEGV|hs_err_pid|A fatal error has been detected"),
                    "JVM 级崩溃（显卡驱动或内存故障）",
                    Severity.FATAL,
                    "先找游戏目录下的 hs_err_pid*.log；更新显卡驱动，关闭超频/内存 XMP，"
                            + "把内存调到 4–8 GB 再试；仍崩可换 Java 版本（17 ↔ 21）。"),
            new Rule("out-of-memory",
                    Pattern.compile("java\\.lang\\.OutOfMemoryError|GC overhead limit exceeded"),
                    "内存不足（OutOfMemoryError）",
                    Severity.FATAL,
                    "在「设置 → 内存」把分配调高（纯净端 4 GB，大型整合包 10–16 GB），"
                            + "同时关闭浏览器等占内存程序；不要超过物理内存 60%。"),
            new Rule("class-version",
                    Pattern.compile("UnsupportedClassVersionError|class file version (\\d+)"),
                    "Java 版本不匹配（class 版本过高）",
                    Severity.FATAL,
                    "class file version 52=Java 8、61=Java 17、65=Java 21。"
                            + "到「设置 → Java」换成对应版本，或让启动器自动匹配。"),
            new Rule("missing-dependency",
                    Pattern.compile("Missing or unsupported mandatory dependencies|ModResolutionException|"
                                    + "requires (?:any|at least one) version of|depends on .* which is missing"),
                    "缺少模组前置（依赖未安装）",
                    Severity.ERROR,
                    "按日志里列出的模组名到「模组管理」补齐前置（常见：Fabric API、Architectury API、"
                            + "Cloth Config、Kotlin for Forge）。"),
            new Rule("duplicate-mods",
                    Pattern.compile("DuplicateModsFoundException|duplicate mods?|Found duplicate mod"),
                    "同一模组装了多份",
                    Severity.ERROR,
                    "检查 mods/ 里是否有同一模组的多个版本（含 .disabled），只保留与游戏版本匹配的那一个。"),
            new Rule("mixin-conflict",
                    Pattern.compile("Mixin (?:apply|transformer).*(?:failed|error)|"
                                    + "InvalidMixinException|mixin\\.env\\.MixinException"),
                    "Mixin 注入冲突（模组之间或模组与版本不兼容）",
                    Severity.ERROR,
                    "看日志里 `Mixin apply failed` 后面提到的模组，逐个禁用排查；"
                            + "确认所有模组都支持当前游戏版本与加载器版本。"),
            new Rule("no-such-method",
                    Pattern.compile("NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError"),
                    "模组与游戏/加载器版本不兼容（API 变了）",
                    Severity.ERROR,
                    "把报错模组升级到支持当前游戏版本的版本；Forge/NeoForge 混装、"
                            + "跨大版本搬运模组最容易触发。"),
            new Rule("missing-class",
                    Pattern.compile("NoClassDefFoundError|ClassNotFoundException"),
                    "缺少类库（依赖 jar 没进 classpath 或被误删）",
                    Severity.ERROR,
                    "先「版本管理 → 校验文件」补全库；若是加载器版本，确认安装时没有中断；"
                            + "仍报错请把完整崩溃日志发出来。"),
            new Rule("natives",
                    Pattern.compile("UnsatisfiedLinkError|no .* in java\\.library\\.path|"
                                    + "Failed to locate library|OpenAL.*not found"),
                    "natives 缺失或架构不匹配",
                    Severity.ERROR,
                    "删掉 versions/<版本>/natives 后重新「校验文件」；"
                            + "确认没有把 32 位 Java 用在 64 位系统上。"),
            new Rule("opengl",
                    Pattern.compile("GLFW error|Failed to create window|Pixel format not accelerated|"
                                    + "GL_INVALID|OpenGL.*not supported|Couldn't create (?:window|context)"),
                    "显卡驱动 / OpenGL 不支持",
                    Severity.ERROR,
                    "更新显卡驱动；核显太老时安装 OptiFine 或降级到 1.12.2；"
                            + "笔记本确认游戏跑在独显上（显卡控制面板里指定 java.exe）。"),
            new Rule("already-running",
                    Pattern.compile("Address already in use|already running|Another instance", Pattern.CASE_INSENSITIVE),
                    "游戏/端口被占用",
                    Severity.WARN,
                    "任务管理器结束残留的 javaw.exe；若开了多个启动器实例，只保留一个。"),
            new Rule("auth",
                    Pattern.compile("Invalid session|Failed to verify username|401 Unauthorized|AuthenticationException"),
                    "登录状态失效",
                    Severity.WARN,
                    "到「账号」里重新登录微软账号；离线账号请确认用户名没有与正版冲突。"),
            new Rule("main-class",
                    Pattern.compile("Could not find or load main class|Could not find the main class"),
                    "主类找不到（启动命令或版本 JSON 异常）",
                    Severity.FATAL,
                    "这是启动器侧问题：确认 versions/<版本>/<版本>.json 没被手动改坏，"
                            + "点「校验文件」修复；仍失败请附启动器日志（logs/launcher-*.log）。"),
            new Rule("access-denied",
                    Pattern.compile("AccessDeniedException|Access is denied|权限不够", Pattern.CASE_INSENSITIVE),
                    "文件权限/占用问题",
                    Severity.ERROR,
                    "关闭杀毒软件对该目录的实时防护；确认游戏没在运行导致 jar 被占用；"
                            + "不要把启动器放在 Program Files 下（改放用户目录）。")
    );

    private CrashAnalyzer() {
    }

    /** 扫描文本，返回按命中顺序排列的诊断；没有命中返回空列表。 */
    public static List<Diagnosis> analyze(String logText) {
        List<Diagnosis> result = new ArrayList<>();
        if (logText == null || logText.isBlank()) {
            return result;
        }
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(logText);
            if (matcher.find()) {
                String detail = excerptAt(logText, matcher.start());
                result.add(new Diagnosis(rule.id(), rule.title(), rule.severity(), detail, rule.suggestion()));
            }
        }
        return result;
    }

    /** 判断一段文本是否像崩溃日志（用于自动弹诊断）。 */
    public static boolean looksLikeCrash(String logText) {
        return logText != null && (logText.contains("---- Minecraft Crash Report ----")
                || logText.contains("A fatal error has been detected")
                || logText.contains("Exception in thread")
                || logText.contains("net.fabricmc.loader.impl.FormattedException"));
    }

    /** 取命中行及其后两行，做令牌脱敏。 */
    private static String excerptAt(String text, int index) {
        int start = text.lastIndexOf('\n', index) + 1;
        int end = text.indexOf('\n', index);
        end = end < 0 ? text.length() : end;
        int next = end;
        for (int i = 0; i < 2; i++) {
            int lineEnd = text.indexOf('\n', next + 1);
            if (lineEnd < 0) {
                break;
            }
            next = lineEnd;
        }
        String excerpt = text.substring(start, Math.min(next + 1, text.length()));
        return redact(excerpt);
    }

    private static String redact(String text) {
        return text.replaceAll("(?i)(accessToken[\"']?\\s*[:=]\\s*[\"']?)[^\"'\\s,]+", "$1<已脱敏>")
                .replaceAll("(?i)(refresh_token[=:]\\s*)[^\\s&,]+", "$1<已脱敏>");
    }
}
