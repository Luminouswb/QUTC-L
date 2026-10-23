package com.qutc.launcher.auth;

import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.config.LauncherConfig;
import com.qutc.launcher.utils.Log;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 账号服务：把「离线登录 / 微软完整链路 / 令牌续期 / 落盘」串起来。
 *
 * <p>阶段 5 的命令行与阶段 7 的 UI 都只跟本类交互，不直接碰
 * {@link MicrosoftAuth} 与 {@link AccountStore}。
 */
public final class AuthService {

    /** 默认等用户完成浏览器登录的时间。 */
    public static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofMinutes(5);

    /** 供测试替换的浏览器启动器；返回 false 表示没打开（需要用户手动点链接）。 */
    public interface BrowserLauncher {
        boolean open(URI uri);
    }

    public record LoginResult(Account account, Credentials credentials) {
    }

    private final AccountStore store;
    private final MicrosoftAuth microsoft;
    private final BrowserLauncher browser;

    public AuthService(AccountStore store, MicrosoftAuth microsoft) {
        this(store, microsoft, AuthService::openBrowserDefault);
    }

    public AuthService(AccountStore store, MicrosoftAuth microsoft, BrowserLauncher browser) {
        this.store = store;
        this.microsoft = microsoft;
        this.browser = browser == null ? uri -> false : browser;
    }

    /**
     * 按全局配置构造。
     *
     * <p>登录方式两种：
     * <ul>
     *   <li>全局配置里没填 Client ID → 用旧版启动器的公开客户端（{@value MicrosoftAuth#LEGACY_CLIENT_ID}），
     *       不需要任何注册，浏览器登录后粘贴回调地址即可；</li>
     *   <li>填了 Client ID → 用自建 Azure 应用的 PKCE + loopback 自动回调。</li>
     * </ul>
     */
    public static AuthService defaults() {
        LauncherConfig config = ConfigManager.get();
        String clientId = config.microsoftClientId;
        MicrosoftAuth microsoft = clientId == null || clientId.isBlank()
                ? MicrosoftAuth.legacy()
                : new MicrosoftAuth(clientId);
        return new AuthService(AccountStore.defaults(), microsoft);
    }

    /** 启动参数里的 clientId：没配 Azure 应用时用内置客户端 ID。 */
    public static String effectiveClientId(LauncherConfig config) {
        String clientId = config == null ? null : config.microsoftClientId;
        return clientId == null || clientId.isBlank() ? MicrosoftAuth.LEGACY_CLIENT_ID : clientId.trim();
    }

    public AccountStore store() {
        return store;
    }

    /** 微软登录是否可用：内置客户端永远可用，自建应用需要 Client ID。 */
    public boolean microsoftAvailable() {
        return microsoft != null;
    }

    /** 是否走旧版内置客户端流程（浏览器登录 + 粘贴回调地址）。 */
    public boolean usesLegacyFlow() {
        return microsoft != null && microsoft.isLegacy();
    }

    /** 旧版流程的授权页地址。 */
    public URI legacyAuthorizeUri() {
        return microsoft == null ? null : microsoft.legacyAuthorizeUri();
    }

    public String microsoftClientId() {
        return microsoft == null ? null : microsoft.clientId();
    }

    // ---- 离线账号 ----

    public LoginResult loginOffline(String name) throws IOException {
        Account account = Account.offline(name);
        store.save(account, null);
        Log.info("Auth", "离线账号已保存: " + account.name() + " / " + account.uuid());
        return new LoginResult(account, null);
    }

    // ---- 微软登录 ----

    /**
     * 完整交互式登录：开回调服务器 → 打开浏览器 → 等授权码 → 走完六步 → 加密保存。
     *
     * <p>不使用设备码流程：总纲明确要求「授权码 + PKCE + loopback 重定向」。
     */
    public LoginResult loginMicrosoft(Duration timeout) throws AuthException, IOException {
        if (microsoft == null) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED,
                    "尚未配置微软 Client ID：请用 --client-id 指定 Azure 应用注册的客户端 ID");
        }
        if (microsoft.isLegacy()) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED,
                    "当前使用内置客户端：请用 loginMicrosoftWithPastedUrl 提交浏览器回调地址");
        }
        String verifier = Pkce.newVerifier();
        String challenge = Pkce.challengeOf(verifier);
        String state = Pkce.newState();
        try (CallbackServer callback = CallbackServer.start(state)) {
            String redirectUri = callback.redirectUri();
            URI authorizeUri = microsoft.authorizeUri(state, challenge, redirectUri);
            Log.info("Auth", "请在浏览器中完成微软登录");
            System.out.println("如果浏览器没有自动打开，请手动访问：");
            System.out.println(authorizeUri);
            if (!browser.open(authorizeUri)) {
                Log.warn("Auth", "无法自动打开浏览器，请手动复制上面的链接");
            }
            CallbackServer.Result result = callback.await(timeout);
            MicrosoftAuth.MsTokens msTokens = microsoft.exchangeCode(result.code(), verifier, redirectUri);
            LoginResult login = completeAndSave(msTokens);
            Log.info("Auth", "微软登录成功: " + login.account().display());
            return login;
        }
    }

    /**
     * 旧版内置客户端流程：用浏览器登录后，把回调地址（含 {@code code}）粘回来。
     *
     * <p>回调页是 {@code https://login.live.com/oauth20_desktop.srf?code=...}——
     * 旧版启动器（Electron）也是从地址栏取这个 code。
     *
     * @param pasted 用户粘贴的完整回调 URL（也接受只贴 code）
     */
    public LoginResult loginMicrosoftWithPastedUrl(String pasted) throws AuthException, IOException {
        if (microsoft == null) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED, "微软登录不可用");
        }
        if (!microsoft.isLegacy()) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED,
                    "当前使用自建 Azure 应用，浏览器登录会自动回调，无需粘贴地址");
        }
        String code = MicrosoftAuth.parseLegacyRedirect(pasted);
        if (code == null) {
            String trimmed = pasted == null ? "" : pasted.trim();
            // 允许用户只贴 code（从地址栏里手动摘出来）
            if (trimmed.matches("[A-Za-z0-9._~+\\-]{20,4096}") && !trimmed.contains("://")) {
                code = trimmed;
            } else {
                throw new AuthException(AuthException.Reason.HTTP_ERROR,
                        "请粘贴以 https://login.live.com/oauth20_desktop.srf 开头的完整地址");
            }
        }
        MicrosoftAuth.MsTokens msTokens = microsoft.exchangeLegacyCode(code);
        LoginResult login = completeAndSave(msTokens);
        Log.info("Auth", "微软登录成功: " + login.account().display());
        return login;
    }

    /**
     * 用已有的 refresh_token 续期（不弹浏览器）。
     *
     * <p>总纲的原子轮换在这里落地：新 refresh_token 一拿到，
     * 立即随 Account + Credentials 一次性原子写盘；写盘成功后才返回新账号对象，
     * 上层随后只会看到新令牌。
     */
    public Account refresh(Account account) throws AuthException, IOException {
        if (microsoft == null) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED, "尚未配置微软 Client ID");
        }
        if (account == null || !account.isMicrosoft()) {
            return account;
        }
        Credentials credentials = store.credentials(account).orElseThrow(() -> new AuthException(
                AuthException.Reason.REAUTH_REQUIRED,
                "本地没有可用的登录凭据（可能换了机器或换了 Windows 用户），请重新登录"));
        if (credentials.refreshToken() == null || credentials.refreshToken().isBlank()) {
            throw new AuthException(AuthException.Reason.REAUTH_REQUIRED, "refresh_token 缺失，请重新登录");
        }
        MicrosoftAuth.MsTokens refreshed = microsoft.refresh(credentials.refreshToken());
        // 微软有权不返回新 refresh_token：此时沿用旧值（旧值仍有效）
        String effectiveRefresh = refreshed.refreshToken() == null || refreshed.refreshToken().isBlank()
                ? credentials.refreshToken()
                : refreshed.refreshToken();
        if (!effectiveRefresh.equals(credentials.refreshToken())) {
            Log.info("Auth", "refresh_token 已轮换，旧值立即作废");
        }
        MicrosoftAuth.Chain chain = microsoft.completeLogin(refreshed);
        Account updated = microsoftAccount(chain, account);
        Credentials next = new Credentials(chain.mcAccessToken(), effectiveRefresh,
                refreshed.accessToken(), chain.expiresAt().toString());
        store.save(updated, next);
        boolean persisted = store.credentials(updated)
                .map(saved -> next.accessToken() != null && next.accessToken().equals(saved.accessToken()))
                .orElse(false);
        if (!persisted) {
            throw new IOException("续期令牌写盘后回读校验失败，已中止以避免继续使用旧 refresh_token");
        }
        Log.debug("Auth", "续期后的令牌已确认写入磁盘");
        return updated;
    }

    /** 启动前调用：令牌快过期就续期，返回最新账号对象。 */
    public Account ensureFresh(Account account) throws AuthException, IOException {
        if (account == null) {
            return null;
        }
        if (account.isYggdrasil()) {
            Credentials credentials = credentialsOf(account);
            if (YggdrasilAuth.validate(account.authServer(), credentials.accessToken(),
                    credentials.refreshToken())) {
                return account;
            }
            Log.info("Auth", "外置登录令牌已失效，正在续期: " + account.name());
            return refreshYggdrasil(account, credentials);
        }
        if (!account.isMicrosoft()) {
            return account;
        }
        if (!account.needsRefresh(Instant.now())) {
            return account;
        }
        Log.info("Auth", "令牌即将过期，正在续期: " + account.name());
        try {
            return refresh(account);
        } catch (AuthException e) {
            if (e.requiresRelogin()) {
                Log.error("Auth", "续期失败，需要重新登录: " + e.getMessage());
            }
            throw e;
        }
    }

    // ---- 第三方外置登录（Yggdrasil / authlib-injector） ----

    /** 账号密码登录外置服务器；密码不落盘，只保存令牌。 */
    public LoginResult loginYggdrasil(String server, String username, String password)
            throws AuthException, IOException {
        YggdrasilAuth.Login login = YggdrasilAuth.authenticate(server, username, password);
        // refreshToken 槽位用来存 clientToken（外置登录续期需要它）
        Credentials credentials = new Credentials(login.accessToken(), login.clientToken(), null, null);
        store.save(login.account(), credentials);
        Log.info("Auth", "外置登录成功: " + login.account().display()
                + " @ " + YggdrasilAuth.shortHash(login.account().authServer()));
        return new LoginResult(login.account(), credentials);
    }

    private Account refreshYggdrasil(Account account, Credentials credentials)
            throws AuthException, IOException {
        YggdrasilAuth.Login login = YggdrasilAuth.refresh(account.authServer(),
                credentials.accessToken(), credentials.refreshToken());
        Account fresh = login.account();
        Account updated = Account.yggdrasil(fresh.name(), fresh.uuid(),
                fresh.authServer(), fresh.authServerName());
        Credentials next = new Credentials(login.accessToken(), login.clientToken(), null, null);
        store.save(updated, next);
        Log.info("Auth", "外置登录已续期: " + updated.name());
        return updated;
    }

    private Credentials credentialsOf(Account account) throws AuthException {
        return store.credentials(account).orElseThrow(() -> new AuthException(
                AuthException.Reason.REAUTH_REQUIRED,
                "本地没有可用的登录凭据，请重新登录 " + account.name()));
    }

    /** 取当前账号（无账号时返回空，由调用方决定用临时离线名还是引导登录）。 */
    public Optional<Account> current() {
        return store.current();
    }

    /** 取游戏启动要用的凭据：离线账号返回空。 */
    public Optional<Credentials> credentialsFor(Account account) {
        return store.credentials(account);
    }

    public void setCurrent(Account account) throws IOException {
        store.setCurrent(account);
    }

    public void logout(Account account) throws IOException {
        store.delete(account);
        Log.info("Auth", "已移除账号: " + account.display());
    }

    // ---- 内部 ----

    private LoginResult completeAndSave(MicrosoftAuth.MsTokens msTokens) throws AuthException, IOException {
        MicrosoftAuth.Chain chain = microsoft.completeLogin(msTokens);
        Account account = microsoftAccount(chain, null);
        Credentials credentials = new Credentials(chain.mcAccessToken(), msTokens.refreshToken(),
                msTokens.accessToken(), chain.expiresAt().toString());
        store.save(account, credentials);
        return new LoginResult(account, credentials);
    }

    private static Account microsoftAccount(MicrosoftAuth.Chain chain, Account previous) {
        String uuid = chain.profile().dashedUuid();
        if (uuid == null || uuid.isBlank()) {
            uuid = previous == null ? null : previous.uuid();
        }
        return Account.microsoft(chain.profile().name(), uuid, chain.xuid(), chain.expiresAt(),
                chain.profile().skinUrl(), chain.profile().capeUrl());
    }

    private static boolean openBrowserDefault(URI uri) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return true;
            }
        } catch (Throwable t) {
            Log.warn("Auth", "打开浏览器失败: " + t.getClass().getSimpleName());
        }
        return false;
    }
}
