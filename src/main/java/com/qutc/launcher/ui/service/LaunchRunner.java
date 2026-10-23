package com.qutc.launcher.ui.service;

import com.qutc.launcher.core.launch.GameProcess;
import com.qutc.launcher.core.launch.LaunchService;
import com.qutc.launcher.download.Downloader;
import com.qutc.launcher.download.MirrorResolver;
import com.qutc.launcher.thread.Async;
import com.qutc.launcher.utils.Log;

/**
 * UI 层的启动执行器：安装 → 预检 → 起进程 → 等退出，全部在后台线程。
 *
 * <p>总纲硬性要求「禁止阻塞 UI 线程」，这里把整条链放进 {@link Async}，
 * 只通过回调把状态送回 UI。
 */
public final class LaunchRunner {

    public interface Listener {
        /** 进度或状态文本（已在 UI 线程）。 */
        void onStatus(String text);

        /** 结束（已在 UI 线程）。 */
        void onFinished(boolean success, String message);
    }

    private LaunchRunner() {
    }

    public static void launch(VersionService.Entry entry, Listener listener) {
        Async.run(() -> {
            try {
                LaunchService.Options options = LaunchOptionsFactory.build(entry);
                Downloader.Listener progress = p -> {
                    String text = p.totalFiles() == 0
                            ? "正在准备…"
                            : "下载 " + p.doneFiles() + "/" + p.totalFiles() + "  " + p.percent()
                            + "%  " + p.bytesPerSecond() / 1024 + " KB/s";
                    Async.ui(() -> listener.onStatus(text));
                };
                Async.ui(() -> listener.onStatus("正在完成启动前检查…"));
                LaunchService.Prepared prepared = LaunchService.prepare(options, progress, MirrorResolver.of(null));
                if (!prepared.preflight().ok()) {
                    Async.ui(() -> listener.onFinished(false, prepared.preflight().summary()));
                    return;
                }
                Async.ui(() -> listener.onStatus("正在启动游戏…"));
                GameProcess process = LaunchService.start(prepared, line -> {
                    if (line.contains("Setting user") || line.contains("ERROR")
                            || line.contains("Exception") || line.contains("Caused by")) {
                        String shortLine = line.length() > 90 ? line.substring(0, 90) : line;
                        Async.ui(() -> listener.onStatus(shortLine));
                    }
                });
                Async.ui(() -> listener.onStatus("游戏运行中 · 日志 " + process.logFile().getFileName()));
                int code = process.waitFor();
                String message = code == 0 ? "游戏已正常退出"
                        : "游戏异常退出，退出码 " + code + "，日志: " + process.logFile();
                Async.ui(() -> listener.onFinished(true, message));
            } catch (Throwable error) {
                Log.error("Launch", "启动失败", error);
                Async.ui(() -> listener.onFinished(false, String.valueOf(error.getMessage())));
            }
        });
    }
}
