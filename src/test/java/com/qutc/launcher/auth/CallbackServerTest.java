package com.qutc.launcher.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** loopback 回调服务器：正常回调、state 防伪、用户拒绝、超时。 */
class CallbackServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void receivesCodeFromBrowserRedirect() throws Exception {
        try (CallbackServer server = CallbackServer.start("state-ok")) {
            assertTrue(server.redirectUri().startsWith("http://127.0.0.1:"), "只监听回环地址");
            get(server.redirectUri() + "?code=the-code&state=state-ok");
            CallbackServer.Result result = server.await(Duration.ofSeconds(5));
            assertEquals("the-code", result.code());
            assertEquals("state-ok", result.state());
            assertTrue(!result.failed());
        }
    }

    @Test
    void rejectsForgedState() throws Exception {
        try (CallbackServer server = CallbackServer.start("expected-state")) {
            get(server.redirectUri() + "?code=stolen-code&state=attacker-state");
            AuthException error = assertThrows(AuthException.class,
                    () -> server.await(Duration.ofSeconds(5)));
            assertEquals(AuthException.Reason.STATE_MISMATCH, error.reason());
        }
    }

    @Test
    void userDenialIsReportedAsRelogin() throws Exception {
        try (CallbackServer server = CallbackServer.start("state-ok")) {
            get(server.redirectUri() + "?error=access_denied&error_description=user%20cancelled&state=state-ok");
            AuthException error = assertThrows(AuthException.class,
                    () -> server.await(Duration.ofSeconds(5)));
            assertEquals(AuthException.Reason.REAUTH_REQUIRED, error.reason());
            assertTrue(error.getMessage().contains("access_denied"));
        }
    }

    @Test
    void timesOutWithoutCallback() throws Exception {
        try (CallbackServer server = CallbackServer.start("state-ok")) {
            long start = System.nanoTime();
            AuthException error = assertThrows(AuthException.class,
                    () -> server.await(Duration.ofSeconds(1)));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(AuthException.Reason.TIMEOUT, error.reason());
            assertTrue(elapsedMs >= 900, "应真的等满 1 秒，实际 " + elapsedMs + "ms");
        }
    }

    private static void get(String url) throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }
}
