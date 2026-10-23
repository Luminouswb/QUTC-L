package com.qutc.launcher.core.launch;

import com.qutc.launcher.utils.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 游戏进程：启动、逐行读日志、退出回调。
 *
 * <p>日志编码不是纯 UTF-8：中文 Windows 下老版本会吐 GBK 字节，
 * 因此逐行按 UTF-8 严格解码，失败回落 GBK，再去掉 ANSI 控制符。
 */
public final class GameProcess {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Charset GBK = Charset.forName("GBK");

    public interface Listener {
        default void onLine(String line) {
        }

        default void onExit(int exitCode, long elapsedMillis) {
        }
    }

    private final Process process;
    private final Path logFile;
    private final long startedAt = System.currentTimeMillis();
    private final List<Consumer<String>> lineSinks = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final AtomicReference<Integer> exitCode = new AtomicReference<>();
    private volatile boolean killedByLauncher;

    private GameProcess(Process process, Path logFile) {
        this.process = process;
        this.logFile = logFile;
    }

    public static GameProcess start(LaunchCommand command, Path workingDirectory, Path logFile) throws IOException {
        return start(command, workingDirectory, logFile, false);
    }

    /**
     * @param quiet true 时把 java.exe 换成同目录的 javaw.exe（UI 模式避免弹出黑色控制台窗口）
     */
    public static GameProcess start(LaunchCommand command, Path workingDirectory, Path logFile, boolean quiet)
            throws IOException {
        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }
        List<String> argv = quiet ? quietCommand(command.command()) : command.command();
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        GameProcess game = new GameProcess(process, logFile);
        game.pump();
        return game;
    }

    /** 把命令行里的 java.exe 换成 javaw.exe（存在才换）。 */
    static List<String> quietCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        List<String> argv = new ArrayList<>(command);
        String exe = argv.get(0);
        if (exe.endsWith("java.exe")) {
            Path javaw = Path.of(exe.substring(0, exe.length() - "java.exe".length()) + "javaw.exe");
            if (Files.isRegularFile(javaw)) {
                argv.set(0, javaw.toString());
            }
        }
        return argv;
    }

    /**
     * 分离启动：子进程直接把 stdout/stderr 写进日志文件，<b>不经过启动器的管道</b>。
     *
     * <p>这一点是必须的：启动器一旦退出（--no-wait、或 UI 被关掉），管道读端消失，
     * 游戏向 stdout 写日志就会拿到「管道已关闭」错误——Minecraft 会因此静默退出，
     * 表现为「双击启动、窗口一闪就没」。改成文件重定向后，启动器退出不影响游戏。
     */
    public static GameProcess startDetached(LaunchCommand command, Path workingDirectory, Path logFile)
            throws IOException {
        if (logFile.getParent() != null) {
            Files.createDirectories(logFile.getParent());
        }
        ProcessBuilder builder = new ProcessBuilder(command.command());
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Process process = builder.start();
        Log.info("Game", "已分离启动，pid=" + process.pid() + "，日志直写 " + logFile);
        return new GameProcess(process, logFile);
    }

    public GameProcess onLine(Consumer<String> sink) {
        if (sink != null) {
            lineSinks.add(sink);
        }
        return this;
    }

    public GameProcess addListener(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    private void pump() {
        Thread reader = new Thread(() -> {
            BufferedWriter writer = null;
            try {
                writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                InputStream input = process.getInputStream();
                BufferedReader reader0 = new BufferedReader(new InputStreamReader(input, StandardCharsets.ISO_8859_1));
                // 用 ISO-8859-1 读原始字节再自行按 UTF-8/GBK 解码，避免双层编码干扰
                String raw;
                while ((raw = reader0.readLine()) != null) {
                    String line = decode(raw);
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                    for (Consumer<String> sink : lineSinks) {
                        try {
                            sink.accept(line);
                        } catch (RuntimeException error) {
                            Log.warn("Game", "日志回调异常", error);
                        }
                    }
                    for (Listener listener : listeners) {
                        try {
                            listener.onLine(line);
                        } catch (RuntimeException error) {
                            Log.warn("Game", "日志监听异常", error);
                        }
                    }
                }
            } catch (IOException error) {
                Log.debug("Game", "日志读取结束: " + error.getMessage());
            } finally {
                closeQuietly(writer);
            }
        }, "qutc-game-log");
        reader.setDaemon(true);
        reader.start();

        Thread waiter = new Thread(() -> {
            try {
                int code = process.waitFor();
                exitCode.set(code);
                long elapsed = System.currentTimeMillis() - startedAt;
                Log.info("Game", "游戏进程退出，退出码 " + code + "，运行 " + elapsed / 1000 + " 秒"
                        + (killedByLauncher ? "（启动器主动结束）" : ""));
                for (Listener listener : listeners) {
                    try {
                        listener.onExit(code, elapsed);
                    } catch (RuntimeException error) {
                        Log.warn("Game", "退出回调异常", error);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "qutc-game-wait");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * ISO-8859-1 的「保字节」字符串 → 真实文本。
     * 先严格 UTF-8，失败回落 GBK；再去 ANSI，最后去尾部 \r。
     */
    static String decode(String latin1) {
        byte[] bytes = latin1.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length == 0) {
            return "";
        }
        String text = tryStrictDecode(bytes, StandardCharsets.UTF_8);
        if (text == null) {
            text = tryStrictDecode(bytes, GBK);
        }
        if (text == null) {
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        return ANSI.matcher(text).replaceAll("").replace("\r", "");
    }

    private static String tryStrictDecode(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    public Path logFile() {
        return logFile;
    }

    /** 阻塞等待退出；返回退出码。 */
    public int waitFor() {
        try {
            int code = process.waitFor();
            exitCode.set(code);
            return code;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    public int waitForSeconds(int seconds) {
        try {
            if (process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) {
                int code = process.exitValue();
                exitCode.set(code);
                return code;
            }
            return Integer.MIN_VALUE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** 结束进程树（Windows 下 taskkill 才会连子进程一起收）。 */
    public void kill() {
        killedByLauncher = true;
        process.destroy();
        if (com.qutc.launcher.utils.OsUtils.isWindows()) {
            try {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(process.pid()))
                        .redirectErrorStream(true)
                        .start()
                        .waitFor();
            } catch (IOException ignored) {
                // 进程可能已退出
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    public Integer exitCode() {
        return exitCode.get();
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
