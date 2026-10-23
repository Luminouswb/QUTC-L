package com.qutc.launcher.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧版内置客户端流程（对应交接包 Electron 版 {@code microsoft.js}）：
 * 公开 client_id + login.live.com 回调地址 + 手动粘贴 code。
 */
class LegacyLoginTest {

    @Test
    void legacyAuthorizeUriUsesPublicClientAndFixedRedirect() {
        URI uri = MicrosoftAuth.legacy().legacyAuthorizeUri();
        Map<String, String> query = query(uri.getRawQuery());
        assertEquals(MicrosoftAuth.LEGACY_CLIENT_ID, query.get("client_id"));
        assertEquals(MicrosoftAuth.LEGACY_REDIRECT_URI, query.get("redirect_uri"));
        assertEquals(MicrosoftAuth.LEGACY_SCOPE, query.get("scope"));
        assertEquals("code", query.get("response_type"));
        assertFalse(query.containsKey("code_challenge"), "旧版客户端不支持 PKCE");
        assertFalse(query.containsKey("state"));
        assertTrue(MicrosoftAuth.legacy().isLegacy());
        assertFalse(new MicrosoftAuth("client-azure").isLegacy());
    }

    @Test
    void parsesCallbackAndPreservesPlusSignInCode() throws Exception {
        String code = "M.C123_abc+def/ghi=";
        String url = "https://login.live.com/oauth20_desktop.srf?code="
                + java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8);
        // URLEncoder 会把 + 编码成 %2B；再单独构造一个未编码的 + 也要原样保留
        assertEquals(code, MicrosoftAuth.parseLegacyRedirect(url));
        assertEquals("ab+cd", MicrosoftAuth.parseLegacyRedirect(
                "https://login.live.com/oauth20_desktop.srf?code=ab+cd"));
    }

    @Test
    void rejectsNonCallbackInputAndSurfacesErrorParam() throws Exception {
        assertNull(MicrosoftAuth.parseLegacyRedirect("https://example.com/?code=abc"));
        assertNull(MicrosoftAuth.parseLegacyRedirect("not a url"));
        AuthException error = assertThrows(AuthException.class, () -> MicrosoftAuth.parseLegacyRedirect(
                "https://login.live.com/oauth20_desktop.srf?error=access_denied&error_description=%E7%94%A8%E6%88%B7%E6%8B%92%E7%BB%9D"));
        assertTrue(error.getMessage().contains("用户拒绝"));
    }

    @Test
    void exchangeLegacyCodePostsWithoutPkceAndWithRedirectUri() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            MicrosoftAuth auth = MicrosoftAuth.legacy(server.endpoints());
            MicrosoftAuth.MsTokens tokens = auth.exchangeLegacyCode("legacy-code-1");
            assertEquals("ms-access-token", tokens.accessToken());
            assertEquals("initial-refresh-token", tokens.refreshToken());

            Map<String, String> form = server.formByPath.get("/token");
            assertEquals("authorization_code", form.get("grant_type"));
            assertEquals("legacy-code-1", form.get("code"));
            assertEquals(MicrosoftAuth.LEGACY_CLIENT_ID, form.get("client_id"));
            assertEquals(MicrosoftAuth.LEGACY_REDIRECT_URI, form.get("redirect_uri"));
            assertEquals(MicrosoftAuth.LEGACY_SCOPE, form.get("scope"));
            assertFalse(form.containsKey("code_verifier"), "旧版流程不带 PKCE verifier");
        }
    }

    @Test
    void legacyChainUsesTaintedPrefixedRpsTicketAndReadsProfile() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            MicrosoftAuth auth = MicrosoftAuth.legacy(server.endpoints());
            MicrosoftAuth.MsTokens tokens = auth.exchangeLegacyCode("legacy-code-2");
            MicrosoftAuth.Chain chain = auth.completeLogin(tokens);
            assertEquals("Steve", chain.profile().name());
            assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", chain.profile().dashedUuid());
            String ticket = server.jsonByPath.get("/xbl").path("Properties").path("RpsTicket").asText();
            assertTrue(ticket.startsWith("t="), "旧版内置客户端优先使用 t= 前缀: " + ticket);
        }
    }

    @Test
    void legacyRefreshKeepsRedirectUriAndLegacyScope() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            MicrosoftAuth auth = MicrosoftAuth.legacy(server.endpoints());
            MicrosoftAuth.MsTokens refreshed = auth.refresh("old-refresh-token");
            assertEquals("rotated-refresh-token", refreshed.refreshToken());
            Map<String, String> form = server.formByPath.get("/token");
            assertEquals("refresh_token", form.get("grant_type"));
            assertEquals("old-refresh-token", form.get("refresh_token"));
            assertEquals(MicrosoftAuth.LEGACY_REDIRECT_URI, form.get("redirect_uri"));
            assertEquals(MicrosoftAuth.LEGACY_SCOPE, form.get("scope"));
            assertEquals(MicrosoftAuth.LEGACY_CLIENT_ID, form.get("client_id"));
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> map = new java.util.HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            map.put(java.net.URLDecoder.decode(pair.substring(0, index), java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(index + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return map;
    }
}
