package com.qutc.launcher.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第三方外置登录（authlib-injector / Yggdrasil 协议）。
 *
 * <p>对应交接包里的 {@code core/auth/yggdrasil.js}：
 * <ol>
 *   <li>规范化服务器地址，只允许 HTTPS；</li>
 *   <li>GET 根地址，读取 {@code X-Authlib-Injector-API-Location} 与 {@code meta.serverName}；</li>
 *   <li>{@code POST /authserver/authenticate} 拿 accessToken + selectedProfile；</li>
 *   <li>启动前用 {@code /authserver/validate} 校验，失效用 {@code /authserver/refresh} 续期。</li>
 * </ol>
 * 密码只在这一次请求里使用，不落盘。
 */
public final class YggdrasilAuth {

    public static final String DND_PREFIX = "authlib-injector:yggdrasil-server:";
    public static final String LITTLESKIN = "https://littleskin.cn/api/yggdrasil";

    /** 解析后的验证服务器。 */
    public record Server(String apiRoot, String name) {
    }

    /** 登录/续期结果。 */
    public record Login(Account account, String accessToken, String clientToken) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private YggdrasilAuth() {
    }

    /** 去掉聊天窗里拖进来的 authlib-injector 前缀。 */
    public static String parseDnd(String input) {
        String text = input == null ? "" : input.trim();
        if (text.toLowerCase().startsWith(DND_PREFIX)) {
            String encoded = text.substring(DND_PREFIX.length());
            try {
                return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                return encoded;
            }
        }
        return text;
    }

    /** 规范化地址：补 https、去尾斜杠、拒绝 http。 */
    public static String normalize(String input) throws AuthException {
        String value = parseDnd(input);
        if (value.isBlank()) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED, "请输入验证服务器 API 地址");
        }
        if (!value.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            value = "https://" + value;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException e) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED, "验证服务器地址格式不正确");
        }
        if (uri.getHost() == null) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED, "验证服务器地址格式不正确");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED,
                    "验证服务器必须使用 HTTPS，不能使用明文 HTTP");
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /** 读取 ALI 与元数据，验证这确实是一个 Yggdrasil 服务器。 */
    public static Server resolve(String input) throws AuthException {
        String start = normalize(input);
        OAuthHttp.FullResponse first = OAuthHttp.getFull(start, null);
        String ali = first.header("X-Authlib-Injector-API-Location");
        String apiRoot = start;
        if (ali != null && !ali.isBlank()) {
            try {
                apiRoot = normalize(URI.create(start).resolve(ali).toString());
            } catch (RuntimeException e) {
                throw new AuthException(AuthException.Reason.HTTP_ERROR,
                        "验证服务器返回的 API 地址指示无效");
            }
        }
        OAuthHttp.Response metaResponse = OAuthHttp.get(apiRoot, null);
        JsonNode meta = parse(metaResponse, "读取验证服务器元数据");
        JsonNode metaNode = meta.path("meta");
        if (!metaNode.hasNonNull("serverName")) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR,
                    "该地址不是有效的 Yggdrasil 验证服务器（缺少 meta.serverName）");
        }
        return new Server(apiRoot, truncate(metaNode.path("serverName").asText(), 80));
    }

    /** 账号密码登录。密码不会写入磁盘。 */
    public static Login authenticate(String serverInput, String username, String password)
            throws AuthException {
        return loginOn(resolve(serverInput), username, password);
    }

    /** 直接对已知服务器登录（测试与「同一服务器多账号」复用）。 */
    static Login loginOn(Server server, String username, String password) throws AuthException {
        String name = username == null ? "" : username.trim();
        String pass = password == null ? "" : password;
        if (name.isBlank()) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED, "请输入验证服务器账号");
        }
        if (pass.isBlank()) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED, "请输入验证服务器密码");
        }
        if (name.length() > 128 || pass.length() > 256) {
            throw new AuthException(AuthException.Reason.NOT_CONFIGURED, "账号或密码过长");
        }
        String clientToken = randomId(32);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", name);
        body.put("password", pass);
        body.put("clientToken", clientToken);
        body.put("agent", Map.of("name", "Minecraft", "version", 1));
        body.put("requestUser", true);
        OAuthHttp.Response response = post(server.apiRoot(), "/authserver/authenticate", body);
        JsonNode json = parse(response, "外置登录");

        Account account = pickAccount(json, server.apiRoot(), server.name());
        String accessToken = text(json, "accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR, "验证服务器没有返回 accessToken");
        }
        String returnedClient = text(json, "clientToken");
        return new Login(account, accessToken,
                returnedClient == null || returnedClient.isBlank() ? clientToken : returnedClient);
    }

    /** 校验令牌；网络异常按「无法验证」处理，不误报失效。 */
    public static boolean validate(String apiRoot, String accessToken, String clientToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accessToken", accessToken);
            body.put("clientToken", clientToken == null ? "" : clientToken);
            OAuthHttp.Response response = post(apiRoot, "/authserver/validate", body);
            return response.ok();
        } catch (AuthException e) {
            Log.warn("Yggdrasil", "校验令牌失败: " + e.getMessage());
            return false;
        }
    }

    /** 用旧 accessToken 换新令牌（外置服务器通常同时轮换 clientToken）。 */
    public static Login refresh(String apiRoot, String accessToken, String clientToken)
            throws AuthException {
        Server server = resolve(apiRoot);
        return refreshAt(apiRoot, server.name(), accessToken, clientToken);
    }

    /** 直接对已知服务器续期（测试用）。 */
    static Login refreshAt(String apiRoot, String serverName, String accessToken, String clientToken)
            throws AuthException {
        if (accessToken == null || accessToken.isBlank()) {
            throw new AuthException(AuthException.Reason.REAUTH_REQUIRED, "外置登录令牌缺失，请重新登录");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accessToken", accessToken);
        body.put("clientToken", clientToken == null ? "" : clientToken);
        body.put("requestUser", true);
        OAuthHttp.Response response = post(apiRoot, "/authserver/refresh", body);
        JsonNode json = parse(response, "外置登录续期");

        Account account = pickAccount(json, apiRoot, serverName);
        String newToken = text(json, "accessToken");
        if (newToken == null || newToken.isBlank()) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR, "验证服务器刷新令牌失败");
        }
        String newClient = text(json, "clientToken");
        return new Login(account, newToken,
                newClient == null || newClient.isBlank() ? clientToken : newClient);
    }

    // ---- 内部 ----

    private static OAuthHttp.Response post(String apiRoot, String path, Map<String, Object> body)
            throws AuthException {
        com.fasterxml.jackson.databind.node.ObjectNode node = JsonUtils.newObject();
        body.forEach((key, value) -> node.set(key, JsonUtils.mapper().valueToTree(value)));
        return OAuthHttp.postJson(apiRoot + path, node.toString(), null);
    }

    private static Account pickAccount(JsonNode data, String apiRoot, String serverName)
            throws AuthException {
        if (data == null || data.isMissingNode()) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR, "验证服务器返回格式异常");
        }
        JsonNode profile = data.path("selectedProfile");
        if (profile.isMissingNode() || profile.isNull() || !profile.hasNonNull("id")
                || !profile.hasNonNull("name")) {
            JsonNode available = data.path("availableProfiles");
            profile = null;
            if (available.isArray()) {
                for (JsonNode item : available) {
                    if (item.hasNonNull("id") && item.hasNonNull("name")) {
                        profile = item;
                        break;
                    }
                }
            }
        }
        if (profile == null) {
            throw new AuthException(AuthException.Reason.NO_MINECRAFT_PROFILE,
                    "该账号还没有角色，请先在验证服务器创建一个角色");
        }
        String rawUuid = profile.path("id").asText("").replace("-", "").toLowerCase();
        if (!rawUuid.matches("[0-9a-f]{32}")) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR, "验证服务器返回的角色 UUID 非法");
        }
        String dashed = rawUuid.substring(0, 8) + '-' + rawUuid.substring(8, 12) + '-'
                + rawUuid.substring(12, 16) + '-' + rawUuid.substring(16, 20) + '-'
                + rawUuid.substring(20);
        String name = truncate(profile.path("name").asText(), 32);
        return Account.yggdrasil(name, dashed, apiRoot, serverName);
    }

    private static JsonNode parse(OAuthHttp.Response response, String action) throws AuthException {
        JsonNode json = null;
        try {
            if (response.body() != null && !response.body().isBlank()) {
                json = JsonUtils.parse(response.body());
            }
        } catch (Exception ignored) {
            // 非 JSON 响应按错误处理
        }
        if (!response.ok()) {
            String reason = null;
            if (json != null) {
                reason = firstText(json, "errorMessage", "error_description", "error");
            }
            throw new AuthException(AuthException.Reason.HTTP_ERROR,
                    action + "失败: " + (reason == null ? "HTTP " + response.status() : reason));
        }
        if (json == null) {
            throw new AuthException(AuthException.Reason.HTTP_ERROR, action + "失败: 响应不是合法 JSON");
        }
        return json;
    }

    private static String firstText(JsonNode json, String... fields) {
        for (String field : fields) {
            if (json.hasNonNull(field)) {
                return json.path(field).asText();
            }
        }
        return null;
    }

    private static String text(JsonNode json, String field) {
        return json != null && json.hasNonNull(field) ? json.path(field).asText() : null;
    }

    private static String randomId(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes).substring(0, Math.min(length, length * 2));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 服务器地址的短哈希（日志脱敏用）。 */
    static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 12);
        } catch (Exception e) {
            return "?";
        }
    }
}
