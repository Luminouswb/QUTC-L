package com.qutc.launcher.auth;

import com.qutc.launcher.utils.HashUtils;

import java.time.Instant;

/**
 * 账号档案：只放「可公开」的身份信息，<b>不含任何令牌</b>。
 *
 * <p>令牌走 {@link Credentials}，由 {@link AccountStore} 加密落盘，两者严格分离——
 * 这样列表页、配置、日志随手打印 Account 都不会泄密。
 */
public record Account(AccountType type,
                      String name,
                      String uuid,
                      String xuid,
                      String userType,
                      Instant tokenExpiresAt,
                      String skinUrl,
                      String capeUrl,
                      String authServer,
                      String authServerName) {

    /** 离线账号：UUID 用 {@code OfflinePlayer:<name>} 的 MD5（Java UUID v3 规则）。 */
    public static Account offline(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("离线用户名不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 16) {
            throw new IllegalArgumentException("离线用户名不能超过 16 个字符: " + trimmed);
        }
        if (!trimmed.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("离线用户名只能包含字母、数字、下划线: " + trimmed);
        }
        return new Account(AccountType.OFFLINE, trimmed, HashUtils.offlineUuid(trimmed),
                null, "legacy", null, null, null, null, null);
    }

    public static Account microsoft(String name, String uuid, String xuid, Instant expiresAt,
                                    String skinUrl, String capeUrl) {
        return new Account(AccountType.MICROSOFT, name, uuid, xuid, "msa", expiresAt, skinUrl, capeUrl,
                null, null);
    }

    /** 第三方外置登录（authlib-injector / Yggdrasil 协议）。 */
    public static Account yggdrasil(String name, String uuid, String authServer, String authServerName) {
        return new Account(AccountType.YGGDRASIL, name, uuid, null, "mojang", null, null, null,
                authServer, authServerName);
    }

    public boolean isMicrosoft() {
        return type == AccountType.MICROSOFT;
    }

    public boolean isYggdrasil() {
        return type == AccountType.YGGDRASIL;
    }

    /** 需要在线令牌（微软 / 外置登录）。离线账号返回 false。 */
    public boolean online() {
        return isMicrosoft() || isYggdrasil();
    }

    /** 令牌是否已过期或即将过期（提前 5 分钟视为过期，避免开局瞬间失效）。 */
    public boolean needsRefresh(Instant now) {
        if (!online()) {
            return false;
        }
        if (isYggdrasil()) {
            // 外置登录令牌没有统一有效期，启动前用 validate 检查一次
            return true;
        }
        return tokenExpiresAt == null || !now.plusSeconds(300).isBefore(tokenExpiresAt);
    }

    /** 存储键：微软正版用 UUID（名字可改），离线用名字，外置登录用「服务器 + UUID」。 */
    public String storageKey() {
        return switch (type) {
            case MICROSOFT -> "msa:" + (uuid == null ? "" : uuid.toLowerCase());
            case YGGDRASIL -> "ygg:" + (uuid == null ? "" : uuid.toLowerCase())
                    + "@" + (authServer == null ? "" : authServer.toLowerCase());
            case OFFLINE -> "offline:" + name.toLowerCase();
        };
    }

    public String display() {
        String kind = switch (type) {
            case MICROSOFT -> "微软正版";
            case YGGDRASIL -> "外置登录" + (authServerName == null || authServerName.isBlank()
                    ? "" : " · " + authServerName);
            case OFFLINE -> "离线";
        };
        return name + "（" + kind + "）";
    }
}
