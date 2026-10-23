package com.qutc.launcher.auth;

import com.qutc.launcher.config.AppPaths;
import com.qutc.launcher.utils.FsUtils;
import com.qutc.launcher.utils.JsonUtils;
import com.qutc.launcher.utils.Log;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 账号仓库：{@code accounts/accounts.dat} 的唯一读写入口。
 *
 * <p>文件里<b>只有身份信息 + 密文</b>；令牌一律经 {@link SecretBox} 封装后落盘。
 * 写入是「临时文件 + 原子替换」，并且整段操作加锁——这是总纲要求的
 * 「RefreshToken 轮换必须原子化」的落地点：要么新令牌完整可见，要么旧文件原样，
 * 绝不会出现「读到了新 refresh_token 但没写进去」的半截状态。
 *
 * <p>多设备场景：本文件是<b>本设备</b>的令牌快照，换设备各自维护，互不覆盖。
 */
public final class AccountStore {

    public static final int FORMAT_VERSION = 1;

    private final Path file;
    private final SecretBox box;
    private final ReentrantLock lock = new ReentrantLock();

    public AccountStore(Path file, SecretBox box) {
        this.file = file;
        this.box = box;
    }

    public static AccountStore defaults() {
        return new AccountStore(AppPaths.accountStore(), SecretBox.defaults());
    }

    public Path file() {
        return file;
    }

    public List<Account> accounts() {
        lock.lock();
        try {
            List<Account> result = new ArrayList<>();
            for (Entry entry : read().accounts) {
                Account account = toAccount(entry);
                if (account != null) {
                    result.add(account);
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        return accounts().isEmpty();
    }

    /** 按 storageKey / UUID / 名字（大小写不敏感）查找。 */
    public Optional<Account> find(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        String needle = idOrName.trim().toLowerCase(Locale.ROOT);
        for (Account account : accounts()) {
            if (account.storageKey().toLowerCase(Locale.ROOT).equals(needle)
                    || (account.uuid() != null && account.uuid().toLowerCase(Locale.ROOT).equals(needle))
                    || account.name().toLowerCase(Locale.ROOT).equals(needle)) {
                return Optional.of(account);
            }
        }
        return Optional.empty();
    }

    public Optional<Account> current() {
        lock.lock();
        try {
            Store store = read();
            if (store.currentAccount == null) {
                return store.accounts.isEmpty()
                        ? Optional.empty()
                        : Optional.ofNullable(toAccount(store.accounts.get(0)));
            }
            for (Entry entry : store.accounts) {
                if (store.currentAccount.equals(entry.storageKey())) {
                    return Optional.ofNullable(toAccount(entry));
                }
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public void setCurrent(Account account) throws IOException {
        lock.lock();
        try {
            Store store = read();
            store.currentAccount = account.storageKey();
            write(store);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 新增或更新账号；凭据整体替换（旧 refresh_token 随旧记录一起作废）。
     */
    public void save(Account account, Credentials credentials) throws IOException {
        lock.lock();
        try {
            Store store = read();
            Entry entry = new Entry();
            entry.type = account.type().name();
            entry.name = account.name();
            entry.uuid = account.uuid();
            entry.xuid = account.xuid();
            entry.userType = account.userType();
            entry.expiresAt = account.tokenExpiresAt() == null ? null : account.tokenExpiresAt().toString();
            entry.skinUrl = account.skinUrl();
            entry.capeUrl = account.capeUrl();
            entry.authServer = account.authServer();
            entry.authServerName = account.authServerName();
            if (credentials != null) {
                String plain = JsonUtils.mapper().writeValueAsString(credentials);
                SecretBox.Sealed sealed = box.seal(plain);
                if (sealed == null) {
                    throw new IOException("凭据加密失败，拒绝落盘");
                }
                entry.secretsScheme = sealed.scheme();
                entry.secretsPayload = sealed.payload();
            }
            String key = account.storageKey();
            store.accounts.removeIf(existing -> key.equals(existing.storageKey()));
            store.accounts.add(entry);
            if (store.currentAccount == null || store.currentAccount.isBlank()) {
                store.currentAccount = key;
            }
            write(store);
        } finally {
            lock.unlock();
        }
    }

    /** 读取可用凭据；解不开（换机/换用户）返回空，调用方必须引导重新登录。 */
    public Optional<Credentials> credentials(Account account) {
        if (account == null || !account.isMicrosoft()) {
            return Optional.empty();
        }
        lock.lock();
        try {
            for (Entry entry : read().accounts) {
                if (account.storageKey().equals(entry.storageKey())) {
                    if (entry.secretsPayload == null) {
                        return Optional.empty();
                    }
                    String plain = box.open(new SecretBox.Sealed(entry.secretsScheme, entry.secretsPayload));
                    if (plain == null) {
                        Log.warn("Account", "账号 " + account.name() + " 的凭据无法解密，需要重新登录");
                        return Optional.empty();
                    }
                    return Optional.of(JsonUtils.mapper().readValue(plain, Credentials.class));
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            Log.warn("Account", "读取账号凭据失败: " + e.getMessage());
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public void delete(Account account) throws IOException {
        lock.lock();
        try {
            Store store = read();
            String key = account.storageKey();
            store.accounts.removeIf(entry -> key.equals(entry.storageKey()));
            if (key.equals(store.currentAccount)) {
                store.currentAccount = store.accounts.isEmpty()
                        ? null
                        : store.accounts.get(store.accounts.size() - 1).storageKey();
            }
            write(store);
        } finally {
            lock.unlock();
        }
    }

    public void clear() throws IOException {
        lock.lock();
        try {
            Store store = new Store();
            write(store);
        } finally {
            lock.unlock();
        }
    }

    public String describeAll() {
        List<Account> list = accounts();
        if (list.isEmpty()) {
            return "（无账号）";
        }
        Account current = current().orElse(null);
        StringBuilder sb = new StringBuilder();
        for (Account account : list) {
            boolean isCurrent = current != null && current.storageKey().equals(account.storageKey());
            sb.append(isCurrent ? " * " : "   ").append(account.display());
            if (account.isMicrosoft() && account.tokenExpiresAt() != null) {
                sb.append("  令牌有效至 ").append(account.tokenExpiresAt());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---- 内部：文件模型 ----

    public static final class Store {
        public int version = FORMAT_VERSION;
        public String currentAccount;
        public List<Entry> accounts = new ArrayList<>();
    }

    public static final class Entry {
        public String type;
        public String name;
        public String uuid;
        public String xuid;
        public String userType;
        public String expiresAt;
        public String skinUrl;
        public String capeUrl;
        public String authServer;
        public String authServerName;
        public String secretsScheme;
        public String secretsPayload;

        public String storageKey() {
            if ("MICROSOFT".equalsIgnoreCase(type)) {
                return "msa:" + (uuid == null ? "" : uuid.toLowerCase(Locale.ROOT));
            }
            if ("YGGDRASIL".equalsIgnoreCase(type)) {
                return "ygg:" + (uuid == null ? "" : uuid.toLowerCase(Locale.ROOT))
                        + "@" + (authServer == null ? "" : authServer.toLowerCase(Locale.ROOT));
            }
            return "offline:" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        }
    }

    private Store read() {
        Store store = JsonUtils.read(file, Store.class, null);
        if (store == null) {
            store = new Store();
        }
        if (store.accounts == null) {
            store.accounts = new ArrayList<>();
        }
        return store;
    }

    private void write(Store store) throws IOException {
        store.version = FORMAT_VERSION;
        FsUtils.ensureDir(file.getParent());
        // 跨进程锁：多开启动器时避免两个进程同时改写同一份账号文件
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            JsonUtils.write(file, store);
        } catch (IOException e) {
            // 锁文件不可用（只读介质等）时退化为直接原子写，不阻塞用户
            Log.warn("Account", "账号文件加锁失败，退化为原子写: " + e.getMessage());
            JsonUtils.write(file, store);
        }
        try {
            Files.setAttribute(file, "dos:hidden", true);
        } catch (Exception ignored) {
            // 非 Windows：忽略
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.contains("accessToken") && !text.contains("\"secretsPayload\"")) {
                Log.warn("Account", "账号文件疑似包含明文令牌，请检查加密流程");
            }
        } catch (IOException ignored) {
            // 自检失败不影响主流程
        }
    }

    private static Account toAccount(Entry entry) {
        if (entry == null || entry.name == null) {
            return null;
        }
        AccountType type = "MICROSOFT".equalsIgnoreCase(entry.type)
                ? AccountType.MICROSOFT
                : "YGGDRASIL".equalsIgnoreCase(entry.type) ? AccountType.YGGDRASIL : AccountType.OFFLINE;
        Instant expires = null;
        if (entry.expiresAt != null && !entry.expiresAt.isBlank()) {
            try {
                expires = Instant.parse(entry.expiresAt);
            } catch (RuntimeException ignored) {
                // 时间格式坏掉只影响展示，按无过期处理
            }
        }
        return new Account(type, entry.name, entry.uuid, entry.xuid, entry.userType, expires,
                entry.skinUrl, entry.capeUrl, entry.authServer, entry.authServerName);
    }
}
