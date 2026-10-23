package com.qutc.launcher.download;

import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 小文件 / JSON 的 HTTP GET，带多源降级。
 *
 * <p>大文件走 {@link Downloader}（它需要断点续传与流式进度）；版本清单、版本 JSON、
 * 资源索引这类几百 KB 的 JSON 用这里更轻。
 *
 * <p>刻意不发送 {@code Accept-Encoding}，避免拿到需要手动解压的 gzip 响应。
 */
public final class Http {

    private static final String USER_AGENT = "QUTC-Launcher/0.1.0 (Minecraft launcher)";

    private static volatile HttpClient client;

    private Http() {
    }

    private static HttpClient client() {
        HttpClient local = client;
        if (local == null) {
            synchronized (Http.class) {
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(15))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                }
                local = client;
            }
        }
        return local;
    }

    public static byte[] getBytes(String url, int timeoutSeconds) throws IOException {
        return getBytes(List.of(url), timeoutSeconds, timeoutSeconds);
    }

    /**
     * 按候选顺序尝试，第一个成功即返回。
     *
     * @param connectSeconds 建连超时
     * @param readSeconds    等待响应（含读取 body）的超时
     */
    public static byte[] getBytes(List<String> urls, int connectSeconds, int readSeconds) throws IOException {
        IOException last = null;
        for (String url : urls) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    HttpResponse<byte[]> response = send(url, connectSeconds, readSeconds,
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() / 100 != 2) {
                        IOException error = new IOException("HTTP " + response.statusCode() + " @ " + hostOf(url));
                        Log.warn("Http", error.getMessage());
                        last = error;
                        break; // 4xx/5xx 换下一个源
                    }
                    return response.body();
                } catch (IOException error) {
                    Log.warn("Http", "请求失败（第 " + (attempt + 1) + " 次）: " + hostOf(url)
                            + " | " + error.getMessage());
                    last = error;
                    if (attempt == 0) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("请求被中断", interrupted);
                        }
                    }
                }
            }
        }
        throw last == null ? new IOException("无可用地址") : last;
    }

    public static String getString(List<String> urls, int connectSeconds, int readSeconds) throws IOException {
        return new String(getBytes(urls, connectSeconds, readSeconds), StandardCharsets.UTF_8);
    }

    public static String getString(String url, int timeoutSeconds) throws IOException {
        return new String(getBytes(url, timeoutSeconds), StandardCharsets.UTF_8);
    }

    private static <T> HttpResponse<T> send(String url, int connectSeconds, int readSeconds,
                                            HttpResponse.BodyHandler<T> handler) throws IOException {
        long start = System.nanoTime();
        HttpClient local = client();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, readSeconds)))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<T> response = local.send(request, handler);
            long cost = (System.nanoTime() - start) / 1_000_000L;
            Log.debug("Http", "GET " + hostOf(url) + " -> " + response.statusCode() + " (" + cost + "ms)");
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断: " + url, e);
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (RuntimeException e) {
            return url;
        }
    }
}
