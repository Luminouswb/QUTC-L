package com.qutc.launcher.download;

import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.HashUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工业级并发下载器（阶段 4）。
 *
 * <p>能力清单，逐条对应总纲 §三.2：
 * <ul>
 *   <li><b>多源降级</b>：{@link FileTask#urls()} 是按优先级排好的候选地址（由
 *       {@link MirrorResolver} 生成），一个源失败自动试下一个；</li>
 *   <li><b>连接 / 读取超时分开</b>：{@link HttpURLConnection} 的
 *       {@code setConnectTimeout} 与 {@code setReadTimeout}；</li>
 *   <li><b>指数退避</b>：同一地址 1s → 2s → 4s 三轮；</li>
 *   <li><b>4xx 快速切换</b>：404/403/410 不做无意义重试，直接换源；</li>
 *   <li><b>断点续传</b>：{@code .part} + {@code Range}，服务端不认 Range 就整份重下；</li>
 *   <li><b>SHA-1 / SHA-256 双重校验</b>：给了哪个校验哪个，两个都给就都校验；</li>
 *   <li><b>原子落盘</b>：校验通过后 {@code ATOMIC_MOVE} 改名，杜绝半截文件。</li>
 * </ul>
 */
public final class Downloader {

    /** 同一地址的重试退避（总纲：1s → 2s → 4s 三轮）。 */
    private static final long[] BACKOFF_MILLIS = {1000L, 2000L, 4000L};

    /** 连接超时：只覆盖 TCP 建连。 */
    public static final int CONNECT_TIMEOUT_MS = 15_000;
    /** 读取超时：覆盖两次读之间的间隔，防止半死不活的镜像把下载挂住。 */
    public static final int READ_TIMEOUT_MS = 30_000;

    private static final String USER_AGENT = "QUTC-Launcher/0.1.0 (Minecraft launcher)";
    private static final int BUFFER = 64 * 1024;

    /** 退避序列；测试可注入 0 延迟。 */
    private final long[] backoff;

    public Downloader() {
        this(BACKOFF_MILLIS);
    }

    Downloader(long[] backoff) {
        this.backoff = backoff == null || backoff.length == 0 ? BACKOFF_MILLIS : backoff;
    }

    /**
     * 一个待下载文件。
     *
     * @param urls   候选地址，按优先级排列（非空）
     * @param target 落盘路径
     * @param sha1   期望 SHA-1，可为 null
     * @param sha256 期望 SHA-256，可为 null
     * @param size   期望大小，未知传 -1
     */
    public record FileTask(List<String> urls, Path target, String sha1, String sha256, long size) {

        public static FileTask of(String url, Path target, String sha1, long size) {
            return new FileTask(List.of(url), target, sha1, null, size);
        }

        public static FileTask of(String url, Path target, String sha1, String sha256, long size) {
            return new FileTask(List.of(url), target, sha1, sha256, size);
        }

        public static FileTask mirrors(List<String> urls, Path target, String sha1, long size) {
            return new FileTask(List.copyOf(urls), target, sha1, null, size);
        }

        public static FileTask mirrors(List<String> urls, Path target, String sha1, String sha256, long size) {
            return new FileTask(List.copyOf(urls), target, sha1, sha256, size);
        }

        /** 首选地址，日志与失败报告用。 */
        public String url() {
            return urls.isEmpty() ? "" : urls.get(0);
        }

        public boolean hasHash() {
            return (sha1 != null && !sha1.isBlank()) || (sha256 != null && !sha256.isBlank());
        }
    }

    public record Progress(int totalFiles, int doneFiles, long totalBytes, long doneBytes,
                           String currentFile, long bytesPerSecond) {
        public int percent() {
            if (totalBytes <= 0) {
                return totalFiles <= 0 ? 0 : (int) (doneFiles * 100L / totalFiles);
            }
            return (int) Math.min(100L, doneBytes * 100L / totalBytes);
        }
    }

    /** 失败项：记录最后尝试的地址，便于用户判断是源的问题还是文件的问题。 */
    public record Failure(FileTask task, String url, String reason) {
    }

    public interface Listener {
        void onProgress(Progress progress);
    }

    public List<Failure> download(List<FileTask> tasks, int concurrency, Listener listener) {
        List<FileTask> pending = new ArrayList<>();
        long skippedBytes = 0;
        for (FileTask task : tasks) {
            if (task == null || task.urls().isEmpty()) {
                continue;
            }
            if (isAlreadyValid(task)) {
                skippedBytes += Math.max(task.size(), 0);
            } else {
                pending.add(task);
            }
        }
        long totalBytes = 0;
        for (FileTask task : pending) {
            totalBytes += Math.max(task.size(), 0);
        }
        Log.info("Download", "待下载 " + pending.size() + " 个文件（已存在 " + (tasks.size() - pending.size())
                + "，跳过 " + skippedBytes / 1024 + " KB），总计 " + totalBytes / 1024 / 1024 + " MB");

        if (pending.isEmpty()) {
            if (listener != null) {
                listener.onProgress(new Progress(0, 0, 0, 0, "", 0));
            }
            return List.of();
        }

        int threads = Math.max(1, Math.min(concurrency <= 0 ? 8 : concurrency, 32));
        List<FileTask> pendingTasks = List.copyOf(pending);
        long totalBytesFinal = totalBytes;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "qutc-download");
            thread.setDaemon(true);
            return thread;
        });

        AtomicInteger doneFiles = new AtomicInteger();
        AtomicLong totalDownloaded = new AtomicLong();
        AtomicLong lastSampleTime = new AtomicLong(System.currentTimeMillis());
        AtomicLong lastSampleBytes = new AtomicLong();
        AtomicLong speed = new AtomicLong();
        List<Failure> failures = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();
        for (FileTask task : pendingTasks) {
            futures.add(pool.submit(() -> {
                try {
                    downloadOne(task, downloaded -> {
                        long bytes = totalDownloaded.addAndGet(downloaded);
                        long now = System.currentTimeMillis();
                        long last = lastSampleTime.get();
                        if (now - last >= 500 && lastSampleTime.compareAndSet(last, now)) {
                            long delta = bytes - lastSampleBytes.getAndSet(bytes);
                            speed.set(delta * 1000 / Math.max(now - last, 1));
                            if (listener != null) {
                                listener.onProgress(new Progress(pendingTasks.size(), doneFiles.get(),
                                        totalBytesFinal, bytes, task.target().getFileName().toString(),
                                        speed.get()));
                            }
                        }
                    });
                    doneFiles.incrementAndGet();
                    if (listener != null) {
                        listener.onProgress(new Progress(pendingTasks.size(), doneFiles.get(), totalBytesFinal,
                                totalDownloaded.get(), task.target().getFileName().toString(), speed.get()));
                    }
                } catch (Exception error) {
                    Log.warn("Download", "下载失败: " + task.url(), error);
                    failures.add(new Failure(task, task.url(), error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage()));
                }
            }));
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.HOURS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
        Log.info("Download", "下载结束：成功 " + doneFiles.get() + "，失败 " + failures.size());
        return List.copyOf(failures);
    }

    private interface ChunkSink {
        void accept(long bytes) throws IOException;
    }

    /** 依次尝试候选源；每个源内部按 1s/2s/4s 退避重试。 */
    private void downloadOne(FileTask task, ChunkSink sink) throws IOException {
        Path target = task.target();
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");

        IOException lastError = null;
        String lastUrl = null;
        for (String url : task.urls()) {
            lastUrl = url;
            for (int attempt = 0; attempt <= backoff.length; attempt++) {
                try {
                    attemptDownload(url, task, part, sink);
                    verify(part, task);
                    moveIntoPlace(part, target);
                    if (attempt > 0 || !url.equals(task.url())) {
                        Log.info("Download", "下载成功: " + target.getFileName()
                                + (url.equals(task.url()) ? "（重试后）" : "（已切换到 " + hostOf(url) + "）"));
                    }
                    return;
                } catch (FatalHttpException fatal) {
                    // 4xx：这个源上没有该文件，重试没意义，直接换下一个
                    lastError = fatal;
                    break;
                } catch (IOException error) {
                    lastError = error;
                    if (attempt < backoff.length) {
                        Log.warn("Download", "第 " + (attempt + 1) + " 次尝试失败: " + url
                                + " | " + error.getMessage());
                        sleep(backoff[attempt]);
                    }
                }
            }
            if (task.urls().size() > 1) {
                Log.warn("Download", "源不可用，切换下一个: " + hostOf(url));
            }
        }
        throw lastError == null ? new IOException("下载失败: " + lastUrl) : lastError;
    }

    /** 4xx：源上没有这个文件，重试没有意义，直接换源。 */
    private static final class FatalHttpException extends IOException {
        private final int status;

        FatalHttpException(String url, int status) {
            super("HTTP " + status + " @ " + hostOf(url));
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private void attemptDownload(String url, FileTask task, Path part, ChunkSink sink) throws IOException {
        long existing = Files.isRegularFile(part) ? Files.size(part) : 0L;
        if (task.size() > 0 && existing > task.size()) {
            Files.deleteIfExists(part);
            existing = 0;
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setInstanceFollowRedirects(true);
            if (existing > 0) {
                connection.setRequestProperty("Range", "bytes=" + existing + "-");
            }

            int status = connection.getResponseCode();
            boolean append;
            if (status == 206) {
                append = true;
            } else if (status == 200) {
                append = false;
                existing = 0;
            } else if (status >= 400 && status < 500) {
                throw new FatalHttpException(url, status);
            } else {
                throw new IOException("HTTP " + status + " @ " + hostOf(url));
            }

            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(part,
                         StandardOpenOption.CREATE,
                         append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    sink.accept(read);
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void verify(Path part, FileTask task) throws IOException {
        if (task.sha256() != null && !task.sha256().isBlank()) {
            String actual = HashUtils.sha256(part);
            if (!task.sha256().equalsIgnoreCase(actual)) {
                Files.deleteIfExists(part);
                throw new IOException("SHA-256 校验失败（期望 " + task.sha256() + "，实际 " + actual + "）");
            }
        }
        if (task.sha1() != null && !task.sha1().isBlank()) {
            String actual = HashUtils.sha1(part);
            if (!task.sha1().equalsIgnoreCase(actual)) {
                Files.deleteIfExists(part);
                throw new IOException("SHA-1 校验失败（期望 " + task.sha1() + "，实际 " + actual + "）");
            }
        }
        if (!task.hasHash()) {
            Log.debug("Download", "该文件无官方校验值，跳过校验: " + task.target().getFileName());
        }
    }

    private void moveIntoPlace(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 已存在且校验通过则直接跳过。 */
    public boolean isAlreadyValid(FileTask task) {
        Path target = task.target();
        if (!Files.isRegularFile(target)) {
            return false;
        }
        try {
            long actualSize = Files.size(target);
            if (task.size() > 0 && actualSize != task.size()) {
                return false;
            }
            if (!task.hasHash()) {
                return true;
            }
            if (task.sha256() != null && !task.sha256().isBlank()
                    && !task.sha256().equalsIgnoreCase(HashUtils.sha256(target))) {
                return false;
            }
            return task.sha1() == null || task.sha1().isBlank()
                    || task.sha1().equalsIgnoreCase(HashUtils.sha1(target));
        } catch (IOException e) {
            return false;
        }
    }

    /** 目录大小，供安装结果展示。 */
    public static long sizeOf(Path path) {
        return FsUtils.sizeOf(path);
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
