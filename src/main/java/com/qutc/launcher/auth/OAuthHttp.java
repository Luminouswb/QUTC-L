package com.qutc.launcher.auth;

import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 认证相关的 HTTP 收发（表单 POST / JSON POST / Bearer GET）。
 *
 * <p>与 {@code download.Http} 分开是有意的：认证请求必须带
 * {@code Content-Type}、要读错误响应体里的 {@code error} 字段，
 * 且<b>不能自动换源、不能重试</b>——令牌端点重试可能触发频率限制，
 * 也可能让 refresh_token 轮换出现歧义。
 */
public final class OAuthHttp {

    private static final String USER_AGENT = "QUTC-Launcher/0.1.0 (Minecraft launcher)";
    private static final String JSON = "application/json; charset=utf-8";
    private static final String FORM = "application/x-www-form-urlencoded; charset=utf-8";

    private static volatile HttpClient client;

    private OAuthHttp() {
    }

    public record Response(int status, String body) {
        public boolean ok() {
            return status / 100 == 2;
        }
    }

    /** 带响应头的 GET 结果（authlib-injector 的 ALI 发现需要读头）。 */
    public record FullResponse(int status, String body, java.net.http.HttpHeaders headers) {
        public boolean ok() {
            return status / 100 == 2;
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    private static HttpClient client() {
        HttpClient local = client;
        if (local == null) {
            synchronized (OAuthHttp.class) {
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

    public static Response postForm(String url, Map<String, String> form) throws AuthException {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", FORM)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(request, url);
    }

    public static Response postJson(String url, String json, String bearerToken) throws AuthException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", JSON)
                .header("Accept", JSON)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return send(builder.build(), url);
    }

    public static Response get(String url, String bearerToken) throws AuthException {
        FullResponse full = getFull(url, bearerToken);
        return new Response(full.status(), full.body());
    }

    /** GET 并保留响应头（ALI 发现用）。 */
    public static FullResponse getFull(String url, String bearerToken) throws AuthException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", JSON)
                .GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        try {
            HttpResponse<String> response = client().send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Log.debug("Auth", "GET " + hostOf(url) + " -> " + response.statusCode());
            return new FullResponse(response.statusCode(), response.body(), response.headers());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException(AuthException.Reason.NETWORK, "认证请求被中断", e);
        } catch (IOException e) {
            throw new AuthException(AuthException.Reason.NETWORK,
                    "无法连接认证服务器 " + hostOf(url) + ": " + e.getMessage(), e);
        }
    }

    private static Response send(HttpRequest request, String url) throws AuthException {
        try {
            HttpResponse<String> response = client().send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Log.debug("Auth", request.method() + " " + hostOf(url) + " -> " + response.statusCode());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException(AuthException.Reason.NETWORK, "认证请求被中断", e);
        } catch (IOException e) {
            throw new AuthException(AuthException.Reason.NETWORK,
                    "无法连接认证服务器 " + hostOf(url) + ": " + e.getMessage(), e);
        }
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (RuntimeException e) {
            return url;
        }
    }
}
