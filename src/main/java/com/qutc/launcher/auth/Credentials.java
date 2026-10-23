package com.qutc.launcher.auth;

/**
 * 一份令牌集合（全部属于「敏感」类）。
 *
 * @param accessToken    Minecraft 服务端 AccessToken（进游戏用）
 * @param refreshToken   微软 OAuth 的 refresh_token（长期凭据，轮换用）
 * @param msAccessToken  微软 OAuth 的 access_token（换 XBL/XSTS 用，短期）
 * @param expiresAt      Minecraft AccessToken 过期时间，ISO-8601 文本；离线账号为 null
 */
public record Credentials(String accessToken,
                          String refreshToken,
                          String msAccessToken,
                          String expiresAt) {

    public java.time.Instant expiresInstant() {
        if (expiresAt == null || expiresAt.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(expiresAt);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 只改 Minecraft 令牌，保留 refresh/ms 令牌（刷新链的中间态）。 */
    public Credentials withMinecraftToken(String newAccessToken, java.time.Instant newExpiry) {
        return new Credentials(newAccessToken, refreshToken, msAccessToken,
                newExpiry == null ? null : newExpiry.toString());
    }

    /** RefreshToken 轮换：新 refresh_token 就位，旧的随之作废。 */
    public Credentials withRotatedRefreshToken(String newRefreshToken, String newMsAccessToken) {
        return new Credentials(accessToken,
                newRefreshToken == null || newRefreshToken.isBlank() ? refreshToken : newRefreshToken,
                newMsAccessToken == null ? msAccessToken : newMsAccessToken,
                expiresAt);
    }

    public Credentials withMsAccessToken(String newMsAccessToken) {
        return new Credentials(accessToken, refreshToken,
                newMsAccessToken == null ? msAccessToken : newMsAccessToken, expiresAt);
    }
}
