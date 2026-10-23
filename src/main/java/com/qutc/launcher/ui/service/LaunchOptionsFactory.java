package com.qutc.launcher.ui.service;

import com.qutc.launcher.auth.Account;
import com.qutc.launcher.auth.AuthService;
import com.qutc.launcher.auth.Credentials;
import com.qutc.launcher.config.ConfigManager;
import com.qutc.launcher.config.LauncherConfig;
import com.qutc.launcher.config.VersionConfig;
import com.qutc.launcher.core.java.JavaStatus;
import com.qutc.launcher.core.launch.LaunchService;
import com.qutc.launcher.core.memory.MemoryAdvisor;
import com.qutc.launcher.utils.HashUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 把「版本配置 + 全局配置 + 当前账号」组装成启动参数。
 *
 * <p>主页与版本管理页共用这一份逻辑，避免两处口径不一致（比如一边用推荐内存、
 * 一边用全局内存）。
 */
public final class LaunchOptionsFactory {

    private LaunchOptionsFactory() {
    }

    public static LaunchService.Options build(VersionService.Entry entry) throws IOException {
        return build(entry, AuthService.defaults(), ConfigManager.get());
    }

    public static LaunchService.Options build(VersionService.Entry entry, AuthService auth, LauncherConfig global)
            throws IOException {
        VersionConfig config = entry.config();

        Account account = auth.current().orElse(null);
        if (account != null && account.isMicrosoft()) {
            try {
                account = auth.ensureFresh(account);
            } catch (com.qutc.launcher.auth.AuthException e) {
                throw new IOException("账号续期失败：" + e.getMessage()
                        + (e.requiresRelogin() ? "，请在主页重新登录" : ""), e);
            }
        }
        String username = account != null ? account.name() : "Player";
        String uuid = account != null ? account.uuid() : HashUtils.offlineUuid(username);
        String accessToken = "0";
        String userType = account != null ? account.userType() : "legacy";
        String xuid = account == null ? null : account.xuid();
        String clientId = account != null && account.isMicrosoft()
                ? AuthService.effectiveClientId(global)
                : null;
        if (account != null && account.online()) {
            Optional<Credentials> credentials = auth.credentialsFor(account);
            if (credentials.isEmpty()) {
                throw new IOException("账号 " + account.name() + " 的登录凭据不可用，请在主页重新登录");
            }
            accessToken = credentials.get().accessToken();
        }

        MemoryAdvisor.Recommendation recommendation = MemoryAdvisor.recommend(entry.modCount(),
                JavaStatus.hasShaders(entry.versionId()));
        int memory = config.memoryMb > 0 ? config.memoryMb : recommendation.recommendedMb();
        if (global.memoryMode == LauncherConfig.MemoryMode.GLOBAL && config.memoryMb <= 0
                && global.globalMemoryMb > 0) {
            memory = global.globalMemoryMb;
        }
        int width = config.width > 0 ? config.width : global.resolutionWidth;
        int height = config.height > 0 ? config.height : global.resolutionHeight;
        boolean fullscreen = config.fullscreen || global.fullscreen;
        Path javaHome = config.javaPath == null || config.javaPath.isBlank() ? null : Path.of(config.javaPath);

        return new LaunchService.Options(
                entry.versionId(),
                username,
                uuid,
                memory,
                width,
                height,
                fullscreen,
                config.isolated,
                true,
                true,
                Math.max(1, global.parallelDownloads),
                javaHome,
                List.copyOf(config.jvmArgs),
                List.copyOf(config.gameArgs),
                true,
                accessToken,
                userType,
                xuid,
                clientId,
                entry.imported() ? Path.of(entry.externalRoot()) : null);
    }
}
