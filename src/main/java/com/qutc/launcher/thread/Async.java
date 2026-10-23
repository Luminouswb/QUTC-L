package com.qutc.launcher.thread;

import com.qutc.launcher.utils.Log;
import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步调度中心。
 *
 * <p>总纲硬性要求：任何 UI / 网络 / IO 耗时操作必须异步，绝不阻塞 JavaFX 主线程。
 * 所有后台任务统一走本类的有界线程池，UI 回调统一走 {@link #ui(Runnable)}。
 */
public final class Async {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static final ThreadFactory FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "qutc-worker-" + COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };

    private static final ExecutorService POOL = Executors.newCachedThreadPool(FACTORY);
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "qutc-timer-" + COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private Async() {
    }

    public static CompletableFuture<Void> run(Runnable task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.error("Async", "后台任务异常", t);
                throw t;
            }
        }, POOL);
    }

    public static <T> CompletableFuture<T> supply(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                Log.error("Async", "后台任务异常", e);
                throw new RuntimeException(e);
            }
        }, POOL);
    }

    /** 回 UI 线程；JavaFX 尚未初始化时直接同步执行，避免测试环境 NPE。 */
    public static void ui(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
                return;
            }
            Platform.runLater(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    Log.error("Async", "UI 回调异常", t);
                }
            });
        } catch (IllegalStateException toolkitNotStarted) {
            action.run();
        }
    }

    public static ScheduledExecutorService scheduler() {
        return SCHEDULER;
    }

    public static void shutdown() {
        POOL.shutdownNow();
        SCHEDULER.shutdownNow();
        try {
            POOL.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
