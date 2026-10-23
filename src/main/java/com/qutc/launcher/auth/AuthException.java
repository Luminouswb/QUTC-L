package com.qutc.launcher.auth;

/**
 * 账号链路异常：带「用户可读原因 + 机器可判定原因」，UI 层据此决定
 * 是提示重试、引导重新登录，还是给出具体修复步骤（如未注册 Xbox）。
 */
public class AuthException extends Exception {

    public enum Reason {
        /** 网络不通、超时、被中断。 */
        NETWORK,
        /** 刷新令牌失效（invalid_grant），必须重新交互登录。 */
        REAUTH_REQUIRED,
        /** 回调里的 state 不匹配，疑似 CSRF。 */
        STATE_MISMATCH,
        /** 等用户浏览器回调超时。 */
        TIMEOUT,
        /** 微软账号下没有 Xbox 账户。 */
        NO_XBOX_ACCOUNT,
        /** 所在地区/年龄不满足 Xbox 要求。 */
        XBOX_RESTRICTED,
        /** 需要成人验证或家庭组授权。 */
        XBOX_CHILD_ACCOUNT,
        /** 登录成功但该账号未拥有 Minecraft（无 profile）。 */
        NO_MINECRAFT_PROFILE,
        /** 服务端返回了非 2xx 且无法归类。 */
        HTTP_ERROR,
        /** 配置缺失（如没有 Client ID）。 */
        NOT_CONFIGURED
    }

    private final Reason reason;

    public AuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AuthException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 该错误是否必须由用户重新走一次浏览器登录。 */
    public boolean requiresRelogin() {
        return reason == Reason.REAUTH_REQUIRED || reason == Reason.STATE_MISMATCH;
    }
}
