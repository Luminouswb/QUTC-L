package com.qutc.launcher.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qutc.launcher.utils.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 假微软认证服务器：把 XBL / XSTS / MC 登录 / Profile 全部本地化，
 * 让整条链路可以在没有真实账号的情况下被断言。
 *
 * <p>它只验证「启动器发了什么」，不验证微软的真实行为——真实行为由阶段 13 真机联调覆盖。
 */
final class FakeAuthServer implements AutoCloseable {

    private final HttpServer server;

    /** 最近一次请求的可观测内容。 */
    volatile String lastPath;
    volatile String lastMethod;
    volatile String lastAuthorization;
    volatile Map<String, String> lastForm = Map.of();
    volatile JsonNode lastJson;

    /** 按路径记录的请求体，便于分别断言 XBL / XSTS / MC 三次请求。 */
    final Map<String, JsonNode> jsonByPath = new ConcurrentHashMap<>();
    final Map<String, Map<String, String>> formByPath = new ConcurrentHashMap<>();

    /** 令牌端点的行为开关。 */
    volatile boolean rotateRefreshToken = true;
    volatile boolean tokenEndpointFails = false;
    volatile String tokenAccess = "ms-access-token";
    volatile String mcAccessToken = "mc-access-token";
    volatile String profileState = "ACTIVE";
    volatile boolean profileMissing = false;
    volatile boolean mcUnauthorized = false;
    /** 非 null 时 XSTS 返回 401 + 该 XErr。 */
    volatile Long xstsXErr;

    FakeAuthServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", this::handleToken);
        server.createContext("/xbl", exchange -> handleJson(exchange, xblResponse()));
        server.createContext("/xsts", this::handleXsts);
        server.createContext("/mc", this::handleMc);
        server.createContext("/profile", this::handleProfile);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    MicrosoftAuth.Endpoints endpoints() {
        return new MicrosoftAuth.Endpoints(
                base() + "/authorize",
                base() + "/token",
                base() + "/xbl",
                base() + "/xsts",
                base() + "/mc",
                base() + "/profile");
    }

    MicrosoftAuth auth(String clientId) {
        return new MicrosoftAuth(clientId, endpoints());
    }

    // ---- 请求记录 ----

    private void record(HttpExchange exchange) throws IOException {
        lastPath = exchange.getRequestURI().getPath();
        lastMethod = exchange.getRequestMethod();
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] body = exchange.getRequestBody().readAllBytes();
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            lastForm = Map.of();
            lastJson = null;
            return;
        }
        if (text.trim().startsWith("{")) {
            lastJson = JsonUtils.parse(text);
            lastForm = Map.of();
            jsonByPath.put(lastPath, lastJson);
        } else {
            lastJson = null;
            lastForm = parseForm(text);
            formByPath.put(lastPath, lastForm);
        }
    }

    private static Map<String, String> parseForm(String text) {
        Map<String, String> map = new HashMap<>();
        for (String pair : text.split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            map.put(URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
        }
        return map;
    }

    // ---- 端点实现 ----

    private void handleToken(HttpExchange exchange) throws IOException {
        record(exchange);
        if (tokenEndpointFails) {
            ObjectNode error = JsonUtils.newObject();
            error.put("error", "invalid_grant");
            error.put("error_description", "The refresh token is invalid.");
            respond(exchange, 400, error.toString());
            return;
        }
        ObjectNode token = JsonUtils.newObject();
        token.put("token_type", "bearer");
        token.put("expires_in", 3600);
        token.put("scope", MicrosoftAuth.SCOPE);
        token.put("access_token", tokenAccess);
        token.put("user_id", "fake-user");
        boolean refreshGrant = "refresh_token".equals(lastForm.get("grant_type"));
        if (!refreshGrant || rotateRefreshToken) {
            token.put("refresh_token", refreshGrant ? "rotated-refresh-token" : "initial-refresh-token");
        }
        respond(exchange, 200, token.toString());
    }

    private void handleXsts(HttpExchange exchange) throws IOException {
        record(exchange);
        if (xstsXErr != null) {
            ObjectNode error = JsonUtils.newObject();
            error.put("Identity", "0");
            error.put("XErr", xstsXErr);
            error.put("Message", "fake xsts failure");
            error.put("Redirect", "https://start.ui.xboxlive.com/");
            respond(exchange, 401, error.toString());
            return;
        }
        respond(exchange, 200, xstsResponse().toString());
    }

    private void handleProfile(HttpExchange exchange) throws IOException {
        record(exchange);
        if (profileMissing) {
            ObjectNode error = JsonUtils.newObject();
            error.put("path", "/minecraft/profile");
            error.put("errorType", "NOT_FOUND");
            respond(exchange, 404, error.toString());
            return;
        }
        ObjectNode profile = JsonUtils.newObject();
        profile.put("id", "069a79f444e94726a5befca90e38aaf5");
        profile.put("name", "Steve");
        ArrayNode skins = profile.putArray("skins");
        ObjectNode skin = skins.addObject();
        skin.put("id", "skin-1");
        skin.put("state", profileState);
        skin.put("url", "https://textures.minecraft.net/texture/skin-abc");
        ArrayNode capes = profile.putArray("capes");
        ObjectNode cape = capes.addObject();
        cape.put("id", "cape-1");
        cape.put("state", "ACTIVE");
        cape.put("url", "https://textures.minecraft.net/texture/cape-abc");
        respond(exchange, 200, profile.toString());
    }

    private void handleMc(HttpExchange exchange) throws IOException {
        record(exchange);
        respond(exchange, mcUnauthorized ? 401 : 200, mcResponse().toString());
    }

    private void handleJson(HttpExchange exchange, JsonNode response) throws IOException {
        record(exchange);
        respond(exchange, 200, response.toString());
    }

    private static JsonNode xblResponse() {
        return claims("xbl-token");
    }

    private static JsonNode xstsResponse() {
        return claims("xsts-token");
    }

    private static JsonNode claims(String token) {
        ObjectNode node = JsonUtils.newObject();
        node.put("Token", token);
        ObjectNode claims = node.putObject("DisplayClaims");
        ArrayNode xui = claims.putArray("xui");
        ObjectNode first = xui.addObject();
        first.put("uhs", "uhs-123");
        first.put("xid", "12345");
        first.put("gtg", "Steve");
        return node;
    }

    private JsonNode mcResponse() throws IOException {
        if (mcUnauthorized) {
            ObjectNode error = JsonUtils.newObject();
            error.put("path", "/authentication/login_with_xbox");
            error.put("errorType", "UNAUTHORIZED");
            error.put("error", "UnauthorizedOperationException");
            return error;
        }
        ObjectNode node = JsonUtils.newObject();
        node.put("username", "Steve");
        node.put("roles", "[]");
        node.put("access_token", mcAccessToken);
        node.put("token_type", "Bearer");
        node.put("expires_in", 86400);
        return node;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
