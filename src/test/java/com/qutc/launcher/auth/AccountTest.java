package com.qutc.launcher.auth;

import com.qutc.launcher.utils.HashUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 离线账号档案：UUID 规则、名字校验、过期判定。 */
class AccountTest {

    @Test
    void offlineUuidFollowsMinecraftRule() {
        Account steve = Account.offline("Steve");
        assertEquals(AccountType.OFFLINE, steve.type());
        assertEquals("Steve", steve.name());
        assertEquals("legacy", steve.userType());
        assertEquals(HashUtils.offlineUuid("Steve"), steve.uuid());

        UUID uuid = UUID.fromString(steve.uuid());
        assertEquals(3, uuid.version(), "离线 UUID 必须是 v3");
        assertEquals(2, uuid.variant(), "离线 UUID 必须是 IETF 变体");
        assertTrue(steve.uuid().equals(Account.offline("Steve").uuid()), "同名必须同 UUID");
        assertFalse(steve.uuid().equals(Account.offline("Alex").uuid()), "不同名必须不同 UUID");
    }

    @Test
    void offlineUuidMatchesIndependentMd5Computation() throws Exception {
        String name = "Notch";
        byte[] hash = java.security.MessageDigest.getInstance("MD5")
                .digest(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x30);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        assertEquals(Account.offline(name).uuid(), HashUtils.toDashedHex(hash));
    }

    @Test
    void invalidOfflineNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Account.offline(null));
        assertThrows(IllegalArgumentException.class, () -> Account.offline("   "));
        assertThrows(IllegalArgumentException.class, () -> Account.offline("a".repeat(17)));
        assertThrows(IllegalArgumentException.class, () -> Account.offline("中文名字"));
        assertThrows(IllegalArgumentException.class, () -> Account.offline("has space"));
    }

    @Test
    void namesAreTrimmedBeforeUuid() {
        assertEquals(Account.offline("Steve").uuid(), Account.offline("  Steve  ").uuid());
    }

    @Test
    void refreshDecisionUsesFiveMinuteSkew() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Account fresh = Account.microsoft("Steve", "uuid", "1", now.plusSeconds(3600), null, null);
        Account nearlyExpired = Account.microsoft("Steve", "uuid", "1", now.plusSeconds(120), null, null);
        Account expired = Account.microsoft("Steve", "uuid", "1", now.minusSeconds(1), null, null);
        Account noExpiry = Account.microsoft("Steve", "uuid", "1", null, null, null);
        Account offline = Account.offline("Steve");

        assertFalse(fresh.needsRefresh(now));
        assertTrue(nearlyExpired.needsRefresh(now), "5 分钟内到期视为需要续期");
        assertTrue(expired.needsRefresh(now));
        assertTrue(noExpiry.needsRefresh(now), "没有过期时间必须续期");
        assertFalse(offline.needsRefresh(now), "离线账号不参与续期");
    }

    @Test
    void storageKeySeparatesMicrosoftAndOffline() {
        Account ms = Account.microsoft("Steve", "ABC-123", "1", null, null, null);
        Account offline = Account.offline("Steve");
        assertEquals("msa:abc-123", ms.storageKey());
        assertEquals("offline:steve", offline.storageKey());
        assertFalse(ms.storageKey().equals(offline.storageKey()));
    }
}
