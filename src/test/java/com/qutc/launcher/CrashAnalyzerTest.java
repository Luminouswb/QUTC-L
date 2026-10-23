package com.qutc.launcher;

import com.qutc.launcher.core.diagnose.CrashAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashAnalyzerTest {

    @Test
    void memoryCrashIsRecognized() {
        List<CrashAnalyzer.Diagnosis> result = CrashAnalyzer.analyze("""
                java.lang.OutOfMemoryError: Java heap space
                    at net.minecraft.client.Minecraft.<init>(Minecraft.java:1)
                """);
        assertFalse(result.isEmpty());
        assertEquals("out-of-memory", result.get(0).id());
        assertTrue(result.get(0).suggestion().contains("内存"));
    }

    @Test
    void classVersionCrashPointsToJavaVersion() {
        List<CrashAnalyzer.Diagnosis> result = CrashAnalyzer.analyze("""
                java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a
                more recent version of the Java Runtime (class file version 61.0), this version of the Java
                Runtime only recognizes class file versions up to 52.0
                """);
        assertEquals("class-version", result.get(0).id());
        assertTrue(result.get(0).suggestion().contains("61"));
    }

    @Test
    void missingDependencyAndDuplicateModsBothReported() {
        String log = """
                net.fabricmc.loader.impl.FormattedException: Mod resolution failed
                * Missing or unsupported mandatory dependencies:
                    Mod 'sodium' requires 'fabric-api' >=0.83.0
                DuplicateModsFoundException: Found duplicate mods
                """;
        List<CrashAnalyzer.Diagnosis> result = CrashAnalyzer.analyze(log);
        assertTrue(result.stream().anyMatch(diagnosis -> diagnosis.id().equals("missing-dependency")));
        assertTrue(result.stream().anyMatch(diagnosis -> diagnosis.id().equals("duplicate-mods")));
    }

    @Test
    void glfwErrorSuggestsDriverUpdate() {
        List<CrashAnalyzer.Diagnosis> result = CrashAnalyzer.analyze(
                "[LWJGL] GLFW error 65542: WGL: The driver does not appear to support OpenGL");
        assertEquals("opengl", result.get(0).id());
        assertTrue(result.get(0).suggestion().contains("显卡驱动"));
    }

    @Test
    void jvmCrashIsFatal() {
        List<CrashAnalyzer.Diagnosis> result = CrashAnalyzer.analyze("""
                # A fatal error has been detected by the Java Runtime Environment:
                #  EXCEPTION_ACCESS_VIOLATION (0xc0000005)
                # An error report file with more information is saved as: hs_err_pid1234.log
                """);
        assertEquals("jvm-crash", result.get(0).id());
        assertEquals(CrashAnalyzer.Severity.FATAL, result.get(0).severity());
    }

    @Test
    void accessTokenIsRedactedInExcerpt() {
        List<CrashAnalyzer.Diagnosis> result = CrashAnalyzer.analyze(
                "java.lang.OutOfMemoryError: Java heap space accessToken=\"abcdef123456\"");
        assertFalse(result.isEmpty());
        assertFalse(result.get(0).detail().contains("abcdef123456"));
    }

    @Test
    void cleanLogProduceNoDiagnosis() {
        assertTrue(CrashAnalyzer.analyze("""
                Setting user: Steve
                Backend library: LWJGL version 3.3.1 build 7
                Created: 512x512 textures-atlas
                """).isEmpty());
    }

    @Test
    void crashDetection() {
        assertTrue(CrashAnalyzer.looksLikeCrash("---- Minecraft Crash Report ----"));
        assertTrue(CrashAnalyzer.looksLikeCrash("java.lang.Exception in thread \"main\""));
        assertFalse(CrashAnalyzer.looksLikeCrash("Setting user: Steve"));
    }
}
