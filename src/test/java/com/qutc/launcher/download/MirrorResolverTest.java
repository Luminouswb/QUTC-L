package com.qutc.launcher.download;

import com.qutc.launcher.config.LauncherConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 镜像解析规则：八条官方域名前缀 + 四档源的 fallback 顺序。 */
class MirrorResolverTest {

    private static final String BMCL = MirrorResolver.BMCLAPI_BASE;
    private static final String MCBBS = MirrorResolver.MCBBS_BASE;

    @Test
    void pistonMetaKeepsPath() {
        assertEquals(BMCL + "/mc/game/version_manifest_v2.json",
                MirrorResolver.rewrite(BMCL,
                        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
        assertEquals(BMCL + "/v1/packages/abc/5.json",
                MirrorResolver.rewrite(BMCL,
                        "https://piston-meta.mojang.com/v1/packages/abc/5.json"));
    }

    @Test
    void launcherMetaKeepsPath() {
        assertEquals(BMCL + "/mc/game/version_manifest_v2.json",
                MirrorResolver.rewrite(BMCL,
                        "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"));
    }

    @Test
    void librariesGoUnderMavenPrefix() {
        assertEquals(BMCL + "/maven/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar",
                MirrorResolver.rewrite(BMCL,
                        "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar"));
    }

    @Test
    void resourcesGoUnderAssetsPrefix() {
        assertEquals(BMCL + "/assets/ab/abcdef0123456789",
                MirrorResolver.rewrite(BMCL,
                        "https://resources.download.minecraft.net/ab/abcdef0123456789"));
    }

    @Test
    void loaderMavensAllMapToMavenPrefix() {
        assertEquals(BMCL + "/maven/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-installer.jar",
                MirrorResolver.rewrite(BMCL,
                        "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.2.0/"
                                + "forge-1.20.1-47.2.0-installer.jar"));
        assertEquals(BMCL + "/maven/net/neoforged/neoforge/21.1.72/neoforge-21.1.72-installer.jar",
                MirrorResolver.rewrite(BMCL,
                        "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.72/"
                                + "neoforge-21.1.72-installer.jar"));
        assertEquals(BMCL + "/maven/net/fabricmc/fabric-loader/0.15.11/fabric-loader-0.15.11.jar",
                MirrorResolver.rewrite(BMCL,
                        "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.15.11/"
                                + "fabric-loader-0.15.11.jar"));
        assertEquals(BMCL + "/maven/org/quiltmc/quilt-loader/0.23.1/quilt-loader-0.23.1.jar",
                MirrorResolver.rewrite(BMCL,
                        "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/0.23.1/"
                                + "quilt-loader-0.23.1.jar"));
    }

    @Test
    void unknownDomainCannotBeTranslated() {
        assertNull(MirrorResolver.rewrite(BMCL, "https://example.com/some/file.jar"));
    }

    @Test
    void fallbackOrderFollowsPreference() {
        assertEquals(List.of(LauncherConfig.DownloadSource.OFFICIAL),
                new MirrorResolver(LauncherConfig.DownloadSource.OFFICIAL, null).order());
        assertEquals(List.of(LauncherConfig.DownloadSource.BMCLAPI,
                        LauncherConfig.DownloadSource.MCBBS,
                        LauncherConfig.DownloadSource.OFFICIAL),
                new MirrorResolver(LauncherConfig.DownloadSource.BMCLAPI, null).order());
        assertEquals(List.of(LauncherConfig.DownloadSource.MCBBS,
                        LauncherConfig.DownloadSource.BMCLAPI,
                        LauncherConfig.DownloadSource.OFFICIAL),
                new MirrorResolver(LauncherConfig.DownloadSource.MCBBS, null).order());
        assertEquals(List.of(LauncherConfig.DownloadSource.CUSTOM,
                        LauncherConfig.DownloadSource.BMCLAPI,
                        LauncherConfig.DownloadSource.MCBBS,
                        LauncherConfig.DownloadSource.OFFICIAL),
                new MirrorResolver(LauncherConfig.DownloadSource.CUSTOM, "https://mirror.example.com").order());
    }

    @Test
    void candidatesPutPreferredSourceFirst() {
        MirrorResolver resolver = new MirrorResolver(LauncherConfig.DownloadSource.BMCLAPI, null);
        List<String> candidates = resolver.candidates(
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        assertEquals(3, candidates.size());
        assertEquals(BMCL + "/mc/game/version_manifest_v2.json", candidates.get(0));
        assertEquals(MCBBS + "/mc/game/version_manifest_v2.json", candidates.get(1));
        assertEquals("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", candidates.get(2));
    }

    @Test
    void alreadyMirroredUrlIsNotMirroredTwice() {
        MirrorResolver resolver = new MirrorResolver(LauncherConfig.DownloadSource.BMCLAPI, null);
        List<String> candidates = resolver.candidates(BMCL + "/maven/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar");
        assertEquals(List.of(BMCL + "/maven/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar"), candidates);
    }

    @Test
    void customSourceGoesFirstAndReusesMavenPrefix() {
        MirrorResolver resolver = new MirrorResolver(LauncherConfig.DownloadSource.CUSTOM,
                "https://mirror.example.com/");
        List<String> candidates = resolver.candidates(
                "https://libraries.minecraft.net/org/ow2/asm/asm/9.5/asm-9.5.jar");
        assertEquals(4, candidates.size());
        assertEquals("https://mirror.example.com/maven/org/ow2/asm/asm/9.5/asm-9.5.jar", candidates.get(0));
    }

    @Test
    void describeShowsTheWholeChain() {
        MirrorResolver resolver = new MirrorResolver(LauncherConfig.DownloadSource.BMCLAPI, null);
        assertTrue(resolver.describe().contains("BMCLAPI"));
        assertTrue(resolver.describe().contains("官方"));
    }
}
