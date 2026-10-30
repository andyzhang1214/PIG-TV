package com.pigtv.server.service;

import com.pigtv.server.common.BizException;
import com.pigtv.server.db.Db;
import com.pigtv.server.security.JwtService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{4,20}$");

    private final Db db;
    private final JwtService jwt;
    private final RiskService risk;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(Db db, JwtService jwt, RiskService risk) {
        this.db = db;
        this.jwt = jwt;
        this.risk = risk;
    }

    @Transactional
    public Map<String, Object> register(String username, String password, String deviceId,
                                        String deviceName, String platform, Integer appVersion, String ip) {
        validateUsername(username);
        validatePassword(password);

        if (risk.inBlacklist(ip)) {
            throw BizException.forbidden("当前网络已被限制登录", null);
        }
        long exists = db.count("select count(*) from app_user where username = ?", username);
        if (exists > 0) {
            throw BizException.conflict("用户名已被占用");
        }

        LocalDateTime now = LocalDateTime.now();
        long userId = db.insert("""
                insert into app_user (username, password_hash, nickname, status, device_limit,
                                      register_ip, last_ip, last_login_at, created_at, updated_at)
                values (?,?,?,0,5,?,?,?,?,?)
                """, username, encoder.encode(password), username, ip, ip, now, now, now);

        return issueSession(userId, username, deviceId, deviceName, platform, appVersion, ip);
    }

    @Transactional
    public Map<String, Object> login(String username, String password, String deviceId,
                                     String deviceName, String platform, Integer appVersion, String ip) {
        if (risk.inBlacklist(ip)) {
            throw BizException.forbidden("当前网络已被限制登录", null);
        }
        Map<String, Object> u = findUser(username);
        if (u == null || !encoder.matches(password, (String) u.get("password_hash"))) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (u.get("deleted_at") != null) {
            throw BizException.unauthorized("用户名或密码错误");
        }

        LocalDateTime banUntil = (LocalDateTime) u.get("ban_until");
        int status = ((Number) u.get("status")).intValue();
        if (status == 1 && banUntil != null && banUntil.isAfter(LocalDateTime.now())) {
            Map<String, Object> data = new HashMap<>();
            data.put("banUntil", banUntil.toString());
            data.put("reason", u.get("ban_reason"));
            data.put("banType", u.get("ban_type"));
            throw BizException.forbidden(
                    "auto_ip".equals(u.get("ban_type")) ? "登录地点异常，账号已被临时封禁" : "账号已被封禁", data);
        }

        // 锁网段校验
        String locked = (String) u.get("locked_segments");
        if (locked != null && !locked.isBlank()) {
            String seg = risk.segmentOf(ip);
            boolean allowed = false;
            for (String s : locked.split(",")) {
                if (!s.isBlank() && risk.segmentMatches(seg, s.trim())) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw BizException.forbidden("当前网络不在该账号允许的范围内", null);
            }
        }

        long userId = ((Number) u.get("id")).longValue();
        db.update("update app_user set last_ip=?, last_login_at=?, status=0, ban_until=null, updated_at=? where id=?",
                ip, LocalDateTime.now(), LocalDateTime.now(), userId);

        Map<String, Object> out = issueSession(userId, username, deviceId, deviceName, platform, appVersion, ip);
        risk.check(userId, ip);
        return out;
    }

    private Map<String, Object> issueSession(long userId, String username, String deviceId,
                                             String deviceName, String platform, Integer appVersion, String ip) {
        String dev = (deviceId == null || deviceId.isBlank()) ? "unknown" : deviceId;
        LocalDateTime now = LocalDateTime.now();

        // 同设备重新登录：先撤销旧会话，保证「同一设备只占一个名额」
        db.update("update user_session set revoked = 1 where user_id = ? and device_id = ? and revoked = 0", userId, dev);

        int deviceLimit = 5;
        Map<String, Object> urow = db.one("select device_limit from app_user where id = ?",
                (rs, i) -> Map.of("device_limit", rs.getInt(1)), userId);
        if (urow != null) {
            deviceLimit = ((Number) urow.get("device_limit")).intValue();
        }

        List<String> alive = db.list(
                "select device_id from user_session where user_id = ? and revoked = 0 group by device_id order by min(last_seen_at) asc",
                (rs, i) -> rs.getString(1), userId);
        while (alive.size() >= deviceLimit && !alive.isEmpty()) {
            String oldest = alive.remove(0);
            db.update("update user_session set revoked = 1 where user_id = ? and device_id = ? and revoked = 0",
                    userId, oldest);
        }

        String jti = jwt.newJti();
        db.insert("""
                insert into user_session (user_id, jti, device_id, device_name, platform, app_version, ip, ip_seg, last_seen_at, created_at, revoked)
                values (?,?,?,?,?,?,?,?,?,?,0)
                """, userId, jti, dev, deviceName, platform, appVersion, ip, risk.segmentOf(ip), now, now);

        String token = jwt.issue("user", userId, username, jti);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("userId", userId);
        out.put("username", username);
        out.put("expireHours", 720);
        return out;
    }

    @Transactional
    public void changePassword(long userId, String oldPwd, String newPwd) {
        validatePassword(newPwd);
        Map<String, Object> u = db.one("select password_hash from app_user where id = ?",
                (rs, i) -> Map.of("password_hash", rs.getString(1)), userId);
        if (u == null || !encoder.matches(oldPwd, (String) u.get("password_hash"))) {
            throw BizException.badRequest("原密码不正确");
        }
        db.update("update app_user set password_hash=?, updated_at=? where id=?",
                encoder.encode(newPwd), LocalDateTime.now(), userId);
        db.update("update user_session set revoked = 1 where user_id = ?", userId);
    }

    public void logout(String jti) {
        db.update("update user_session set revoked = 1 where jti = ?", jti);
    }

    public Map<String, Object> profile(long userId) {
        Map<String, Object> u = db.one("""
                select id, username, nickname, status, ban_until, ban_reason, ban_type, last_ip, last_login_at, created_at
                from app_user where id = ?
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("username", rs.getString("username"));
            m.put("nickname", rs.getString("nickname"));
            m.put("status", rs.getInt("status"));
            m.put("banUntil", Db.dt(rs, "ban_until"));
            m.put("banReason", rs.getString("ban_reason"));
            m.put("banType", rs.getString("ban_type"));
            m.put("lastIp", rs.getString("last_ip"));
            m.put("lastLoginAt", Db.dt(rs, "last_login_at"));
            m.put("createdAt", Db.dt(rs, "created_at"));
            return m;
        }, userId);
        if (u == null) {
            throw BizException.unauthorized("账号不存在");
        }
        long devices = db.count("select count(distinct device_id) from user_session where user_id = ? and revoked = 0", userId);
        u.put("deviceCount", devices);
        return u;
    }

    // ---------------- TV 数字码登录 ----------------

    public Map<String, Object> createDeviceCode(String platform) {
        String code;
        int guard = 0;
        do {
            code = String.format("%06d", (int) (Math.random() * 1_000_000));
            guard++;
        } while (db.count("select count(*) from device_code where code = ? and bound = 0 and expire_at > ?",
                code, LocalDateTime.now()) > 0 && guard < 20);

        String deviceCode = java.util.UUID.randomUUID().toString();
        LocalDateTime expire = LocalDateTime.now().plusMinutes(5);
        db.insert("insert into device_code (device_code, code, user_id, bound, expire_at, created_at) values (?,?,null,0,?,?)",
                deviceCode, code, expire, LocalDateTime.now());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceCode", deviceCode);
        out.put("code", code);
        out.put("expireAt", expire.toString());
        out.put("platform", platform);
        return out;
    }

    @Transactional
    public Map<String, Object> bindDeviceCode(long userId, String username, String code, String ip) {
        Map<String, Object> row = db.one(
                "select device_code from device_code where code = ? and bound = 0 and expire_at > ?",
                (rs, i) -> Map.of("device_code", rs.getString(1)), code, LocalDateTime.now());
        if (row == null) {
            throw BizException.notFound("验证码无效或已过期");
        }
        db.update("update device_code set user_id = ?, bound = 1 where device_code = ?", userId, row.get("device_code"));

        Map<String, Object> out = issueSession(userId, username, "tv-" + row.get("device_code"), "电视端", "tv", null, ip);
        out.put("deviceCode", row.get("device_code"));
        return out;
    }

    public Map<String, Object> pollDeviceCode(String deviceCode, String ip) {
        Map<String, Object> row = db.one(
                "select user_id, bound, expire_at from device_code where device_code = ?",
                (rs, i) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("user_id", Db.longOrNull(rs, "user_id"));
                    m.put("bound", rs.getInt("bound"));
                    m.put("expire_at", Db.dt(rs, "expire_at"));
                    return m;
                }, deviceCode);
        Map<String, Object> out = new LinkedHashMap<>();
        if (row == null) {
            out.put("status", "expired");
            return out;
        }
        LocalDateTime exp = (LocalDateTime) row.get("expire_at");
        if (exp == null || exp.isBefore(LocalDateTime.now())) {
            out.put("status", "expired");
            return out;
        }
        if (((Number) row.get("bound")).intValue() != 1 || row.get("user_id") == null) {
            out.put("status", "waiting");
            return out;
        }
        long userId = ((Number) row.get("user_id")).longValue();
        Map<String, Object> u = db.one("select username from app_user where id = ?",
                (rs, i) -> Map.of("username", rs.getString(1)), userId);
        if (u == null) {
            out.put("status", "expired");
            return out;
        }
        out.putAll(issueSession(userId, (String) u.get("username"), "tv-" + deviceCode, "电视端", "tv", null, ip));
        out.put("status", "bound");
        return out;
    }

    // ---------------- helpers ----------------

    private Map<String, Object> findUser(String username) {
        return db.one("""
                select id, username, password_hash, status, ban_until, ban_reason, ban_type, deleted_at, locked_segments
                from app_user where username = ?
                """, (rs, i) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("username", rs.getString("username"));
            m.put("password_hash", rs.getString("password_hash"));
            m.put("status", rs.getInt("status"));
            m.put("ban_until", Db.dt(rs, "ban_until"));
            m.put("ban_reason", rs.getString("ban_reason"));
            m.put("ban_type", rs.getString("ban_type"));
            m.put("deleted_at", Db.dt(rs, "deleted_at"));
            m.put("locked_segments", rs.getString("locked_segments"));
            return m;
        }, username);
    }

    public static void validateUsername(String u) {
        if (u == null || !USERNAME.matcher(u).matches()) {
            throw BizException.badRequest("用户名需 4-20 位字母、数字或下划线");
        }
    }

    public static void validatePassword(String p) {
        if (p == null || p.length() < 8 || p.length() > 32) {
            throw BizException.badRequest("密码长度需 8-32 位");
        }
        boolean hasLetter = p.chars().anyMatch(Character::isLetter);
        boolean hasDigit = p.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw BizException.badRequest("密码需同时包含字母和数字");
        }
    }

    public static List<String> emptyIfNull(List<String> in) {
        return in == null ? new ArrayList<>() : in;
    }
}
