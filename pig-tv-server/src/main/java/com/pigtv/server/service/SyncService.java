package com.pigtv.server.service;

import com.pigtv.server.db.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云同步：客户端推本地数据上来，再拉增量回去。
 * 合并规则统一用 updatedAt 做 LWW；收藏/历史用 (sourceKey, vodId) 做业务主键。
 */
@Service
public class SyncService {

    private final Db db;

    public SyncService(Db db) {
        this.db = db;
    }

    @Transactional
    public Map<String, Object> push(long userId,
                                    List<Map<String, Object>> collect,
                                    List<Map<String, Object>> history,
                                    Map<String, Object> setting) {
        int c = 0;
        int h = 0;
        int s = 0;

        if (collect != null) {
            for (Map<String, Object> item : collect) {
                if (upsertCollect(userId, item)) {
                    c++;
                }
            }
        }
        if (history != null) {
            for (Map<String, Object> item : history) {
                if (upsertHistory(userId, item)) {
                    h++;
                }
            }
        }
        if (setting != null) {
            for (Map.Entry<String, Object> e : setting.entrySet()) {
                if (SETTING_AT.equals(e.getKey())) {
                    continue; // 元数据，不是真实设置项
                }
                Object v = e.getValue();
                Object at = null;
                if (v instanceof Map<?, ?> vm && vm.containsKey("value")) {
                    // 客户端按 {key: {value, updatedAt}} 送上来时，时间戳要跟着走
                    at = vm.get("updatedAt");
                    v = vm.get("value");
                }
                if (upsertSetting(userId, e.getKey(), v == null ? null : String.valueOf(v), at)) {
                    s++;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collect", c);
        out.put("history", h);
        out.put("setting", s);
        out.put("serverTime", LocalDateTime.now().toString());
        return out;
    }

    /** 设置项的 updatedAt 单独放在这个键下（Map 形态没法逐项带时间戳） */
    private static final String SETTING_AT = "_updatedAt";

    /**
     * 把 setting 统一成 Map&lt;key, value&gt; 形态。
     *
     * <p>客户端有两种自然写法，两种都得认：
     * <ul>
     *   <li>{"theme":"dark"} —— 简单键值表</li>
     *   <li>[{"key":"theme","value":"dark","updatedAt":1700000000000}] —— 带时间戳的列表</li>
     * </ul>
     * 第二种在列表里带了 updatedAt，转成 {"theme":{"value":"dark","updatedAt":...}}，
     * 这样设置项也能做 LWW，不会被「谁最后推谁赢」把新值覆盖掉。
     */
    public static Map<String, Object> normalizeSetting(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        if (raw instanceof List<?> list) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> item)) {
                    continue;
                }
                Object k = item.get("key") != null ? item.get("key") : item.get("skey");
                if (k == null) {
                    continue;
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("value", item.get("value") != null ? item.get("value") : item.get("svalue"));
                v.put("updatedAt", item.get("updatedAt"));
                out.put(String.valueOf(k), v);
            }
            return out;
        }
        return null;
    }

    private boolean upsertCollect(long userId, Map<String, Object> item) {
        String sourceKey = str(item, "sourceKey");
        String vodId = str(item, "vodId");
        if (sourceKey == null || vodId == null) {
            return false;
        }
        LocalDateTime at = parseTime(item.get("updatedAt"));
        int deleted = truthy(item.get("deleted")) ? 1 : 0;

        Map<String, Object> exist = db.one(
                "select id, updated_at from user_collect where user_id=? and source_key=? and vod_id=?",
                (rs, i) -> Map.of("id", rs.getLong("id"), "updated_at", Db.dt(rs, "updated_at")),
                userId, sourceKey, vodId);

        if (exist == null) {
            db.insert("insert into user_collect (user_id, source_key, vod_id, name, pic, updated_at, deleted) values (?,?,?,?,?,?,?)",
                    userId, sourceKey, vodId, firstStr(item, "name", "vodName"), firstStr(item, "pic", "vodPic"), at, deleted);
            return true;
        }
        LocalDateTime old = (LocalDateTime) exist.get("updated_at");
        if (old == null || at.isAfter(old)) {
            db.update("update user_collect set name=?, pic=?, updated_at=?, deleted=? where id=?",
                    firstStr(item, "name", "vodName"), firstStr(item, "pic", "vodPic"), at, deleted, exist.get("id"));
            return true;
        }
        return false;
    }

    private boolean upsertHistory(long userId, Map<String, Object> item) {
        String sourceKey = str(item, "sourceKey");
        String vodId = str(item, "vodId");
        if (sourceKey == null || vodId == null) {
            return false;
        }
        LocalDateTime at = parseTime(item.get("updatedAt"));
        int deleted = truthy(item.get("deleted")) ? 1 : 0;

        Map<String, Object> exist = db.one(
                "select id, updated_at from user_history where user_id=? and source_key=? and vod_id=?",
                (rs, i) -> Map.of("id", rs.getLong("id"), "updated_at", Db.dt(rs, "updated_at")),
                userId, sourceKey, vodId);

        Integer playIndex = item.get("playIndex") == null ? null : Integer.valueOf(String.valueOf(item.get("playIndex")));
        Long position = item.get("positionMs") == null ? null : Long.valueOf(String.valueOf(item.get("positionMs")));
        Long duration = item.get("durationMs") == null ? null : Long.valueOf(String.valueOf(item.get("durationMs")));

        if (exist == null) {
            db.insert("""
                    insert into user_history (user_id, source_key, vod_id, data_json, play_flag, play_index,
                                              position_ms, duration_ms, updated_at, deleted)
                    values (?,?,?,?,?,?,?,?,?,?)
                    """, userId, sourceKey, vodId, str(item, "dataJson"), str(item, "playFlag"),
                    playIndex, position, duration, at, deleted);
            return true;
        }
        LocalDateTime old = (LocalDateTime) exist.get("updated_at");
        if (old == null || at.isAfter(old)) {
            db.update("""
                    update user_history set data_json=?, play_flag=?, play_index=?, position_ms=?, duration_ms=?,
                                            updated_at=?, deleted=?
                    where id=?
                    """, str(item, "dataJson"), str(item, "playFlag"), playIndex, position, duration,
                    at, deleted, exist.get("id"));
            return true;
        }
        return false;
    }

    private boolean upsertSetting(long userId, String key, String value, Object clientAt) {
        if (key == null || key.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        // 客户端给了时间戳就按 LWW 比一下，没给就以服务端时间为准（首次合并本地优先）
        LocalDateTime at = clientAt == null ? now : parseTime(clientAt);

        if (clientAt == null) {
            int updated = db.update("update user_setting set svalue=?, updated_at=? where user_id=? and skey=?",
                    value, now, userId, key);
            if (updated > 0) {
                return true;
            }
            // user_setting 是复合主键，不能用自增回填的 insert（GeneratedKeyHolder 取不到单列 key）
            db.update("insert into user_setting (user_id, skey, svalue, updated_at) values (?,?,?,?)",
                    userId, key, value, now);
            return true;
        }

        LocalDateTime old = db.one("select updated_at from user_setting where user_id=? and skey=?",
                (rs, i) -> Db.dt(rs, "updated_at"), userId, key);
        if (old == null) {
            db.update("insert into user_setting (user_id, skey, svalue, updated_at) values (?,?,?,?)",
                    userId, key, value, at);
            return true;
        }
        if (at.isAfter(old)) {
            db.update("update user_setting set svalue=?, updated_at=? where user_id=? and skey=?",
                    value, at, userId, key);
            return true;
        }
        return false;
    }

    public Map<String, Object> pull(long userId, LocalDateTime since, String types, int limit) {
        boolean all = types == null || types.isBlank();
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDateTime from = since == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : since;

        if (all || types.contains("collect")) {
            out.put("collect", db.maps("""
                    select source_key, vod_id, name, pic, updated_at, deleted
                    from user_collect where user_id = ? and updated_at > ?
                    order by updated_at desc limit ?
                    """, userId, from, limit).stream().map(this::lowerKeys).toList());
        }
        if (all || types.contains("history")) {
            out.put("history", db.maps("""
                    select source_key, vod_id, data_json, play_flag, play_index, position_ms, duration_ms, updated_at, deleted
                    from user_history where user_id = ? and updated_at > ?
                    order by updated_at desc limit ?
                    """, userId, from, limit).stream().map(this::lowerKeys).toList());
        }
        if (all || types.contains("setting")) {
            List<Map<String, Object>> rows = db.maps(
                    "select skey, svalue, updated_at from user_setting where user_id = ? and updated_at > ?",
                    userId, from);
            Map<String, Object> settings = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                settings.put(String.valueOf(r.get("skey")), r.get("svalue"));
            }
            out.put("setting", settings);
        }
        out.put("serverTime", LocalDateTime.now().toString());
        return out;
    }

    /**
     * H2 / MySQL 返回的大写键名与时间类型要做归一：
     * <ul>
     *   <li>键名转小写驼峰，两端字段名一致</li>
     *   <li>时间字段统一成本地 ISO（不带时区偏移）。JdbcTemplate 拿到的是
     *       java.sql.Timestamp，Jackson 会按 UTC 序列化成 {@code 2026-10-30T06:29:47.318+00:00}，
     *       和 serverTime 的写法不一致，客户端把它原样当 since 回传就会差 8 小时、增量拉空。</li>
     * </ul>
     */
    private Map<String, Object> lowerKeys(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            m.put(toCamel(e.getKey()), normTime(e.getValue()));
        }
        return m;
    }

    private Object normTime(Object v) {
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(ISO_LOCAL);
        }
        if (v instanceof LocalDateTime dt) {
            return dt.format(ISO_LOCAL);
        }
        return v;
    }

    private static final java.time.format.DateTimeFormatter ISO_LOCAL =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private String toCamel(String s) {
        String lower = s.toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : lower.toCharArray()) {
            if (c == '_') {
                up = true;
            } else if (up) {
                sb.append(Character.toUpperCase(c));
                up = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    /** 取第一个非空字段：兼容客户端用 name 还是 vodName 两种命名 */
    private static String firstStr(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v);
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /** 兼容毫秒时间戳、ISO 字符串、yyyy-MM-dd HH:mm:ss 三种写法 */
    public static LocalDateTime parseTime(Object v) {
        if (v == null) {
            return LocalDateTime.now();
        }
        if (v instanceof Number n) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneId.systemDefault());
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(s)), ZoneId.systemDefault());
        } catch (NumberFormatException ignored) {
            // fallthrough
        }
        try {
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e2) {
                return LocalDateTime.now();
            }
        }
    }

    public static List<String> splitTypes(String types) {
        if (types == null || types.isBlank()) {
            return new ArrayList<>();
        }
        return List.of(types.split(","));
    }
}
