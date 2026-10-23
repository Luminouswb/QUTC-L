package com.qutc.launcher.auth;

import com.qutc.launcher.utils.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * loopback 回调服务器：微软登录重定向到 {@code http://127.0.0.1:<随机端口>/callback}。
 *
 * <p>用随机端口而不是固定端口，避免端口被占或被本机其他程序伪造回调；
 * 只监听 127.0.0.1，不对外暴露。收到的 {@code state} 必须与发起时一致，
 * 否则视为 CSRF 直接拒绝（{@link AuthException.Reason#STATE_MISMATCH}）。
 */
public final class CallbackServer implements AutoCloseable {

    private static final String CALLBACK_PATH = "/callback";

    /** 原始回调内容；state 校验由 {@link #await(Duration)} 完成。 */
    public record Result(String code, String state, String error, String errorDescription) {
        public boolean failed() {
            return error != null && !error.isBlank();
        }
    }

    private final HttpServer server;
    private final CompletableFuture<Result> future = new CompletableFuture<>();
    private final String expectedState;
    private volatile boolean closed;

    private CallbackServer(HttpServer server, String expectedState) {
        this.server = server;
        this.expectedState = expectedState;
    }

    /** 绑定 127.0.0.1 的随机空闲端口（端口 0 由系统分配）。 */
    public static CallbackServer start(String expectedState) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CallbackServer callback = new CallbackServer(server, expectedState);
        server.createContext("/", callback::handle);
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qutc-oauth-callback");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        Log.info("Auth", "登录回调监听: http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK_PATH);
        return callback;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String redirectUri() {
        return "http://127.0.0.1:" + port() + CALLBACK_PATH;
    }

    /**
     * 等浏览器回调。
     *
     * @throws AuthException TIMEOUT（用户没完成登录）/ STATE_MISMATCH / REAUTH_REQUIRED（用户点了拒绝）
     */
    public Result await(Duration timeout) throws AuthException {
        Result result;
        try {
            result = future.get(Math.max(1, timeout.toSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AuthException(AuthException.Reason.TIMEOUT,
                    "等待浏览器登录超时（" + timeout.toSeconds() + " 秒），请重试并完成登录");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException(AuthException.Reason.NETWORK, "等待登录回调被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AuthException auth) {
                throw auth;
            }
            throw new AuthException(AuthException.Reason.NETWORK,
                    "登录回调处理失败: " + (cause == null ? e.getMessage() : cause.getMessage()), e);
        }
        if (result.failed()) {
            throw new AuthException(AuthException.Reason.REAUTH_REQUIRED,
                    "登录未完成: " + result.error()
                            + (result.errorDescription() == null ? "" : "（" + result.errorDescription() + "）"));
        }
        if (expectedState != null && !expectedState.equals(result.state())) {
            Log.warn("Auth", "回调 state 不匹配，可能是伪造请求，已拒绝");
            throw new AuthException(AuthException.Reason.STATE_MISMATCH,
                    "登录回调 state 校验失败，请重新发起登录");
        }
        if (result.code() == null || result.code().isBlank()) {
            throw new AuthException(AuthException.Reason.REAUTH_REQUIRED, "登录回调没有携带授权码");
        }
        return result;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!CALLBACK_PATH.equals(path)) {
                respond(exchange, 404, "Not Found", "text/plain; charset=utf-8");
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            Result result = new Result(query.get("code"), query.get("state"),
                    query.get("error"), query.get("error_description"));
            future.complete(result);
            String html = result.failed()
                    ? page("登录未完成", "错误: " + escape(result.error())
                    + (result.errorDescription() == null ? "" : "<br>" + escape(result.errorDescription())))
                    : page("登录完成", "QUTC 已收到授权码，可以关闭此页面回到启动器。");
            respond(exchange, 200, html, "text/html; charset=utf-8");
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String page(String title, String message) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<title>" + escape(title) + " - QUTC</title>"
                + "<style>body{font-family:Segoe UI,Microsoft YaHei,sans-serif;background:#111;color:#eee;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}"
                + "div{max-width:520px;padding:32px;border-radius:16px;background:#1b1b1f;"
                + "box-shadow:0 12px 40px rgba(0,0,0,.5)}h1{font-size:20px;margin:0 0 12px}"
                + "p{line-height:1.7;color:#bbb;margin:0}</style></head><body><div><h1>"
                + escape(title) + "</h1><p>" + message + "</p></div></body></html>";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
            map.put(key, value);
        }
        return map;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!future.isDone()) {
            future.cancel(true);
        }
        server.stop(0);
    }
}
