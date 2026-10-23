package com.qutc.launcher.core.launch;

import java.util.List;

/**
 * 组装完成的启动命令。
 *
 * <p>{@link #command()} 已经是可直接交给 {@code ProcessBuilder} 的完整参数表：
 * 第 0 项是 java 可执行文件，其余依次为 JVM 参数、{@code -cp}、主类、游戏参数。
 */
public record LaunchCommand(List<String> command, String mainClass, String classpath) {

    /** 便于日志与 dry-run 展示：带引号的单行命令。 */
    public String display() {
        StringBuilder sb = new StringBuilder(command.size() * 24);
        for (String part : command) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(quote(part));
        }
        return sb.toString();
    }

    /** Windows 命令行引号规则：含空格或特殊字符时用双引号包裹并转义内部引号。 */
    public static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        boolean needsQuote = value.isEmpty();
        for (int i = 0; i < value.length() && !needsQuote; i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '"' || c == '&' || c == '|' || c == '<' || c == '>' || c == '^') {
                needsQuote = true;
            }
        }
        if (!needsQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\\\"") + '"';
    }

    /** 主类在参数表中的下标，便于断言「classpath 必须在主类之前」。 */
    public int mainClassIndex() {
        return command.indexOf(mainClass);
    }
}
