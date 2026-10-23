package com.qutc.launcher.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKCE 与 state：RFC 7636 的形状约束，以及 challenge 的独立复算。 */
class PkceTest {

    @Test
    void verifierMatchesRfcCharsetAndLength() {
        String verifier = Pkce.newVerifier();
        assertTrue(verifier.length() >= 43 && verifier.length() <= 128,
                "verifier 长度必须 43–128，实际 " + verifier.length());
        assertTrue(verifier.matches("[A-Za-z0-9\\-._~]+"), "verifier 只能含 RFC 7636 允许的字符: " + verifier);
    }

    @Test
    void challengeIsUnpaddedBase64UrlOfSha256() throws Exception {
        String verifier = "abcdefghijklmnopqrstuvwxyz0123456789-._~ABCDEFGHIJKLMNOP";
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String challenge = Pkce.challengeOf(verifier);
        assertEquals(expected, challenge);
        assertEquals(43, challenge.length(), "S256 摘要固定 43 个 base64url 字符");
        assertFalse(challenge.contains("="), "challenge 不能带 base64 填充");
    }

    @Test
    void challengeIsDeterministicAndVerifierSpecific() {
        String a = Pkce.newVerifier();
        String b = Pkce.newVerifier();
        assertEquals(Pkce.challengeOf(a), Pkce.challengeOf(a));
        assertFalse(Pkce.challengeOf(a).equals(Pkce.challengeOf(b)));
        assertTrue(Pkce.verify(a, Pkce.challengeOf(a)));
    }

    @Test
    void verifyRejectsWrongVerifierOrChallenge() {
        String verifier = Pkce.newVerifier();
        assertFalse(Pkce.verify(verifier, Pkce.challengeOf(Pkce.newVerifier())));
        assertFalse(Pkce.verify(verifier, null));
        assertFalse(Pkce.verify(null, Pkce.challengeOf(verifier)));
    }

    @Test
    void stateIs32BytesHexAndUnguessable() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String state = Pkce.newState();
            assertEquals(64, state.length(), "32 字节十六进制应为 64 字符");
            assertTrue(state.matches("[0-9a-f]{64}"));
            seen.add(state);
        }
        assertEquals(200, seen.size(), "state 不允许重复");
    }
}
