package com.pigtv.server.service;

import com.pigtv.server.config.AppProps;
import com.pigtv.server.db.Db;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatsService {

    private final Db db;
    private final AppProps props;

    public StatsService(Db db, AppProps props) {
        this.db = db;
        this.props = props;
    }

    public Map<String, Object> overview() {
        int window = props.getRisk().getOnlineWindowMinutes();
        LocalDateTime onlineSince = LocalDateTime.now().minusMinutes(window);
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalUsers", db.count("select count(*) from app_user where deleted_at is null"));
        out.put("bannedUsers", db.count("select count(*) from app_user where status = 1 and deleted_at is null"));
        out.put("todayNew", db.count("select count(*) from app_user where created_at >= ? and deleted_at is null", todayStart));
        out.put("weekNew", db.count("select count(*) from app_user where created_at >= ? and deleted_at is null",
                LocalDateTime.now().minusDays(7)));
        out.put("onlineUsers", db.count(
                "select count(distinct user_id) from user_session where revoked = 0 and last_seen_at > ?", onlineSince));
        out.put("onlinePhones", db.count(
                "select count(distinct user_id) from user_session where revoked = 0 and last_seen_at > ? and platform = 'phone'", onlineSince));
        out.put("onlineTvs", db.count(
                "select count(distinct user_id) from user_session where revoked = 0 and last_seen_at > ? and platform = 'tv'", onlineSince));
        out.put("sourceCount", db.count("select count(*) from source_config where enabled = 1"));
        out.put("publishedVersions", db.count("select count(*) from app_version where status = 'published'"));

        // 近 7 天注册趋势
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            long n = db.count("select count(*) from app_user where created_at >= ? and created_at < ?",
                    d.atStartOfDay(), d.plusDays(1).atStartOfDay());
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", d.toString().substring(5));
            point.put("count", n);
            trend.add(point);
        }
        out.put("trend", trend);

        // 版本分布（以在线会话为准）
        out.put("versionDist", db.list("""
                select app_version as ver, count(distinct user_id) as cnt
                from user_session where revoked = 0 and app_version is not null
                group by app_version order by app_version desc limit 8
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", rs.getInt("ver"));
            m.put("count", rs.getLong("cnt"));
            return m;
        }));

        // 平台分布
        out.put("platformDist", db.list("""
                select platform as p, count(distinct user_id) as cnt
                from user_session where revoked = 0 and platform is not null
                group by platform
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("platform", rs.getString("p"));
            m.put("count", rs.getLong("cnt"));
            return m;
        }));

        out.put("recentBans", db.list("""
                select l.id, u.username, l.reason, l.segments, l.ban_until, l.created_at
                from login_ban_log l join app_user u on u.id = l.user_id
                order by l.id desc limit 5
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("username", rs.getString("username"));
            m.put("reason", rs.getString("reason"));
            m.put("segments", rs.getString("segments"));
            m.put("banUntil", Db.dt(rs, "ban_until"));
            m.put("createdAt", Db.dt(rs, "created_at"));
            return m;
        }));

        return out;
    }
}
