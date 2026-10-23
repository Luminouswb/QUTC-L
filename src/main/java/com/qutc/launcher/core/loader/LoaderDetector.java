package com.qutc.launcher.core.loader;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 加载器识别：判断一个版本里实际装了什么、有没有 OptiFine 混搭。
 *
 * <p>总纲对 OptiFine 的要求是「纯净 / 混搭识别」——它没有公开的下载接口，
 * 只能靠用户的安装器落地后再识别。识别结果供启动前检查链与诊断使用。
 */
public final class LoaderDetector {

    /**
     * @param primary  主加载器（多个命中时按 NeoForge > Forge > Fabric > Quilt 取）
     * @param found    命中的全部加载器
     * @param optifine 是否检测到 OptiFine（独立版本或 mods 里的 OptiFine_*.jar）
     * @param notes    给用户看的识别依据
     */
    public record Detection(String versionId,
                            LoaderKind primary,
                            List<LoaderKind> found,
                            boolean optifine,
                            String optifineSource,
                            List<String> notes) {

        public boolean hasLoader() {
            return primary != null;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("版本 ").append(versionId).append("：");
            sb.append(primary == null ? "原版" : primary.displayName());
            if (optifine && primary != LoaderKind.OPTIFINE) {
                sb.append(" + OptiFine");
                if (optifineSource != null) {
                    sb.append("（").append(optifineSource).append("）");
                }
            }
            return sb.toString();
        }
    }

    private LoaderDetector() {
    }

    public static Detection detect(String versionId) {
        List<String> notes = new ArrayList<>();
        List<LoaderKind> found = new ArrayList<>();
        String jsonText = readVersionJson(versionId);
        if (jsonText != null) {
            String lower = jsonText.toLowerCase(Locale.ROOT);
            if (lower.contains("neoforge") || lower.contains("net.neoforged")) {
                found.add(LoaderKind.NEOFORGE);
                notes.add("版本 JSON 含 NeoForge 标记");
            } else if (lower.contains("minecraftforge") || lower.contains("net.minecraftforge")) {
                found.add(LoaderKind.FORGE);
                notes.add("版本 JSON 含 Forge 标记");
            }
            if (lower.contains("fabric-loader") || lower.contains("net.fabricmc")) {
                found.add(LoaderKind.FABRIC);
                notes.add("版本 JSON 含 Fabric 标记");
            }
            if (lower.contains("quilt-loader") || lower.contains("org.quiltmc")) {
                found.add(LoaderKind.QUILT);
                notes.add("版本 JSON 含 Quilt 标记");
            }
        } else {
            notes.add("版本 JSON 不存在或无法解析");
        }

        LoaderKind idKind = LoaderCatalog.kindOfVersionId(versionId);
        if (idKind != null && !found.contains(idKind)) {
            found.add(idKind);
            notes.add("版本 id 含 " + idKind.displayName() + " 字样");
        }

        boolean optifine = false;
        String optifineSource = null;
        if (jsonText != null && jsonText.toLowerCase(Locale.ROOT).contains("optifine")) {
            optifine = true;
            optifineSource = "版本 JSON";
            notes.add("版本 JSON 含 OptiFine 标记（独立安装）");
        }
        String modsHit = findOptiFineMod(versionId);
        if (modsHit != null) {
            optifine = true;
            optifineSource = modsHit;
            notes.add("mods 中发现 " + modsHit + "（混搭）");
        }

        LoaderKind primary = null;
        // 版本 id 里明确写了加载器时以它为准：Quilt 内嵌 Fabric 兼容层，
        // 只看 JSON 标记会把 Quilt 误判成 Fabric
        LoaderKind idKindForPrimary = LoaderCatalog.kindOfVersionId(versionId);
        if (idKindForPrimary != null && found.contains(idKindForPrimary)) {
            primary = idKindForPrimary;
        }
        if (primary == null) {
            // 无 id 线索时按「越特定越优先」：NeoForge > Forge > Quilt > Fabric > OptiFine
            for (LoaderKind kind : List.of(LoaderKind.NEOFORGE, LoaderKind.FORGE,
                    LoaderKind.QUILT, LoaderKind.FABRIC, LoaderKind.OPTIFINE)) {
                if (found.contains(kind)) {
                    primary = kind;
                    break;
                }
            }
        }
        return new Detection(versionId, primary, List.copyOf(found), optifine, optifineSource, List.copyOf(notes));
    }

    private static String readVersionJson(String versionId) {
        Path json = AppPaths.versionJson(versionId);
        if (!Files.isRegularFile(json)) {
            return null;
        }
        try {
            return Files.readString(json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("Loader", "读取版本 JSON 失败: " + json);
            return null;
        }
    }

    /** 在版本隔离目录与全局 mods 目录里找 OptiFine_*.jar。 */
    private static String findOptiFineMod(String versionId) {
        List<Path> candidates = List.of(
                AppPaths.modsDir(versionId, true),
                AppPaths.modsDir(versionId, false));
        for (Path mods : candidates) {
            if (!Files.isDirectory(mods)) {
                continue;
            }
            try (var stream = Files.list(mods)) {
                Path hit = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                                .startsWith("optifine"))
                        .findFirst()
                        .orElse(null);
                if (hit != null) {
                    return "mods/" + hit.getFileName();
                }
            } catch (IOException e) {
                Log.warn("Loader", "扫描 mods 目录失败: " + mods);
            }
        }
        return null;
    }
}
