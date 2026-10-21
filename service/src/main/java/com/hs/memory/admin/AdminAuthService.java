package com.hs.memory.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 控制台的鉴权：单账号（用户名 + 口令）+ 服务端会话。
 *
 * <p>单用户自托管系统里不需要完整的账号体系，但「谁都能打开就改 embedding 配置」不行。
 * 所以只有一行凭证：口令哈希存库、用户名存库，会话令牌只存在服务端内存。
 *
 * <p>用户名不参与安全强度（它就是公开的标识），但两个都要对。校验时会先算一次口令哈希
 * 再比用户名，且用非短路的 {@code &} 合并结果——这样「账号错」和「口令错」耗时一致，
 * 从响应时间上分不出账号是否存在。
 *
 * <p>会话放内存而不是库，是有意的取舍：进程重启后重新登录，代价很小；换来的是
 * 「令牌不出进程」，没有一张表可以被人拿着 dump 直接冒用。
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final int MAX_FAILS = 5;
    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final int MAX_SESSIONS = 32;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 200;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,32}");

    private final AdminSettings settings;
    private final CredentialStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    private volatile String hash;
    private volatile String username;

    public AdminAuthService(AdminSettings settings, CredentialStore store) {
        this.settings = settings;
        this.store = store;
    }

    /** schema 建好之后调用：库里没有凭证且环境变量给了，就用环境变量初始化一次。 */
    public void bootstrap() {
        try {
            CredentialStore.Credential stored = store.load();
            String storedHash = stored == null ? null : stored.passwordHash();
            String storedUser = stored == null ? null : stored.username();

            if (storedHash == null && settings.passwordProvided()) {
                storedUser = settings.usernameOrDefault();
                storedHash = PasswordHasher.hash(settings.password());
                store.save(storedUser, storedHash);
                log.info("控制台账号已从环境变量初始化：{}（之后以库里为准，改账号走界面）", storedUser);
            }
            this.hash = storedHash;
            this.username = storedUser == null || storedUser.isBlank()
                    ? settings.usernameOrDefault() : storedUser;

            if (storedHash == null) {
                log.warn("控制台已禁用：未设置 MEMORY_ADMIN_PASSWORD 且库里没有凭证。"
                        + "设置后重启即可启用；记忆 API 不受影响");
            } else {
                log.info("控制台已启用：账号 {}，会话 {} 小时", this.username, settings.sessionHours());
            }
        } catch (Exception e) {
            log.warn("控制台凭证初始化失败（控制台暂不可用）: {}", e.getMessage());
        }
    }

    public boolean enabled() {
        return hash != null;
    }

    /** 当前账号名；控制台未启用时返回 null。给界面显示用。 */
    public String username() {
        return hash == null ? null : username;
    }

    public LoginResult login(String user, String password, String ip) {
        if (!enabled()) {
            return new LoginResult(LoginStatus.DISABLED, null, 0,
                    "控制台未启用：请在 .env 里设置 MEMORY_ADMIN_PASSWORD 后重启服务");
        }
        String key = ip == null ? "-" : ip;
        Deque<Long> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        long retryAfter;
        synchronized (attempts) {
            prune(attempts, now);
            if (attempts.size() >= MAX_FAILS) {
                retryAfter = Math.max(1, (WINDOW_MS - (now - attempts.peekFirst())) / 1000);
                return new LoginResult(LoginStatus.THROTTLED, null, retryAfter,
                        "尝试次数过多，" + retryAfter + " 秒后再试");
            }
            attempts.addLast(now);
        }

        // 顺序不能反、也不能短路：先做一次昂贵的哈希，再比账号，
        // 失败路径的耗时才对「账号是否存在」不敏感。
        boolean passwordOk = PasswordHasher.verify(password, hash);
        boolean usernameOk = sameUsername(user, username);
        if (!(passwordOk & usernameOk)) {
            // 失败也花掉一点时间：这不是防爆破（限流已经做了），是让脚本没法把
            // 「本机回环 + 快哈希」当成离线字典攻击的加速器。
            sleep(120);
            // 不区分「账号错」和「口令错」
            return new LoginResult(LoginStatus.BAD_CREDENTIALS, null, 0, "账号或口令不正确");
        }

        failures.remove(key);
        Session session = new Session(PasswordHasher.token(), PasswordHasher.token(),
                Instant.now().plusSeconds(settings.sessionHours() * 3600L), key);
        pruneSessions();
        sessions.put(session.token(), session);
        log.info("控制台登录成功 user={} ip={} 当前会话 {}", username, key, sessions.size());
        return new LoginResult(LoginStatus.OK, session, 0, null);
    }

    public Optional<Session> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /**
     * 改账号 / 改口令：校验原口令，写新凭证，作废全部会话（包括当前这个）。
     * 新账号或新口令留空表示「保持不变」；两者都留空视为误操作。
     */
    public void changeCredentials(String oldPassword, String newUsername, String newPassword) {
        if (!enabled()) {
            throw new IllegalStateException("控制台未启用");
        }
        if (!PasswordHasher.verify(oldPassword, hash)) {
            throw new IllegalArgumentException("原口令不正确");
        }

        boolean usernameChanged = newUsername != null && !newUsername.isBlank()
                && !newUsername.trim().equals(username);
        boolean passwordChanged = newPassword != null && !newPassword.isBlank();

        if (!usernameChanged && !passwordChanged) {
            throw new IllegalArgumentException("账号和口令都没有变化");
        }

        String nextUser = usernameChanged ? validateUsername(newUsername) : username;
        String nextHash = hash;
        if (passwordChanged) {
            if (newPassword.length() < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("新口令至少 " + MIN_PASSWORD_LENGTH + " 位");
            }
            if (newPassword.length() > MAX_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("新口令不能超过 " + MAX_PASSWORD_LENGTH + " 位");
            }
            nextHash = PasswordHasher.hash(newPassword);
        }

        store.save(nextUser, nextHash);
        this.username = nextUser;
        this.hash = nextHash;
        sessions.clear();
        log.info("控制台凭证已修改（账号 {}），所有会话已作废", nextUser);
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** 环境变量里配了口令但还没初始化（schema 未就绪时）——控制器用它给更准确的提示。 */
    public boolean passwordProvided() {
        return settings.passwordProvided();
    }

    private static String validateUsername(String candidate) {
        String trimmed = candidate == null ? "" : candidate.trim();
        if (!USERNAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("账号需 3–32 位，只能用字母、数字、点、下划线和短横线");
        }
        return trimmed;
    }

    /** 大小写不敏感：手输 `Admin` 不该因为大小写被判错。比较过程不提前返回。 */
    private static boolean sameUsername(String input, String expected) {
        String a = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        String b = expected == null ? "" : expected.trim().toLowerCase(Locale.ROOT);
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void pruneSessions() {
        if (sessions.size() < MAX_SESSIONS) {
            return;
        }
        sessions.values().stream()
                .min(Comparator.comparing(Session::expiresAt))
                .ifPresent(s -> sessions.remove(s.token()));
    }

    private static void prune(Deque<Long> attempts, long now) {
        while (!attempts.isEmpty() && now - attempts.peekFirst() > WINDOW_MS) {
            attempts.pollFirst();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public enum LoginStatus {
        OK, BAD_CREDENTIALS, THROTTLED, DISABLED
    }

    public record LoginResult(LoginStatus status, Session session, long retryAfterSec, String message) {
    }

    public record Session(String token, String csrf, Instant expiresAt, String ip) {
    }
}
