package com.qutc.launcher.auth;

/** 账号类型。 */
public enum AccountType {
    /** 离线账号：UUID 由名字推导，accessToken 固定为 "0"。 */
    OFFLINE,
    /** 微软正版：OAuth2 授权码 + PKCE，令牌加密存储。 */
    MICROSOFT,
    /** 第三方外置登录：Yggdrasil / authlib-injector 协议。 */
    YGGDRASIL
}
