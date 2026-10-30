package com.pigtv.server.service;

import com.pigtv.server.config.AppProps;
import com.pigtv.server.db.Db;
import com.pigtv.server.util.Web;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网段风控。
 *
 * <p>核心规避误封的规则：判定前对每个 device_id 只取「最新一条会话」的网段。
 * 一台设备 60 分钟内在基站间漂移会不断换 IP，若把历史网段全算上，
 * 坐一趟地铁就能把自己封了。取最新后，一台设备永远只贡献 1 个网段，
 * 超过阈值必然意味着「多台设备在多个网络」。
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final Db db;
    private final AppProps props;
    private final ObjectMapper om;

    public RiskService(Db db, AppProps props, ObjectMapper om) {
        this.db = db;
        this.props = props;
        this.om = om;
    }

    // ---------------- 网段计算 ----------------

    /** IPv4 → /24，IPv6 → /64；非法输入返回 "unknown" */
    public String segmentOf(String ip) {
        if (ip == null || ip.isBlank() || !looksLikeIp(ip)) {
            return "unknown";
        }
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length == 4) {
                return (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".0/24";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                int g = ((b[i * 2] & 0xff) << 8) | (b[i * 2 + 1] & 0xff);
                sb.append(Integer.toHexString(g));
                if (i < 3) {
                    sb.append(':');
                }
            }
            return sb + "::/64";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private boolean looksLikeIp(String s) {
        if (s.indexOf(':') > 0) {
            return s.matches("[0-9a-fA-F:%.]+");
        }
        return s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** 判断规范化网段是否落在某个 cidr 内（同时支持单 IP 与 CIDR 写法） */
    public boolean segmentMatches(String segment, String cidr) {
        if (segment == null || cidr == null) {
            return false;
        }
        String segIp = segment.contains("/") ? segment.substring(0, segment.indexOf('/')) : segment;
        String cidrIp = cidr.contains("/") ? cidr.substring(0, cidr.indexOf('/')) : cidr;

        int prefix = 32;
        if (cidr.contains("/")) {
            try {
                prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
            } catch (NumberFormatException ignored) {
                prefix = 32;
            }
        }
        // IPv4 用位比较，IPv6 退化为前 4 组字符串比较
        if (segIp.contains(".") && cidrIp.contains(".")) {
            long a = ipv4ToLong(segIp);
            long b = ipv4ToLong(cidrIp);
            if (a < 0 || b < 0) {
                return false;
            }
            long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            return (a & mask) == (b & mask);
        }
        String a = segIp.contains(":") ? segIp.substring(0, Math.min(segIp.length(), 19)) : segIp;
        String b = cidrIp.contains(":") ? cidrIp.substring(0, Math.min(cidrIp.length(), 19)) : cidrIp;
        return a.equalsIgnoreCase(b);
    }

    private long ipv4ToLong(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) {
            return -1;
        }
        long v = 0;
        for (String s : p) {
            try {
                v = (v << 8) | (Integer.parseInt(s) & 0xff);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return v;
    }

    // ---------------- 规则读取 ----------------

    public Map<String, Object> rule() {
        Map<String, Object> m = db.one("select * from ip_rule where id = 1", (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("enabled", rs.getInt("enabled") == 1);
            r.put("maxSegments", rs.getInt("max_segments"));
            r.put("banHours", rs.getInt("ban_hours"));
            r.put("sameDeviceGrace", rs.getInt("same_device_grace"));
            r.put("updatedAt", Db.dt(rs, "updated_at"));
            return r;
        });
        if (m == null) {
            m = new LinkedHashMap<>();
            m.put("enabled", true);
            m.put("maxSegments", props.getRisk().getMaxSegments());
            m.put("banHours", props.getRisk().getBanHours());
            m.put("sameDeviceGrace", props.getRisk().getSameDeviceGraceMinutes());
        }
        return m;
    }

    public void saveRule(Boolean enabled, Integer maxSegments, Integer banHours, Integer sameDeviceGrace) {
        Map<String, Object> cur = rule();
        Object e = enabled == null ? (((Boolean) cur.get("enabled")) ? 1 : 0) : (enabled ? 1 : 0);
        Object ms = maxSegments == null ? cur.get("maxSegments") : maxSegments;
        Object bh = banHours == null ? cur.get("banHours") : banHours;
        Object gd = sameDeviceGrace == null ? cur.get("sameDeviceGrace") : sameDeviceGrace;
        db.update("update ip_rule set enabled=?, max_segments=?, ban_hours=?, same_device_grace=?, updated_at=? where id=1",
                e, ms, bh, gd, LocalDateTime.now());
    }

    public boolean inWhitelist(String ip) {
        String seg = segmentOf(ip);
        List<String> allows = db.list("select cidr from ip_list where list_type='allow'", (rs, i) -> rs.getString(1));
        for (String cidr : allows) {
            if (segmentMatches(seg, cidr) || (ip != null && ip.equals(cidr))) {
                return true;
            }
        }
        return false;
    }

    public boolean inBlacklist(String ip) {
        if (ip == null) {
            return false;
        }
        String seg = segmentOf(ip);
        List<String> denies = db.list("select cidr from ip_list where list_type='deny'", (rs, i) -> rs.getString(1));
        for (String cidr : denies) {
            if (segmentMatches(seg, cidr) || ip.equals(cidr)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 核心判定 ----------------

    /** 会话心跳时调用：记录 IP/网段，然后判定是否超限 */
    public void touch(long userId, String jti, String ip) {
        String seg = segmentOf(ip);
        db.update("update user_session set ip = ?, ip_seg = ?, last_seen_at = ? where jti = ?",
                ip, seg, LocalDateTime.now(), jti);
        try {
            check(userId, ip);
        } catch (Exception e) {
            log.warn("risk check failed for user {}", userId, e);
        }
    }

    public void check(long userId, String currentIp) {
        Map<String, Object> rule = rule();
        if (!Boolean.TRUE.equals(rule.get("enabled"))) {
            return;
        }
        if (inWhitelist(currentIp)) {
            return;
        }
        int maxSegments = ((Number) rule.get("maxSegments")).intValue();
        int banHours = ((Number) rule.get("banHours")).intValue();

        // 刚被人工解封的账号给 24 小时冷静期，避免「解封→立刻再封」死循环
        long cooldown = props.getRisk().getUnbanCooldownHours();
        long recentUnban = db.count(
                "select count(*) from login_ban_log where user_id = ? and unbanned_by is not null and created_at > ?",
                userId, LocalDateTime.now().minusHours(cooldown));
        if (recentUnban > 0) {
            return;
        }

        LocalDateTime window = LocalDateTime.now().minusMinutes(props.getRisk().getOnlineWindowMinutes());

        // 每设备取最新一条会话（同一 device 换 IP 只算最新网段）
        List<Map<String, Object>> rows = db.maps("""
                select s.device_id, s.device_name, s.ip, s.ip_seg, s.last_seen_at
                from user_session s
                join (
                    select device_id, max(last_seen_at) as mx
                    from user_session
                    where user_id = ? and revoked = 0 and last_seen_at > ?
                    group by device_id
                ) t on s.device_id = t.device_id and s.last_seen_at = t.mx
                where s.user_id = ? and s.revoked = 0 and s.last_seen_at > ?
                """, userId, window, userId, window);

        Set<String> segments = new LinkedHashSet<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object seg = r.get("IP_SEG") != null ? r.get("IP_SEG") : r.get("ip_seg");
            if (seg != null) {
                segments.add(String.valueOf(seg));
            }
            evidence.add(r);
        }

        if (segments.size() <= maxSegments) {
            return;
        }

        // 超限：自动封禁
        LocalDateTime until = LocalDateTime.now().plusHours(banHours);
        String reason = "登录地点异常（" + segments.size() + " 个不同网络）";
        String evidenceJson;
        try {
            evidenceJson = om.writeValueAsString(Map.of(
                    "segments", segments,
                    "sessions", evidence
            ));
        } catch (Exception e) {
            evidenceJson = "{}";
        }

        db.update("update app_user set status=1, ban_until=?, ban_reason=?, ban_type='auto_ip', ban_evidence=?, updated_at=? where id=?",
                until, reason, evidenceJson, LocalDateTime.now(), userId);
        db.update("update user_session set revoked = 1 where user_id = ?", userId);
        db.update("insert into login_ban_log (user_id, reason, segments, devices, ban_until, auto_unban, created_at) values (?,?,?,?,?,?,?)",
                userId, "auto_ip_segments", String.join(",", segments), evidenceJson, until, 1, LocalDateTime.now());

        log.warn("auto banned user {} until {}, segments={}", userId, until, segments);
    }

    /** 后台：某账号最近登录网段与设备 */
    public List<Map<String, Object>> recentSegments(long userId, int limit) {
        return db.maps("""
                select device_id, device_name, platform, ip, ip_seg, last_seen_at, revoked
                from user_session
                where user_id = ?
                order by last_seen_at desc
                limit ?
                """, userId, limit);
    }
}
