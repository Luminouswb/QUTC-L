package com.qutc.launcher.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微软登录完整合规链路（总纲 §三.3 的六步）。
 *
 * <pre>
 *   1. OAuth2 授权码 + PKCE（S256）+ state     login.live.com/oauth20_authorize.srf
 *   2. 授权码换 Microsoft Token                login.live.com/oauth20_token.srf
 *   3. Microsoft Token 换 XBL Token            user.auth.xboxlive.com/user/authenticate
 *   4. XBL Token 换 XSTS Token                 xsts.auth.xboxlive.com/xsts/authorize
 *   5. XSTS Token 换 Minecraft AccessToken     api.minecraftservices.com/authentication/login_with_xbox
 *   6. 拉取玩家 Profile / 皮肤 / 披风           api.minecraftservices.com/minecraft/profile
 * </pre>
 *
 * <p>所有端点都通过 {@link Endpoints} 注入，单元测试用本地假服务器覆盖
 * 「请求长什么样、响应怎么解析、错误怎么归类」，不必依赖真实账号。
 *
 * <p>RefreshToken 轮换语义：{@link #refresh} 只负责发请求与解析，
 * 「拿到新 refresh_token 立即原子覆盖并停止使用旧值」由
 * {@link AuthService#ensureFresh} + {@link AccountStore#save} 保证——
 * 两者之间没有异步窗口，写盘成功后才把新令牌交还上层。
 */
public final class MicrosoftAuth {

    /** 微软 OAuth scope：登录 Xbox Live + 允许长期刷新（自建 Azure 应用用）。 */
    public static final String SCOPE = "XboxLive.signin offline_access";

    /** 旧版启动器公开客户端（Minecraft 官方启动器在 login.live.com 的客户端 ID）。 */
    public static final String LEGACY_CLIENT_ID = "00000000402b5328";

    /** 旧版客户端的回调地址：登录完成后微软把 code 拼在这个地址上。 */
    public static final String LEGACY_REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";

    /** 旧版客户端只接受 MBI_SSL 这个 scope。 */
    public static final String LEGACY_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    /** 可注入的端点集合（测试用本地服务器替换）。 */
    public record Endpoints(String authorize,
                            String token,
                            String xbl,
                            String xsts,
                            String mcLogin,
                            String profile) {

        public static Endpoints official() {
            return new Endpoints(
                    "https://login.live.com/oauth20_authorize.srf",
                    "https://login.live.com/oauth20_token.srf",
                    "https://user.auth.xboxlive.com/user/authenticate",
                    "https://xsts.auth.xboxlive.com/xsts/authorize",
                    "https://api.minecraftservices.com/authentication/login_with_xbox",
                    "https://api.minecraftservices.com/minecraft/profile");
        }
    }

    /** 微软 OAuth 令牌对。refresh_token 可能为空（刷新时微软有权不返回新值，表示沿用旧的）。 */
    public record MsTokens(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    /** XBL/XSTS 结果：Token + DisplayClaims 里的 user hash / XUID。 */
    public record XboxTokens(String token, String userHash, String xuid) {
    }

    /** Minecraft Profile：UUID 是去掉连字符的 32 位十六进制（微软原样返回）。 */
    public record Profile(String id, String name, String skinUrl, String capeUrl) {
        /** 转成带连字符的标准 UUID 文本。 */
        public String dashedUuid() {
            if (id == null || id.length() != 32) {
                return id;
            }
            return id.substring(0, 8) + '-' + id.substring(8, 12) + '-' + id.substring(12, 16) + '-'
                    + id.substring(16, 20) + '-' + id.substring(20);
        }
    }

    /** 走完整条链后的结果。 */
    public record Chain(String mcAccessToken, Instant expiresAt, String userHash, String xuid, Profile profile) {
    }

    private final String clientId;
    private final Endpoints endpoints;
    private final boolean legacy;

    public MicrosoftAuth(String clientId) {
        this(clientId, Endpoints.official());
    }

    public MicrosoftAuth(String clientId, Endpoints endpoints) {
        this(clientId, endpoints, false);
    }

    private MicrosoftAuth(String clientId, Endpoints endpoints, boolean legacy) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("微软登录需要 Client ID（Azure 应用注册）");
        }
        this.clientId = clientId.trim();
        this.endpoints = endpoints == null ? Endpoints.official() : endpoints;
        this.legacy = legacy;
    }

    /**
     * 旧版启动器方式：用微软公开的 Minecraft 启动器客户端（{@value #LEGACY_CLIENT_ID}），
     * 不需要注册 Azure 应用，登录完成后从浏览器地址栏取回调 URL 里的 code。
     */
    public static MicrosoftAuth legacy() {
        return new MicrosoftAuth(LEGACY_CLIENT_ID, Endpoints.official(), true);
    }

    /** 测试/自定义端点用的旧版客户端。 */
    public static MicrosoftAuth legacy(Endpoints endpoints) {
        return new MicrosoftAuth(LEGACY_CLIENT_ID, endpoints, true);
    }

    /** 是否使用旧版内置客户端流程。 */
    public boolean isLegacy() {
        return legacy;
    }

    public String clientId() {
        return clientId;
    }

    public Endpoints endpoints() {
        return endpoints;
    }

    /** 旧版流程的授权页：无 PKCE、无 state、固定回调地址。 */
    public URI legacyAuthorizeUri() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("response_type", "code");
        params.put("redirect_uri", LEGACY_REDIRECT_URI);
        params.put("scope", LEGACY_SCOPE);
        params.put("prompt", "select_account");
        return buildUri(endpoints.authorize(), params);
    }

    /**
     * 从用户粘贴的回调 URL 里解析授权码。
     *
     * @return 授权码；不是回调地址时返回 {@code null}
     * @throws AuthException 回调里带 error（用户拒绝授权等）
     */
    public static String parseLegacyRedirect(String input) throws AuthException {
        String text = input == null ? "" : input.trim();
        if (text.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getHost() == null || !"login.live.com".equalsIgnoreCase(uri.getHost())
                || uri.getPath() == null || !uri.getPath().startsWith("/oauth20_desktop.srf")) {
            return null;
        }
        Map<String, String> query = parseQuery(uri.getRawQuery());
        String error = query.get("error");
        if (error != null && !error.isBlank()) {
            String description = query.getOrDefault("error_description", "微软登录被拒绝");
            throw new AuthException(AuthException.Reason.HTTP_ERROR, description);
        }
        String code = query.get("code");
        if (code == null || code.isBlank() || code.length() > 4096) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR, "授权回调里没有有效的 code");
        }
        return code;
    }

    /** 旧版流程：授权码换 Microsoft Token（不带 PKCE verifier）。 */
    public MsTokens exchangeLegacyCode(String code) throws AuthException {
        if (code == null || code.isBlank()) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR, "授权码为空");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("code", code);
        form.put("grant_type", "authorization_code");
        form.put("redirect_uri", LEGACY_REDIRECT_URI);
        form.put("scope", LEGACY_SCOPE);
        return toMsTokens(OAuthHttp.postForm(endpoints.token(), form), "授权码换取令牌");
    }

    /** 步骤 1：拼授权页 URL（PKCE challenge + state + loopback redirect）。 */
    public URI authorizeUri(String state, String challenge, String redirectUri) {
        if (legacy) {
            return legacyAuthorizeUri();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("scope", SCOPE);
        params.put("state", state);
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        params.put("prompt", "select_account");
        return buildUri(endpoints.authorize(), params);
    }

    private static URI buildUri(String base, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(base);
        sb.append(base.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return URI.create(sb.toString());
    }

    /** 步骤 2：授权码换 Microsoft Token。 */
    public MsTokens exchangeCode(String code, String verifier, String redirectUri) throws AuthException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("code", code);
        form.put("grant_type", "authorization_code");
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", verifier);
        return toMsTokens(OAuthHttp.postForm(endpoints.token(), form), "授权码换取令牌");
    }

    /**
     * 用 refresh_token 换新的 Microsoft Token。
     *
     * <p>响应里<b>可能</b>带新的 refresh_token（轮换）。调用方必须把返回值里的
     * refresh_token 原样保存并立即弃用旧值。
     */
    public MsTokens refresh(String refreshToken) throws AuthException {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthException(AuthException.Reason.REAUTH_REQUIRED, "没有可用于续期的 refresh_token");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("scope", legacy ? LEGACY_SCOPE : SCOPE);
        if (legacy) {
            form.put("redirect_uri", LEGACY_REDIRECT_URI);
        }
        return toMsTokens(OAuthHttp.postForm(endpoints.token(), form), "刷新令牌");
    }

    /** 步骤 3+4：Microsoft Token → XBL → XSTS。 */
    public XboxTokens authenticateXbox(String msAccessToken) throws AuthException {
        // 旧版内置客户端（Xbox 官方客户端）用 t=；自建 Azure 应用用 d=。401 时换另一个前缀再试一次。
        String primary = legacy ? "t=" : "d=";
        String secondary = legacy ? "d=" : "t=";
        AuthException failure;
        try {
            return authenticateXboxWithTicket(primary + msAccessToken);
        } catch (AuthException e) {
            failure = e;
        }
        try {
            return authenticateXboxWithTicket(secondary + msAccessToken);
        } catch (AuthException e) {
            throw failure;
        }
    }

    private XboxTokens authenticateXboxWithTicket(String rpsTicket) throws AuthException {
        ObjectNode xblBody = JsonUtils.newObject();
        ObjectNode properties = xblBody.putObject("Properties");
        properties.put("AuthMethod", "RPS");
        properties.put("SiteName", "user.auth.xboxlive.com");
        properties.put("RpsTicket", rpsTicket);
        xblBody.put("RelyingParty", "http://auth.xboxlive.com");
        xblBody.put("TokenType", "JWT");
        JsonNode xbl = requireOk(OAuthHttp.postJson(endpoints.xbl(), xblBody.toString(), null), "Xbox Live 登录");
        String xblToken = text(xbl, "Token");
        String userHash = displayClaim(xbl, "uhs");
        String xuid = displayClaim(xbl, "xid");

        ObjectNode xstsBody = JsonUtils.newObject();
        ObjectNode xstsProperties = xstsBody.putObject("Properties");
        xstsProperties.put("SandboxId", "RETAIL");
        xstsProperties.putArray("UserTokens").add(xblToken);
        xstsBody.put("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.put("TokenType", "JWT");
        OAuthHttp.Response xstsResponse = OAuthHttp.postJson(endpoints.xsts(), xstsBody.toString(), null);
        if (!xstsResponse.ok()) {
            throw xstsError(xstsResponse);
        }
        JsonNode xsts = parse(xstsResponse, "XSTS 授权");
        String xstsToken = text(xsts, "Token");
        String xstsHash = displayClaim(xsts, "uhs");
        String xstsXuid = displayClaim(xsts, "xid");
        return new XboxTokens(xstsToken,
                xstsHash != null ? xstsHash : userHash,
                xstsXuid != null ? xstsXuid : xuid);
    }

    /** 步骤 5：XSTS Token 换 Minecraft AccessToken。 */
    public MsTokens loginWithXbox(String userHash, String xstsToken) throws AuthException {
        ObjectNode body = JsonUtils.newObject();
        body.put("identityToken", "XBL3.0 x=" + userHash + ';' + xstsToken);
        JsonNode json = requireOk(OAuthHttp.postJson(endpoints.mcLogin(), body.toString(), null),
                "Minecraft 登录");
        String accessToken = text(json, "access_token");
        long expiresIn = json.path("expires_in").asLong(86400);
        return new MsTokens(accessToken, null, expiresIn);
    }

    /** 步骤 6：拉取 Profile（含皮肤与披风）。 */
    public Profile profile(String mcAccessToken) throws AuthException {
        OAuthHttp.Response response = OAuthHttp.get(endpoints.profile(), mcAccessToken);
        if (response.status() == 404) {
            throw new AuthException(AuthException.Reason.NO_MINECRAFT_PROFILE,
                    "登录成功，但该微软账号未拥有 Minecraft: Java Edition");
        }
        JsonNode json = requireOk(response, "拉取玩家档案");
        String id = text(json, "id");
        String name = text(json, "name");
        String skinUrl = null;
        String capeUrl = null;
        JsonNode skins = json.path("skins");
        if (skins.isArray() && !skins.isEmpty()) {
            JsonNode skin = skins.get(0);
            if ("ACTIVE".equalsIgnoreCase(skin.path("state").asText("ACTIVE"))) {
                skinUrl = text(skin, "url");
            }
        }
        JsonNode capes = json.path("capes");
        if (capes.isArray() && !capes.isEmpty()) {
            capeUrl = text(capes.get(0), "url");
        }
        if (name == null || name.isBlank()) {
            throw new AuthException(AuthException.Reason.NO_MINECRAFT_PROFILE, "玩家档案缺少用户名");
        }
        return new Profile(id, name, skinUrl, capeUrl);
    }

    /** 走完 3→6 步。 */
    public Chain completeLogin(MsTokens msTokens) throws AuthException {
        if (msTokens == null || msTokens.accessToken() == null) {
            throw new AuthException(AuthException.Reason.REAUTH_REQUIRED, "缺少微软 access_token");
        }
        XboxTokens xbox = authenticateXbox(msTokens.accessToken());
        MsTokens mc = loginWithXbox(xbox.userHash(), xbox.token());
        Profile profile = profile(mc.accessToken());
        long expiresIn = mc.expiresInSeconds() > 0 ? mc.expiresInSeconds() : 86400;
        return new Chain(mc.accessToken(), Instant.now().plusSeconds(expiresIn),
                xbox.userHash(), xbox.xuid(), profile);
    }

    // ---- 响应解析与错误归类 ----

    private MsTokens toMsTokens(OAuthHttp.Response response, String action) throws AuthException {
        if (!response.ok()) {
            JsonNode error = safeParse(response.body());
            String code = error == null ? null : error.path("error").asText(null);
            String description = error == null ? null : error.path("error_description").asText(null);
            if ("invalid_grant".equalsIgnoreCase(code)) {
                throw new AuthException(AuthException.Reason.REAUTH_REQUIRED,
                        "登录凭据已失效（invalid_grant），请重新登录: " + nullToEmpty(description));
            }
            if ("invalid_client".equalsIgnoreCase(code) || "unauthorized_client".equalsIgnoreCase(code)) {
                throw new AuthException(AuthException.Reason.NOT_CONFIGURED,
                        "Client ID 未被微软接受（" + code + "），请检查应用注册: " + nullToEmpty(description));
            }
            throw new AuthException(AuthException.Reason.HTTP_ERROR,
                    action + "失败: HTTP " + response.status() + ' '
                            + (code == null ? nullToEmpty(description) : code + " " + nullToEmpty(description)));
        }
        JsonNode json = parse(response, action);
        String accessToken = text(json, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR, action + "失败: 响应缺少 access_token");
        }
        String refreshToken = text(json, "refresh_token");
        long expiresIn = json.path("expires_in").asLong(3600);
        return new MsTokens(accessToken, refreshToken, expiresIn);
    }

    private AuthException xstsError(OAuthHttp.Response response) {
        JsonNode json = safeParse(response.body());
        long xerr = json == null ? 0 : json.path("XErr").asLong(0);
        String message = json == null ? "" : json.path("Message").asText("");
        Log.warn("Auth", "XSTS 拒绝: XErr=" + xerr + " " + message);
        if (xerr == 2148916233L) {
            return new AuthException(AuthException.Reason.NO_XBOX_ACCOUNT,
                    "该微软账号尚未创建 Xbox 账户，请先访问 https://www.xbox.com 完成创建后重试");
        }
        if (xerr == 2148916235L) {
            return new AuthException(AuthException.Reason.XBOX_RESTRICTED, "该微软账号所在地区不支持 Xbox 服务");
        }
        if (xerr == 2148916227L) {
            return new AuthException(AuthException.Reason.XBOX_RESTRICTED,
                    "该微软账号已被 Xbox Live 封禁: " + message);
        }
        if (xerr == 2148916236L || xerr == 2148916237L || xerr == 2148916238L) {
            return new AuthException(AuthException.Reason.XBOX_CHILD_ACCOUNT,
                    "该账号受家庭组/成人验证限制，需家长或成年人完成验证: XErr=" + xerr);
        }
        return new AuthException(AuthException.Reason.HTTP_ERROR,
                "Xbox 授权失败: HTTP " + response.status() + " XErr=" + xerr
                        + (message.isBlank() ? "" : "（" + message + "）"));
    }

    private JsonNode requireOk(OAuthHttp.Response response, String action) throws AuthException {
        if (!response.ok()) {
            JsonNode error = safeParse(response.body());
            String detail = error == null ? response.body() : error.toString();
            if (response.status() == 401 || response.status() == 403) {
                throw new AuthException(AuthException.Reason.REAUTH_REQUIRED,
                        action + "未通过授权（HTTP " + response.status() + "）: " + truncate(detail));
            }
            throw new AuthException(AuthException.Reason.HTTP_ERROR,
                    action + "失败: HTTP " + response.status() + " " + truncate(detail));
        }
        return parse(response, action);
    }

    private static JsonNode parse(OAuthHttp.Response response, String action) throws AuthException {
        try {
            return JsonUtils.parse(response.body() == null ? "" : response.body());
        } catch (Exception e) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR,
                    action + "失败: 响应不是合法 JSON", e);
        }
    }

    private static JsonNode safeParse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parse(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String displayClaim(JsonNode node, String field) {
        JsonNode xui = node == null ? null : node.path("DisplayClaims").path("xui");
        if (xui == null || !xui.isArray() || xui.isEmpty()) {
            return null;
        }
        return text(xui.get(0), field);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 解析 query 字符串（保留 +，不按表单语义转空格）。 */
    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String key = percentDecode(eq < 0 ? part : part.substring(0, eq));
            String value = eq < 0 ? "" : percentDecode(part.substring(eq + 1));
            values.putIfAbsent(key, value);
        }
        return values;
    }

    /**
     * 只做百分号解码，<b>不</b>把 {@code +} 当空格——
     * 微软授权码里可能出现 {@code +}，按表单语义解会得到另一个值（换票 401）。
     */
    private static String percentDecode(String value) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.write((high << 4) | low);
                    i += 2;
                    continue;
                }
            }
            flushBytes(bytes, sb);
            sb.append(c);
        }
        flushBytes(bytes, sb);
        return sb.toString();
    }

    private static void flushBytes(java.io.ByteArrayOutputStream bytes, StringBuilder sb) {
        if (bytes.size() > 0) {
            sb.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            bytes.reset();
        }
    }
}
