package com.qutc.launcher.ui.service;

import com.qutc.launcher.auth.Account;
import com.qutc.launcher.auth.AccountStore;
import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.core.skin.Skins;
import com.qutc.launcher.download.Http;
import com.qutc.launcher.thread.Async;
import com.qutc.launcher.utils.Log;

import javafx.scene.image.Image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 当前账号皮肤：下载 / 缓存 / 通知 UI 刷新。
 *
 * <p>所有方法都不在 FX 线程做网络；监听器只在 FX 线程触发。
 */
public final class SkinService {

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Image image;
    private static volatile boolean slim;
    private static volatile String accountKey;
    private static volatile boolean loading;

    private SkinService() {
    }

    /** 当前皮肤（没有时返回默认皮肤，一定非空）。 */
    public static Image skinImage() {
        Image current = image;
        return current == null ? Skins.defaultSkin() : current;
    }

    public static boolean slim() {
        return slim;
    }

    /** 账号变化后调用：重新解析皮肤并通知界面。 */
    public static void addListener(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void refreshAsync() {
        refreshAsync(null);
    }

    /** @param onDone 在 FX 线程执行（可为空） */
    public static void refreshAsync(Runnable onDone) {
        if (loading) {
            if (onDone != null) {
                Async.ui(onDone);
            }
            return;
        }
        loading = true;
        Async.run(() -> {
            try {
                load();
            } catch (Throwable error) {
                Log.warn("Skin", "加载皮肤失败: " + error.getMessage());
            } finally {
                loading = false;
            }
            Async.ui(() -> {
                for (Runnable listener : LISTENERS) {
                    try {
                        listener.run();
                    } catch (RuntimeException error) {
                        Log.warn("Skin", "皮肤监听器异常: " + error.getMessage());
                    }
                }
                if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    /** 后台线程执行：解析账号 → 缓存命中则直接读，否则下载。 */
    private static void load() {
        if (System.getProperty("qutc.skin.default") != null) {
            apply(Skins.defaultSkin(), false);
            return;
        }
        Account account = AccountStore.defaults().current().orElse(null);
        String key = account == null ? "default" : safe(account.storageKey());
        Path dir = AppPaths.cacheDir().resolve("skins");
        Path cache = dir.resolve(key + ".png");
        if (!key.equals(accountKey) || image == null) {
            accountKey = key;
            BufferedImage loaded = readCache(cache);
            if (loaded == null && account != null && account.skinUrl() != null
                    && !account.skinUrl().isBlank()) {
                try {
                    Files.createDirectories(dir);
                    byte[] data = Http.getBytes(account.skinUrl(), 30);
                    BufferedImage decoded = Skins.normalize(Skins.decode(data));
                    if (decoded != null) {
                        javax.imageio.ImageIO.write(decoded, "png", cache.toFile());
                        loaded = decoded;
                        Log.info("Skin", "已缓存皮肤: " + key);
                    }
                } catch (IOException error) {
                    Log.warn("Skin", "下载皮肤失败: " + error.getMessage());
                }
            }
            if (loaded != null) {
                apply(Skins.toFxImage(loaded), Skins.isSlim(loaded));
            } else {
                apply(Skins.defaultSkin(), false);
            }
        }
    }

    /** 账号切换后强制重新读缓存（例如刚换过皮肤）。 */
    public static void invalidate() {
        accountKey = null;
    }

    private static BufferedImage readCache(Path cache) {
        if (!Files.isRegularFile(cache)) {
            return null;
        }
        try {
            return Skins.normalize(Skins.decode(Files.readAllBytes(cache)));
        } catch (IOException error) {
            return null;
        }
    }

    private static void apply(Image skin, boolean slimSkin) {
        image = skin;
        slim = slimSkin;
    }

    private static String safe(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
