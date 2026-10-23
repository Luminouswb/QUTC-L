package com.qutc.launcher.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 临时目录可用性守卫。
 *
 * <p>为什么必须在启动最早期执行：JNA 加载 {@code jnidispatch.dll} 时会解压到
 * {@code java.io.tmpdir}，ZIP 解压与下载断点也会用临时文件。若玩家机器的
 * {@code %TEMP%} 权限异常（企业管控、磁盘写保护、被安全软件锁定），
 * 这些功能会以 {@code AccessDeniedException} / {@code UnsatisfiedLinkError} 的形式
 * 连环失败，而日志里看不出根因。这里提前探测并切到数据目录下的自建缓存目录。
 *
 * <p><b>关键坑（已踩过）</b>：{@code java.nio.file.Files.createTempFile(prefix, suffix)}
 * 会把默认临时目录在类初始化时缓存下来，之后再改 {@code java.io.tmpdir} 对它无效。
 * 因此：
 * <ul>
 *   <li>本类探测与对外提供的 {@link #tempFile} / {@link #tempDir} 一律显式传目录；</li>
 *   <li>全项目禁止使用不带目录参数的 {@code Files.createTempFile/createTempDirectory}。</li>
 * </ul>
 */
public final class TempDir {

    private static volatile Path effective;

    private TempDir() {
    }

    /**
     * 确保存在可写临时目录，必要时改写 {@code java.io.tmpdir}。
     *
     * @param fallbackRoot 数据目录根（例如 AppPaths.cacheDir()）
     * @return 实际生效的临时目录
     */
    public static synchronized Path ensureWritable(Path fallbackRoot) {
        if (effective != null) {
            return effective;
        }
        Path systemTemp = Path.of(System.getProperty("java.io.tmpdir", "."));
        if (probe(systemTemp)) {
            effective = systemTemp;
            return effective;
        }

        Log.warn("Temp", "系统临时目录不可写，切换到数据目录缓存: " + systemTemp);
        Path fallback = fallbackRoot.resolve("tmp");
        try {
            Files.createDirectories(fallback);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建临时目录: " + fallback, e);
        }
        if (!probe(fallback)) {
            throw new IllegalStateException("数据目录下的临时目录仍不可写: " + fallback);
        }
        // 给 JNA 等「自己读属性」的库用；本项目的 Files 调用都走显式目录
        System.setProperty("java.io.tmpdir", fallback.toAbsolutePath().toString());
        effective = fallback.toAbsolutePath();
        Log.info("Temp", "java.io.tmpdir 已改写为 " + effective);
        return effective;
    }

    public static Path get() {
        Path current = effective;
        if (current != null) {
            return current;
        }
        // 未显式初始化时（例如单元测试）懒初始化，避免退回到可能不可写的系统临时目录
        synchronized (TempDir.class) {
            if (effective != null) {
                return effective;
            }
            try {
                return ensureWritable(defaultFallbackRoot());
            } catch (RuntimeException e) {
                Log.warn("Temp", "临时目录懒初始化失败，退回系统目录", e);
                return Path.of(System.getProperty("java.io.tmpdir", "."));
            }
        }
    }

    private static Path defaultFallbackRoot() {
        return Path.of(System.getProperty("user.dir", ".")).resolve("cache");
    }

    /** 在受控临时目录创建临时文件（显式传目录，绕开 JDK 的缓存）。 */
    public static Path tempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(get(), prefix, suffix);
    }

    /** 在受控临时目录创建临时子目录。 */
    public static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory(get(), prefix);
    }

    /**
     * 探测可写性。刻意不用 {@code Files.createTempFile}：那会用带随机名的 CREATE_NEW，
     * 既避免触发 JDK 的默认目录缓存，也更接近真实写入路径。
     */
    private static boolean probe(Path dir) {
        Path probeFile = null;
        try {
            Files.createDirectories(dir);
            probeFile = dir.resolve(".qutc-tmp-" + UUID.randomUUID() + ".probe");
            try (OutputStream out = Files.newOutputStream(probeFile,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                out.write('o');
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (probeFile != null) {
                try {
                    Files.deleteIfExists(probeFile);
                } catch (IOException ignored) {
                    // 清理失败不影响判定
                }
            }
        }
    }
}
