package com.qutc.launcher.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 统一日志系统。
 *
 * <p>设计要点：
 * <ul>
 *   <li>调用方永不阻塞：入队即返回，落盘由独立守护线程完成。</li>
 *   <li>内存环形缓冲保留最近 {@value #RING_CAPACITY} 条，供 UI 日志页回放。</li>
 *   <li>监听器机制让 UI 实时订阅，不轮询。</li>
 *   <li>落盘按天切分，UTF-8，追加写，退出时 flush。</li>
 * </ul>
 */
public final class Log {

    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR;

        public boolean atLeast(Level other) {
            return ordinal() >= other.ordinal();
        }

        public static Level parse(String text, Level fallback) {
            if (text == null) {
                return fallback;
            }
            for (Level value : values()) {
                if (value.name().equalsIgnoreCase(text.trim())) {
                    return value;
                }
            }
            return fallback;
        }
    }

    /** 一条日志记录，同时用于内存回放与 UI 渲染。 */
    public record Entry(long epochMillis, Level level, String tag, String message) {

        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        public String format() {
            LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
            return TIME.format(time) + " [" + level + "] [" + tag + "] " + message;
        }
    }

    private static final int RING_CAPACITY = 8000;
    private static final int QUEUE_CAPACITY = 20000;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final BlockingQueue<Entry> QUEUE = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private static final Deque<Entry> RING = new ArrayDeque<>(RING_CAPACITY);
    private static final List<Consumer<Entry>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Object RING_LOCK = new Object();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private static volatile Level minLevel = Level.INFO;
    private static volatile Path logDir;
    private static volatile BufferedWriter writer;
    private static volatile LocalDate writerDate;

    private Log() {
    }

    public static void init(Path directory, Level level) {
        minLevel = level == null ? Level.INFO : level;
        logDir = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException ignored) {
            // 目录不可写时退化为仅控制台 + 内存，不影响主流程
        }
        if (STARTED.compareAndSet(false, true)) {
            Thread thread = new Thread(Log::drainLoop, "qutc-log-writer");
            thread.setDaemon(true);
            thread.start();
            Runtime.getRuntime().addShutdownHook(new Thread(Log::flushAndStop, "qutc-log-shutdown"));
        }
        info("Log", "日志系统已启动，目录=" + directory + "，级别=" + minLevel);
    }

    public static Path getLogDir() {
        return logDir;
    }

    public static void setLevel(Level level) {
        minLevel = level == null ? Level.INFO : level;
    }

    public static Level getLevel() {
        return minLevel;
    }

    public static void addListener(Consumer<Entry> listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Consumer<Entry> listener) {
        LISTENERS.remove(listener);
    }

    /** 返回最近日志快照，供 UI 初始化时回放。 */
    public static List<Entry> snapshot() {
        synchronized (RING_LOCK) {
            return List.copyOf(RING);
        }
    }

    public static void trace(String tag, String message) {
        accept(Level.TRACE, tag, message, null);
    }

    public static void debug(String tag, String message) {
        accept(Level.DEBUG, tag, message, null);
    }

    public static void info(String tag, String message) {
        accept(Level.INFO, tag, message, null);
    }

    public static void warn(String tag, String message) {
        accept(Level.WARN, tag, message, null);
    }

    public static void warn(String tag, String message, Throwable throwable) {
        accept(Level.WARN, tag, message, throwable);
    }

    public static void error(String tag, String message) {
        accept(Level.ERROR, tag, message, null);
    }

    public static void error(String tag, String message, Throwable throwable) {
        accept(Level.ERROR, tag, message, throwable);
    }

    private static void accept(Level level, String tag, String message, Throwable throwable) {
        if (!level.atLeast(minLevel)) {
            return;
        }
        String text = message == null ? "" : message;
        if (throwable != null) {
            text = text + " | " + throwable.getClass().getSimpleName() + ": " + safeMessage(throwable);
        }
        Entry entry = new Entry(System.currentTimeMillis(), level, tag == null ? "-" : tag, text);

        synchronized (RING_LOCK) {
            if (RING.size() >= RING_CAPACITY) {
                RING.pollFirst();
            }
            RING.addLast(entry);
        }

        for (Consumer<Entry> listener : LISTENERS) {
            try {
                listener.accept(entry);
            } catch (RuntimeException ignored) {
                // 监听器异常不得影响日志主链路
            }
        }

        if (!QUEUE.offer(entry)) {
            // 队列满：丢弃最旧的一条，保证不阻塞业务线程
            QUEUE.poll();
            QUEUE.offer(entry);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? "(无消息)" : message;
    }

    private static void drainLoop() {
        while (true) {
            try {
                Entry entry = QUEUE.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (entry != null) {
                    writeEntry(entry);
                    while (!QUEUE.isEmpty()) {
                        Entry next = QUEUE.poll();
                        if (next != null) {
                            writeEntry(next);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                // 落盘失败不影响后续
            }
        }
    }

    private static void writeEntry(Entry entry) {
        String line = entry.format();
        System.out.println(line);
        Path directory = logDir;
        if (directory == null) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            if (writer == null || !today.equals(writerDate)) {
                closeWriter();
                Files.createDirectories(directory);
                Path file = directory.resolve("launcher-" + FILE_STAMP.format(today) + ".log");
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                writerDate = today;
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException ignored) {
            // 磁盘满/占用时静默，保证游戏启动不被日志拖死
        }
    }

    private static void closeWriter() {
        BufferedWriter current = writer;
        writer = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static void flushAndStop() {
        try {
            Entry entry;
            while ((entry = QUEUE.poll()) != null) {
                writeEntry(entry);
            }
        } finally {
            closeWriter();
        }
    }
}
