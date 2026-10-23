package com.qutc.launcher.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 账号文件：加密落盘、原子轮换、多账号隔离、损坏容忍。 */
class AccountStoreTest {

    @TempDir
    Path dir;

    private AccountStore newStore() {
        return new AccountStore(dir.resolve("accounts").resolve("accounts.dat"),
                new SecretBox(dir.resolve("accounts").resolve("secret.key"), true));
    }

    private static Credentials creds(String access, String refresh) {
        return new Credentials(access, refresh, "ms-access",
                Instant.now().plusSeconds(3600).toString());
    }

    @Test
    void offlineAccountRoundTripAndCurrent() throws IOException {
        AccountStore store = newStore();
        assertTrue(store.isEmpty());

        Account steve = Account.offline("Steve");
        store.save(steve, null);

        assertEquals(1, store.accounts().size());
        assertEquals(Optional.of(steve), store.current());
        assertEquals(Optional.of(steve), store.find("steve"));
        assertEquals(Optional.of(steve), store.find(steve.uuid()));
        assertEquals(Optional.of(steve), store.find("OFFLINE:STEVE"));
        assertTrue(store.find("nobody").isEmpty());
        assertTrue(store.credentials(steve).isEmpty(), "离线账号不应有凭据");
    }

    @Test
    void credentialsSurviveReload() throws IOException {
        AccountStore store = newStore();
        Account account = Account.microsoft("Steve", "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                "12345", Instant.now().plusSeconds(3600), "https://skin", "https://cape");
        store.save(account, creds("mc-token", "refresh-token"));

        AccountStore reloaded = newStore();
        Optional<Credentials> loaded = reloaded.credentials(account);
        assertTrue(loaded.isPresent());
        assertEquals("mc-token", loaded.get().accessToken());
        assertEquals("refresh-token", loaded.get().refreshToken());
        assertEquals("ms-access", loaded.get().msAccessToken());

        Account back = reloaded.find("Steve").orElseThrow();
        assertEquals("12345", back.xuid());
        assertEquals("https://skin", back.skinUrl());
        assertEquals("https://cape", back.capeUrl());
    }

    @Test
    void tokensNeverAppearInPlaintextOnDisk() throws IOException {
        AccountStore store = newStore();
        Account account = Account.microsoft("Steve", "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                "12345", Instant.now().plusSeconds(3600), null, null);
        store.save(account, creds("SUPER-SECRET-ACCESS", "SUPER-SECRET-REFRESH"));

        String text = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertFalse(text.contains("SUPER-SECRET-ACCESS"), "accessToken 绝不能明文落盘");
        assertFalse(text.contains("SUPER-SECRET-REFRESH"), "refreshToken 绝不能明文落盘");
        assertTrue(text.contains("secretsPayload"), "令牌必须以密文信封形式保存");
        assertTrue(text.contains("secretsScheme"), "必须记录加密方案（dpapi / aes）");
    }

    @Test
    void rotationReplacesOldRefreshTokenAtomically() throws IOException {
        AccountStore store = newStore();
        Account account = Account.microsoft("Steve", "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                "12345", Instant.now().plusSeconds(3600), null, null);
        store.save(account, creds("mc-1", "OLD-REFRESH-TOKEN"));

        store.save(account, creds("mc-2", "NEW-REFRESH-TOKEN"));

        AccountStore reloaded = newStore();
        assertEquals("NEW-REFRESH-TOKEN", reloaded.credentials(account).orElseThrow().refreshToken());
        String text = Files.readString(reloaded.file(), StandardCharsets.UTF_8);
        assertFalse(text.contains("OLD-REFRESH-TOKEN"), "旧 refresh_token 必须随覆盖一起消失");
        assertFalse(text.contains("mc-1"), "旧 accessToken 也必须被替换");
    }

    @Test
    void multipleAccountsAreIsolated() throws IOException {
        AccountStore store = newStore();
        Account a = Account.microsoft("Steve", "uuid-a", "1", Instant.now().plusSeconds(3600), null, null);
        Account b = Account.microsoft("Alex", "uuid-b", "2", Instant.now().plusSeconds(3600), null, null);
        store.save(a, creds("mc-a", "refresh-a"));
        store.save(b, creds("mc-b", "refresh-b"));

        assertEquals(2, store.accounts().size());
        assertEquals("refresh-a", store.credentials(a).orElseThrow().refreshToken());
        assertEquals("refresh-b", store.credentials(b).orElseThrow().refreshToken());

        // 只轮换 A，B 不受影响
        store.save(a, creds("mc-a2", "refresh-a2"));
        assertEquals("refresh-a2", store.credentials(a).orElseThrow().refreshToken());
        assertEquals("refresh-b", store.credentials(b).orElseThrow().refreshToken());
    }

    @Test
    void currentAccountMovesWhenDeleted() throws IOException {
        AccountStore store = newStore();
        Account a = Account.offline("Steve");
        Account b = Account.offline("Alex");
        store.save(a, null);
        store.save(b, null);
        assertEquals(Optional.of(a), store.current(), "首个账号保持当前");

        store.setCurrent(b);
        assertEquals(Optional.of(b), store.current());

        store.delete(b);
        assertEquals(Optional.of(a), store.current(), "删除当前账号后回落到剩余账号");
        assertEquals(1, store.accounts().size());

        store.clear();
        assertTrue(store.isEmpty());
        assertTrue(store.current().isEmpty());
    }

    @Test
    void corruptedFileDoesNotBreakLauncher() throws IOException {
        AccountStore store = newStore();
        store.save(Account.offline("Steve"), null);
        Files.writeString(store.file(), "{ this is not json", StandardCharsets.UTF_8);

        AccountStore reopened = newStore();
        assertTrue(reopened.accounts().isEmpty(), "损坏文件应被留档并重建，而不是抛异常");
    }
}
