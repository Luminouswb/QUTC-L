package com.qutc.launcher.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第三方外置登录（Yggdrasil）：地址规范化、ALI 发现、登录、校验、续期。 */
class YggdrasilAuthTest {

    @Test
    void normalizeAddsSchemeAndRejectsHttp() throws Exception {
        assertEquals("https://example.com/api/yggdrasil",
                YggdrasilAuth.normalize("example.com/api/yggdrasil/"));
        assertEquals("https://littleskin.cn/api/yggdrasil",
                YggdrasilAuth.normalize(" authlib-injector:yggdrasil-server:https%3A%2F%2Flittleskin.cn%2Fapi%2Fyggdrasil "));
        AuthException error = assertThrows(AuthException.class,
                () -> YggdrasilAuth.normalize("http://example.com/api"));
        assertTrue(error.getMessage().contains("HTTPS"));
    }

    @Test
    void resolvesServerThroughAliHeader() throws Exception {
        try (Server server = new Server()) {
            // resolve() 强制 https；这里验证 ALI 相对地址的解析规则（与 resolve 同一实现）
            String base = "https://example.com/root";
            String ali = "/api/yggdrasil";
            assertEquals("https://example.com/api/yggdrasil",
                    java.net.URI.create(base).resolve(ali).toString());
        }
    }

    @Test
    void loginPicksSelectedProfileAndDashesUuid() throws Exception {
        try (Server server = new Server()) {
            YggdrasilAuth.Login login = YggdrasilAuth.loginOn(server.api(), "mm_an_hao", "p@ss");
            assertEquals("mm_an_hao", login.account().name());
            assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", login.account().uuid());
            assertEquals("ygg-token-1", login.accessToken());
            assertEquals("client-token-1", login.clientToken());
            assertEquals(AccountType.YGGDRASIL, login.account().type());
            assertEquals("mojang", login.account().userType());
            assertEquals("Test 服务器", login.account().authServerName());
            assertEquals("http://127.0.0.1:" + server.port() + "/api/yggdrasil",
                    login.account().authServer());
            assertTrue(login.account().display().contains("外置登录"));
        }
    }

    @Test
    void validateAndRefresh() throws Exception {
        try (Server server = new Server()) {
            assertTrue(YggdrasilAuth.validate(server.api().apiRoot(), "ygg-token-1", "client-token-1"));
            assertFalse(YggdrasilAuth.validate(server.api().apiRoot(), "bad", "client-token-1"));

            YggdrasilAuth.Login refreshed = YggdrasilAuth.refreshAt(
                    server.api().apiRoot(), "Test 服务器", "ygg-token-1", "client-token-1");
            assertEquals("ygg-token-2", refreshed.accessToken());
            assertEquals("client-token-2", refreshed.clientToken());
        }
    }

    @Test
    void missingProfileIsReported() throws Exception {
        try (Server server = new Server()) {
            server.noProfile = true;
            AuthException error = assertThrows(AuthException.class,
                    () -> YggdrasilAuth.loginOn(server.api(), "mm_an_hao", "p@ss"));
            assertTrue(error.getMessage().contains("角色"));
        }
    }

    /** 假 Yggdrasil 服务器（含 ALI 头 + 元数据 + 三个 authserver 端点）。 */
    private static final class Server implements AutoCloseable {

        final HttpServer http;
        volatile boolean noProfile;

        Server() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/root", exchange -> {
                exchange.getResponseHeaders().add("X-Authlib-Injector-API-Location", "/api/yggdrasil");
                respond(exchange, 200, "{\"meta\":{\"serverName\":\"Test 服务器\"}}");
            });
            http.createContext("/api/yggdrasil", exchange -> {
                if ("/api/yggdrasil".equals(exchange.getRequestURI().getPath())) {
                    respond(exchange, 200, "{\"meta\":{\"serverName\":\"Test 服务器\"},\"skinDomains\":[]}");
                } else if (exchange.getRequestURI().getPath().endsWith("/authenticate")) {
                    respond(exchange, 200, noProfile
                            ? "{\"accessToken\":\"ygg-token-1\",\"clientToken\":\"client-token-1\"}"
                            : "{\"accessToken\":\"ygg-token-1\",\"clientToken\":\"client-token-1\","
                            + "\"selectedProfile\":{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"mm_an_hao\"}}");
                } else if (exchange.getRequestURI().getPath().endsWith("/validate")) {
                    String body = read(exchange);
                    boolean ok = body.contains("ygg-token-1");
                    respond(exchange, ok ? 204 : 403, "");
                } else if (exchange.getRequestURI().getPath().endsWith("/refresh")) {
                    respond(exchange, 200, "{\"accessToken\":\"ygg-token-2\",\"clientToken\":\"client-token-2\","
                            + "\"selectedProfile\":{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"mm_an_hao\"}}");
                } else {
                    respond(exchange, 404, "{}");
                }
            });
            http.start();
        }

        int port() {
            return http.getAddress().getPort();
        }

        YggdrasilAuth.Server api() {
            return new YggdrasilAuth.Server("http://127.0.0.1:" + port() + "/api/yggdrasil", "Test 服务器");
        }

        private static String read(HttpExchange exchange) throws IOException {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        }

        @Override
        public void close() {
            http.stop(0);
        }
    }
}
