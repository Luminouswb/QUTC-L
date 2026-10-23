package com.qutc.launcher.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 微软六步链路：请求形状、响应解析、错误归类的本地断言。 */
class MicrosoftAuthTest {

    private static final String CLIENT_ID = "client-abc";
    private static final String REDIRECT = "http://127.0.0.1:12345/callback";

    private FakeAuthServer fake;
    private MicrosoftAuth auth;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeAuthServer();
        auth = fake.auth(CLIENT_ID);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    @Test
    void authorizationUrlCarriesPkceStateAndScope() {
        String verifier = Pkce.newVerifier();
        String challenge = Pkce.challengeOf(verifier);
        String state = Pkce.newState();
        URI uri = auth.authorizeUri(state, challenge, REDIRECT);
        Map<String, String> query = query(uri.getRawQuery());

        assertEquals(CLIENT_ID, query.get("client_id"));
        assertEquals("code", query.get("response_type"));
        assertEquals(REDIRECT, query.get("redirect_uri"));
        assertEquals(MicrosoftAuth.SCOPE, query.get("scope"));
        assertEquals(state, query.get("state"));
        assertEquals(challenge, query.get("code_challenge"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals("select_account", query.get("prompt"));
        assertTrue(uri.toString().startsWith(fake.base() + "/authorize"));
    }

    @Test
    void exchangeCodeSendsVerifierAndParsesTokens() throws Exception {
        MicrosoftAuth.MsTokens tokens = auth.exchangeCode("code-1", "verifier-1", REDIRECT);

        Map<String, String> form = fake.formByPath.get("/token");
        assertNotNull(form);
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals("code-1", form.get("code"));
        assertEquals("verifier-1", form.get("code_verifier"), "PKCE verifier 必须随授权码一起提交");
        assertEquals(REDIRECT, form.get("redirect_uri"));
        assertEquals(CLIENT_ID, form.get("client_id"));

        assertEquals("ms-access-token", tokens.accessToken());
        assertEquals("initial-refresh-token", tokens.refreshToken());
        assertEquals(3600, tokens.expiresInSeconds());
    }

    @Test
    void xblAndXstsPayloadsMatchMicrosoftContract() throws Exception {
        MicrosoftAuth.XboxTokens xbox = auth.authenticateXbox("ms-token-1");

        JsonNode xbl = fake.jsonByPath.get("/xbl");
        assertNotNull(xbl, "必须调用 XBL 端点");
        assertEquals("RPS", xbl.path("Properties").path("AuthMethod").asText());
        assertEquals("user.auth.xboxlive.com", xbl.path("Properties").path("SiteName").asText());
        assertEquals("d=ms-token-1", xbl.path("Properties").path("RpsTicket").asText(),
                "RpsTicket 必须以 d= 前缀携带微软 access_token");
        assertEquals("http://auth.xboxlive.com", xbl.path("RelyingParty").asText());
        assertEquals("JWT", xbl.path("TokenType").asText());

        JsonNode xsts = fake.jsonByPath.get("/xsts");
        assertNotNull(xsts, "必须调用 XSTS 端点");
        assertEquals("RETAIL", xsts.path("Properties").path("SandboxId").asText());
        assertEquals("xbl-token", xsts.path("Properties").path("UserTokens").get(0).asText());
        assertEquals("rp://api.minecraftservices.com/", xsts.path("RelyingParty").asText());
        assertEquals("JWT", xsts.path("TokenType").asText());

        assertEquals("xsts-token", xbox.token());
        assertEquals("uhs-123", xbox.userHash());
        assertEquals("12345", xbox.xuid());
    }

    @Test
    void loginWithXboxSendsIdentityToken() throws Exception {
        MicrosoftAuth.MsTokens tokens = auth.loginWithXbox("uhs-123", "xsts-token");

        JsonNode mc = fake.jsonByPath.get("/mc");
        assertNotNull(mc);
        assertEquals("XBL3.0 x=uhs-123;xsts-token", mc.path("identityToken").asText());
        assertEquals("mc-access-token", tokens.accessToken());
        assertEquals(86400, tokens.expiresInSeconds());
    }

    @Test
    void profileParsesUuidSkinAndCape() throws Exception {
        MicrosoftAuth.Profile profile = auth.profile("mc-access-token");
        assertEquals("069a79f444e94726a5befca90e38aaf5", profile.id());
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", profile.dashedUuid());
        assertEquals("Steve", profile.name());
        assertEquals("https://textures.minecraft.net/texture/skin-abc", profile.skinUrl());
        assertEquals("https://textures.minecraft.net/texture/cape-abc", profile.capeUrl());
        assertEquals("Bearer mc-access-token", fake.lastAuthorization);
    }

    @Test
    void inactiveSkinIsNotUsed() throws Exception {
        fake.profileState = "INACTIVE";
        assertNull(auth.profile("mc").skinUrl(), "非 ACTIVE 皮肤不应作为当前皮肤");
    }

    @Test
    void missingProfileMeansNoMinecraftOwnership() {
        fake.profileMissing = true;
        AuthException error = assertThrows(AuthException.class, () -> auth.profile("mc"));
        assertEquals(AuthException.Reason.NO_MINECRAFT_PROFILE, error.reason());
    }

    @Test
    void completeLoginWalksTheWholeChain() throws Exception {
        MicrosoftAuth.Chain chain = auth.completeLogin(
                new MicrosoftAuth.MsTokens("ms-access", "refresh", 3600));
        assertEquals("mc-access-token", chain.mcAccessToken());
        assertEquals("Steve", chain.profile().name());
        assertEquals("12345", chain.xuid());
        assertTrue(chain.expiresAt().isAfter(Instant.now().plusSeconds(3600 * 23)));
    }

    @Test
    void refreshUsesRefreshGrantAndReturnsRotatedToken() throws Exception {
        MicrosoftAuth.MsTokens refreshed = auth.refresh("old-refresh");
        Map<String, String> form = fake.formByPath.get("/token");
        assertEquals("refresh_token", form.get("grant_type"));
        assertEquals("old-refresh", form.get("refresh_token"));
        assertEquals(MicrosoftAuth.SCOPE, form.get("scope"));
        assertEquals("rotated-refresh-token", refreshed.refreshToken());
    }

    @Test
    void refreshWithoutRotationReturnsNullRefreshToken() throws Exception {
        fake.rotateRefreshToken = false;
        assertNull(auth.refresh("old-refresh").refreshToken(),
                "微软未返回新 refresh_token 时，调用方必须沿用旧值");
    }

    @Test
    void invalidGrantRequiresRelogin() {
        fake.tokenEndpointFails = true;
        AuthException error = assertThrows(AuthException.class, () -> auth.refresh("stale"));
        assertEquals(AuthException.Reason.REAUTH_REQUIRED, error.reason());
        assertTrue(error.requiresRelogin());
    }

    @Test
    void xstsErrorsAreMappedToActionableReasons() {
        fake.xstsXErr = 2148916233L;
        assertEquals(AuthException.Reason.NO_XBOX_ACCOUNT,
                assertThrows(AuthException.class, () -> auth.authenticateXbox("ms")).reason());

        fake.xstsXErr = 2148916235L;
        assertEquals(AuthException.Reason.XBOX_RESTRICTED,
                assertThrows(AuthException.class, () -> auth.authenticateXbox("ms")).reason());

        fake.xstsXErr = 2148916236L;
        assertEquals(AuthException.Reason.XBOX_CHILD_ACCOUNT,
                assertThrows(AuthException.class, () -> auth.authenticateXbox("ms")).reason());

        fake.xstsXErr = 999999L;
        assertEquals(AuthException.Reason.HTTP_ERROR,
                assertThrows(AuthException.class, () -> auth.authenticateXbox("ms")).reason());
    }

    @Test
    void unauthorizedMinecraftLoginRequiresRelogin() {
        fake.mcUnauthorized = true;
        AuthException error = assertThrows(AuthException.class, () -> auth.loginWithXbox("uhs", "xsts"));
        assertEquals(AuthException.Reason.REAUTH_REQUIRED, error.reason());
    }

    @Test
    void clientIdIsMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new MicrosoftAuth("  "));
        assertThrows(IllegalArgumentException.class, () -> new MicrosoftAuth(null));
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            map.put(URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
        }
        return map;
    }
}
