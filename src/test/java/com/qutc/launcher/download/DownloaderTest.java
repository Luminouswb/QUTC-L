package com.qutc.launcher.download;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载器行为：5xx 重试、4xx 换源、Range 续传、sha1 不符时不落地。
 * 用本地 HttpServer，不碰外网；退避注入 0 延迟。
 */
class DownloaderTest {

    private static final long[] NO_BACKOFF = {0, 0, 0};

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String serve(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String sha1(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    }

    @Test
    void retriesOnServerErrorThenSucceeds(@TempDir Path dir) throws Exception {
        byte[] data = "hello mirror".getBytes(StandardCharsets.UTF_8);
        AtomicInteger hits = new AtomicInteger();
        String base = serve(exchange -> {
            int n = hits.incrementAndGet();
            if (n == 1) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        });

        Path target = dir.resolve("a.bin");
        Downloader downloader = new Downloader(NO_BACKOFF);
        List<Downloader.Failure> failures = downloader.download(List.of(
                Downloader.FileTask.of(base + "/file", target, sha1(data), data.length)), 1, null);

        assertTrue(failures.isEmpty(), "failures=" + failures);
        assertEquals(2, hits.get(), "5xx 后应重试一次");
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    @Test
    void switchesSourceOnNotFoundWithoutRetrying(@TempDir Path dir) throws Exception {
        byte[] data = "from secondary".getBytes(StandardCharsets.UTF_8);
        AtomicInteger deadHits = new AtomicInteger();
        AtomicInteger goodHits = new AtomicInteger();
        String base = serve(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/dead")) {
                deadHits.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            goodHits.incrementAndGet();
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        });

        Path target = dir.resolve("b.bin");
        Downloader downloader = new Downloader(NO_BACKOFF);
        List<Downloader.Failure> failures = downloader.download(List.of(
                Downloader.FileTask.mirrors(List.of(base + "/dead/b.bin", base + "/good/b.bin"),
                        target, sha1(data), data.length)), 1, null);

        assertTrue(failures.isEmpty(), "failures=" + failures);
        assertEquals(1, deadHits.get(), "404 不应在同一源上重试");
        assertEquals(1, goodHits.get());
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    @Test
    void resumesFromPartFileWithRangeRequest(@TempDir Path dir) throws Exception {
        byte[] data = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        int alreadyHave = 6;
        AtomicReference<String> rangeHeader = new AtomicReference<>();
        String base = serve(exchange -> {
            rangeHeader.set(exchange.getRequestHeaders().getFirst("Range"));
            String range = rangeHeader.get();
            int start = 0;
            if (range != null && range.startsWith("bytes=")) {
                start = Integer.parseInt(range.substring("bytes=".length()).split("-")[0]);
            }
            byte[] slice = java.util.Arrays.copyOfRange(data, start, data.length);
            if (start > 0) {
                exchange.getResponseHeaders().add("Content-Range",
                        "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
                exchange.sendResponseHeaders(206, slice.length);
            } else {
                exchange.sendResponseHeaders(200, slice.length);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(slice);
            }
        });

        Path target = dir.resolve("c.bin");
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Files.write(part, java.util.Arrays.copyOfRange(data, 0, alreadyHave));

        Downloader downloader = new Downloader(NO_BACKOFF);
        List<Downloader.Failure> failures = downloader.download(List.of(
                Downloader.FileTask.of(base + "/file", target, sha1(data), data.length)), 1, null);

        assertTrue(failures.isEmpty(), "failures=" + failures);
        assertTrue(rangeHeader.get() != null && rangeHeader.get().startsWith("bytes="),
                "应带 Range 头，实际: " + rangeHeader.get());
        assertArrayEquals(data, Files.readAllBytes(target));
        assertFalse(Files.exists(part), ".part 应在完成后被移走");
    }

    @Test
    void hashMismatchLeavesNoTargetFile(@TempDir Path dir) throws Exception {
        byte[] data = "corrupted payload".getBytes(StandardCharsets.UTF_8);
        String base = serve(exchange -> {
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        });

        Path target = dir.resolve("d.bin");
        Downloader downloader = new Downloader(NO_BACKOFF);
        List<Downloader.Failure> failures = downloader.download(List.of(
                Downloader.FileTask.of(base + "/file", target, "0000000000000000000000000000000000000000",
                        data.length)), 1, null);

        assertEquals(1, failures.size(), "sha1 不符必须报失败");
        assertFalse(Files.exists(target), "校验失败不能落地");
    }
}
