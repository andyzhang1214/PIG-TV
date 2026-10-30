package com.pigtv.server.service;

import com.pigtv.server.common.BizException;
import com.pigtv.server.common.PageData;
import com.pigtv.server.db.Db;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserAdminService {

    private final Db db;
    private final AuditService audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserAdminService(Db db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    public PageData<Map<String, Object>> page(String keyword, String state, int page, int size, int onlineWindowMinutes) {
        StringBuilder where = new StringBuilder(" where u.deleted_at is null");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and u.username like ?");
            args.add("%" + keyword.trim() + "%");
        }
        if ("banned".equals(state)) {
            where.append(" and u.status = 1");
        } else if ("online".equals(state)) {
            where.append(" and exists (select 1 from user_session s where s.user_id = u.id and s.revoked = 0 and s.last_seen_at > ?)");
            args.add(LocalDateTime.now().minusMinutes(onlineWindowMinutes));
        } else if ("normal".equals(state)) {
            where.append(" and u.status = 0");
        }

        long total = db.count("select count(*) from app_user u" + where, args.toArray());

        String sql = """
                select u.*,
                       (select count(distinct s.device_id) from user_session s
                          where s.user_id = u.id and s.revoked = 0) as device_count,
                       (select max(s.last_seen_at) from user_session s
                          where s.user_id = u.id) as last_seen,
                       (select max(s.last_seen_at) from user_session s
                          where s.user_id = u.id and s.revoked = 0) as active_seen,
                       (select s.app_version from user_session s
                          where s.user_id = u.id order by s.last_seen_at desc limit 1) as app_version,
                       (select s.platform from user_session s
                          where s.user_id = u.id order by s.last_seen_at desc limit 1) as platform
                from app_user u
                """ + where + " order by u.id desc limit ? offset ?";

        List<Object> q = new ArrayList<>(args);
        q.add(size);
        q.add((long) (page - 1) * size);

        LocalDateTime onlineSince = LocalDateTime.now().minusMinutes(onlineWindowMinutes);
        List<Map<String, Object>> items = db.list(sql, (rs, i) -> {
            Map<String, Object> m = mapUser(rs);
            LocalDateTime seen = Db.dt(rs, "last_seen");
            LocalDateTime activeSeen = Db.dt(rs, "active_seen");
            // 最后登录时间取「全部会话」的最大值：封号会把会话全撤销，
            // 只认未撤销会话的话，一封号列表里「最后登录/IP/版本」全变空，管理员没法判断要不要解封。
            m.put("lastSeenAt", seen);
            // 但「在线」必须还要求有活着（未撤销）的会话，否则刚被封的账号会显示成在线
            m.put("online", activeSeen != null && activeSeen.isAfter(onlineSince));
            m.put("deviceCount", rs.getInt("device_count"));
            m.put("appVersion", Db.intOrNull(rs, "app_version"));
            m.put("platform", rs.getString("platform"));
            return m;
        }, q.toArray());

        return new PageData<>(items, total, page, size);
    }

    public Map<String, Object> detail(long id, int onlineWindowMinutes) {
        Map<String, Object> user = db.one("select * from app_user where id = ? and deleted_at is null",
                (rs, i) -> mapUser(rs), id);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        LocalDateTime onlineSince = LocalDateTime.now().minusMinutes(onlineWindowMinutes);
        user.put("segments", db.list("""
                select device_id, device_name, platform, app_version, ip, ip_seg, last_seen_at, revoked
                from user_session where user_id = ? order by last_seen_at desc limit 20
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", rs.getString("device_id"));
            m.put("deviceName", rs.getString("device_name"));
            m.put("platform", rs.getString("platform"));
            m.put("appVersion", Db.intOrNull(rs, "app_version"));
            m.put("ip", rs.getString("ip"));
            m.put("ipSeg", rs.getString("ip_seg"));
            LocalDateTime seen = Db.dt(rs, "last_seen_at");
            boolean revoked = rs.getInt("revoked") == 1;
            m.put("lastSeenAt", seen);
            // 已撤销的会话即使刚活跃过也不能算在线，否则封号/改密后仍显示在线
            m.put("online", !revoked && seen != null && seen.isAfter(onlineSince));
            m.put("revoked", revoked);
            return m;
        }, id));

        user.put("banLogs", db.list("""
                select id, reason, segments, ban_until, unbanned_by, created_at
                from login_ban_log where user_id = ? order by id desc limit 10
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("reason", rs.getString("reason"));
            m.put("segments", rs.getString("segments"));
            m.put("banUntil", Db.dt(rs, "ban_until"));
            m.put("unbannedBy", Db.longOrNull(rs, "unbanned_by"));
            m.put("createdAt", Db.dt(rs, "created_at"));
            return m;
        }, id));
        return user;
    }

    @Transactional
    public void resetPassword(long id, String newPwd, long adminId, String adminName, String ip) {
        AuthService.validatePassword(newPwd);
        Map<String, Object> u = db.one("select username from app_user where id = ? and deleted_at is null",
                (rs, i) -> Map.of("username", rs.getString(1)), id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }
        db.update("update app_user set password_hash=?, updated_at=? where id=?",
                encoder.encode(newPwd), LocalDateTime.now(), id);
        db.update("update user_session set revoked = 1 where user_id = ?", id);
        audit.log(adminId, adminName, "reset_pwd", String.valueOf(u.get("username")),
                Map.of("userId", id), ip);
    }

    @Transactional
    public void ban(long id, Integer hours, String reason, long adminId, String adminName, String ip) {
        Map<String, Object> u = db.one("select username from app_user where id = ? and deleted_at is null",
                (rs, i) -> Map.of("username", rs.getString(1)), id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }
        LocalDateTime until = hours == null || hours <= 0
                ? LocalDateTime.of(9999, 12, 31, 23, 59, 59)
                : LocalDateTime.now().plusHours(hours);

        db.update("""
                update app_user set status=1, ban_until=?, ban_reason=?, ban_type='manual', updated_at=? where id=?
                """, until, reason, LocalDateTime.now(), id);
        db.update("update user_session set revoked = 1 where user_id = ?", id);

        Map<String, Object> detail = new HashMap<>();
        detail.put("userId", id);
        detail.put("hours", hours);
        detail.put("until", until.toString());
        detail.put("reason", reason);
        audit.log(adminId, adminName, "ban", String.valueOf(u.get("username")), detail, ip);
    }

    @Transactional
    public void unban(long id, long adminId, String adminName, String ip) {
        Map<String, Object> u = db.one("select username from app_user where id = ? and deleted_at is null",
                (rs, i) -> Map.of("username", rs.getString(1)), id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }
        db.update("update app_user set status=0, ban_until=null, ban_reason=null, updated_at=? where id=?",
                LocalDateTime.now(), id);
        // 记录人工解封，触发 24 小时冷静期（避免解封后立刻再次自动封禁）
        db.update("update login_ban_log set unbanned_by = ? where user_id = ? and unbanned_by is null",
                adminId, id);
        audit.log(adminId, adminName, "unban", String.valueOf(u.get("username")), Map.of("userId", id), ip);
    }

    @Transactional
    public void lockSegments(long id, String segments, long adminId, String adminName, String ip) {
        Map<String, Object> u = db.one("select username from app_user where id = ? and deleted_at is null",
                (rs, i) -> Map.of("username", rs.getString(1)), id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }
        String v = (segments == null || segments.isBlank()) ? null : segments.trim();
        db.update("update app_user set locked_segments=?, updated_at=? where id=?", v, LocalDateTime.now(), id);
        audit.log(adminId, adminName, "lock_segment", String.valueOf(u.get("username")),
                Map.of("userId", id, "segments", v == null ? "" : v), ip);
    }

    @Transactional
    public void remove(long id, long adminId, String adminName, String ip) {
        Map<String, Object> u = db.one("select username from app_user where id = ? and deleted_at is null",
                (rs, i) -> Map.of("username", rs.getString(1)), id);
        if (u == null) {
            throw BizException.notFound("用户不存在");
        }
        db.update("update app_user set deleted_at=?, updated_at=? where id=?", LocalDateTime.now(), LocalDateTime.now(), id);
        db.update("update user_session set revoked = 1 where user_id = ?", id);
        audit.log(adminId, adminName, "delete_user", String.valueOf(u.get("username")), Map.of("userId", id), ip);
    }

    private static Map<String, Object> mapUser(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("username", rs.getString("username"));
        m.put("nickname", rs.getString("nickname"));
        m.put("status", rs.getInt("status"));
        m.put("banUntil", Db.dt(rs, "ban_until"));
        m.put("banReason", rs.getString("ban_reason"));
        m.put("banType", rs.getString("ban_type"));
        m.put("lockedSegments", rs.getString("locked_segments"));
        m.put("deviceLimit", rs.getInt("device_limit"));
        m.put("registerIp", rs.getString("register_ip"));
        m.put("lastIp", rs.getString("last_ip"));
        m.put("lastLoginAt", Db.dt(rs, "last_login_at"));
        m.put("createdAt", Db.dt(rs, "created_at"));
        return m;
    }
}
