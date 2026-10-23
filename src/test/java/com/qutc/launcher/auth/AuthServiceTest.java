package com.qutc.launcher.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 账号服务：续期轮换的原子性、离线账号、配置缺失的降级。 */
class AuthServiceTest {

    private static final String UUID_TEXT = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    @TempDir
    Path dir;

    private FakeAuthServer fake;
    private AccountStore store;
    private AuthService service;

    @BeforeEach
    void setUp() throws IOException {
        fake = new FakeAuthServer();
        store = new AccountStore(dir.resolve("accounts").resolve("accounts.dat"),
                new SecretBox(dir.resolve("accounts").resolve("secret.key"), true));
        service = new AuthService(store, fake.auth("client-abc"), uri -> true);
    }

    @AfterEach
    void tearDown() {
        fake.close();
    }

    private Account saveMicrosoftAccount(Instant expiresAt, String refreshToken) throws IOException {
        Account account = Account.microsoft("Steve", UUID_TEXT, "12345", expiresAt, null, null);
        store.save(account, new Credentials("old-mc-token", refreshToken, "old-ms-token",
                expiresAt == null ? null : expiresAt.toString()));
        return account;
    }

    @Test
    void refreshRotatesRefreshTokenAndPersistsAtomically() throws Exception {
        Account account = saveMicrosoftAccount(Instant.now().minusSeconds(60), "OLD-REFRESH-TOKEN");

        Account refreshed = service.refresh(account);

        Credentials saved = store.credentials(account).orElseThrow();
        assertEquals("rotated-refresh-token", saved.refreshToken(), "新的 refresh_token 必须落盘");
        assertEquals("mc-access-token", saved.accessToken());
        assertEquals("ms-access-token", saved.msAccessToken());
        assertFalse(Files.readString(store.file(), StandardCharsets.UTF_8).contains("OLD-REFRESH-TOKEN"),
                "旧 refresh_token 必须被覆盖");

        assertEquals("Steve", refreshed.name());
        assertEquals(UUID_TEXT, refreshed.uuid());
        assertTrue(refreshed.tokenExpiresAt().isAfter(Instant.now().plusSeconds(3600)));
    }

    @Test
    void ensureFreshSkipsNetworkWhenTokenStillValid() throws Exception {
        Account account = saveMicrosoftAccount(Instant.now().plusSeconds(3600), "refresh");
        fake.lastPath = null;

        Account result = service.ensureFresh(account);

        assertSame(account, result);
        assertEquals(null, fake.lastPath, "有效期内不应发起任何网络请求");
    }

    @Test
    void ensureFreshRefreshesExpiredAccount() throws Exception {
        Account account = saveMicrosoftAccount(Instant.now().minusSeconds(1), "OLD");
        Account result = service.ensureFresh(account);
        assertEquals("rotated-refresh-token", store.credentials(account).orElseThrow().refreshToken());
        assertTrue(result.tokenExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void refreshWithoutStoredCredentialsAsksForRelogin() throws IOException {
        Account account = Account.microsoft("Steve", UUID_TEXT, "1", Instant.now().minusSeconds(1), null, null);
        AuthException error = assertThrows(AuthException.class, () -> service.refresh(account));
        assertEquals(AuthException.Reason.REAUTH_REQUIRED, error.reason());
        assertTrue(error.requiresRelogin());
    }

    @Test
    void failedRefreshLeavesOldCredentialsIntact() throws Exception {
        Account account = saveMicrosoftAccount(Instant.now().minusSeconds(1), "OLD-REFRESH-TOKEN");
        fake.tokenEndpointFails = true;

        assertThrows(AuthException.class, () -> service.refresh(account));

        assertEquals("OLD-REFRESH-TOKEN", store.credentials(account).orElseThrow().refreshToken(),
                "刷新失败时旧令牌必须原样保留，不能写坏文件");
        assertTrue(Files.readString(store.file(), StandardCharsets.UTF_8).contains("secretsPayload"));
    }

    @Test
    void offlineLoginSavesAndBecomesCurrent() throws Exception {
        AuthService.LoginResult result = service.loginOffline("Steve");
        assertEquals(AccountType.OFFLINE, result.account().type());
        assertTrue(service.current().isPresent());
        assertEquals("Steve", service.current().orElseThrow().name());
        assertTrue(service.credentialsFor(result.account()).isEmpty());
    }

    @Test
    void microsoftLoginRequiresClientId() throws IOException {
        AuthService unconfigured = new AuthService(store, null, uri -> true);
        assertFalse(unconfigured.microsoftAvailable());
        AuthException error = assertThrows(AuthException.class,
                () -> unconfigured.loginMicrosoft(java.time.Duration.ofSeconds(1)));
        assertEquals(AuthException.Reason.NOT_CONFIGURED, error.reason());
    }

    /**
     * 端到端「假浏览器」：从授权 URL 里取出 redirect_uri / state / code_challenge，
     * 像真浏览器那样把授权码回传给 loopback 服务器。
     * 覆盖 PKCE 生成 → state 校验 → 授权码换令牌 → 六步链路 → 加密落盘 全流程。
     */
    @Test
    void interactiveLoginWalksCallbackAndStoresAccount() throws Exception {
        AtomicReference<String> challenge = new AtomicReference<>();
        AuthService interactive = new AuthService(store, fake.auth("client-abc"), authorizeUri -> {
            Map<String, String> query = query(authorizeUri.getRawQuery());
            challenge.set(query.get("code_challenge"));
            String redirect = query.get("redirect_uri");
            String state = query.get("state");
            try {
                HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(redirect + "?code=the-code&state=" + state)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        });

        AuthService.LoginResult result = interactive.loginMicrosoft(java.time.Duration.ofSeconds(15));

        Map<String, String> tokenForm = fake.formByPath.get("/token");
        assertEquals("the-code", tokenForm.get("code"));
        assertEquals("authorization_code", tokenForm.get("grant_type"));
        assertTrue(Pkce.verify(tokenForm.get("code_verifier"), challenge.get()),
                "提交的 code_verifier 必须与授权时的 code_challenge 匹配");

        assertEquals("Steve", result.account().name());
        assertEquals(UUID_TEXT, result.account().uuid());
        assertEquals("12345", result.account().xuid());
        assertTrue(result.account().isMicrosoft());
        assertEquals("mc-access-token", result.credentials().accessToken());
        assertEquals("initial-refresh-token", result.credentials().refreshToken());
        assertTrue(service.current().isPresent(), "登录后应成为当前账号");
        assertFalse(Files.readString(store.file(), StandardCharsets.UTF_8).contains("initial-refresh-token"),
                "refresh_token 必须以密文落盘");
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            map.put(java.net.URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
        }
        return map;
    }
}
