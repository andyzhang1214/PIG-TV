package com.pigtv.server.service;

import com.pigtv.server.common.BizException;
import com.pigtv.server.db.Db;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SourceService {

    private final Db db;

    public SourceService(Db db) {
        this.db = db;
    }

    /** 客户端拉取：只给启用的，按权重倒序 */
    public List<Map<String, Object>> listForClient() {
        return db.list("""
                select id, name, url, weight, is_default, remark
                from source_config where enabled = 1
                order by weight desc, id asc
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("name", rs.getString("name"));
            m.put("url", rs.getString("url"));
            m.put("weight", rs.getInt("weight"));
            m.put("isDefault", rs.getInt("is_default") == 1);
            m.put("remark", rs.getString("remark"));
            return m;
        });
    }

    public List<Map<String, Object>> listAll() {
        return db.list("select * from source_config order by weight desc, id asc", SourceService::mapRow);
    }

    public long create(Map<String, Object> body) {
        String name = require(body.get("name"), "名称");
        String url = require(body.get("url"), "地址");
        checkUrl(url);
        long isDefault = truthy(body.get("isDefault")) ? 1 : 0;
        if (isDefault == 1) {
            db.update("update source_config set is_default = 0");
        }
        LocalDateTime now = LocalDateTime.now();
        return db.insert("""
                insert into source_config (name, url, weight, is_default, enabled, remark, created_at, updated_at)
                values (?,?,?,?,?,?,?,?)
                """, name, url, intOf(body.get("weight"), 100), isDefault,
                body.containsKey("enabled") ? (truthy(body.get("enabled")) ? 1 : 0) : 1,
                str(body.get("remark")), now, now);
    }

    public void update(long id, Map<String, Object> body) {
        Map<String, Object> row = db.one("select id from source_config where id = ?",
                (rs, i) -> Map.of("id", rs.getLong(1)), id);
        if (row == null) {
            throw BizException.notFound("片源接口不存在");
        }
        if (body.containsKey("url")) {
            checkUrl(require(body.get("url"), "地址"));
        }
        if (truthy(body.get("isDefault"))) {
            db.update("update source_config set is_default = 0");
        }

        StringBuilder set = new StringBuilder("update source_config set updated_at = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(LocalDateTime.now());
        appendStr(set, args, "name", body.get("name"));
        appendStr(set, args, "url", body.get("url"));
        appendStr(set, args, "remark", body.get("remark"));
        if (body.containsKey("weight")) {
            set.append(", weight = ?");
            args.add(intOf(body.get("weight"), 100));
        }
        if (body.containsKey("enabled")) {
            set.append(", enabled = ?");
            args.add(truthy(body.get("enabled")) ? 1 : 0);
        }
        if (body.containsKey("isDefault")) {
            set.append(", is_default = ?");
            args.add(truthy(body.get("isDefault")) ? 1 : 0);
        }
        set.append(" where id = ?");
        args.add(id);
        db.update(set.toString(), args.toArray());
    }

    public void delete(long id) {
        db.update("delete from source_config where id = ?", id);
    }

    /** 后台点「测试」：代请求一次，判断能否拿到内容 */
    public Map<String, Object> test(long id) {
        Map<String, Object> row = db.one("select url from source_config where id = ?",
                (rs, i) -> Map.of("url", rs.getString(1)), id);
        if (row == null) {
            throw BizException.notFound("片源接口不存在");
        }
        String url = (String) row.get("url");
        Map<String, Object> out = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(6))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "okhttp/3.15.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body() == null ? "" : resp.body().trim();
            boolean looksJson = body.startsWith("{");
            out.put("ok", resp.statusCode() == 200 && looksJson);
            out.put("status", resp.statusCode());
            out.put("ms", System.currentTimeMillis() - start);
            out.put("looksJson", looksJson);
            out.put("preview", body.length() > 120 ? body.substring(0, 120) : body);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("ms", System.currentTimeMillis() - start);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        db.update("update source_config set last_check_at = ?, last_check_ok = ? where id = ?",
                LocalDateTime.now(), Boolean.TRUE.equals(out.get("ok")) ? 1 : 0, id);
        return out;
    }

    private static void appendStr(StringBuilder set, List<Object> args, String col, Object v) {
        if (v != null) {
            set.append(", ").append(col).append(" = ?");
            args.add(String.valueOf(v));
        }
    }

    private static void checkUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw BizException.badRequest("地址必须以 http:// 或 https:// 开头");
        }
        if (url.length() > 512) {
            throw BizException.badRequest("地址过长");
        }
    }

    private static Map<String, Object> mapRow(ResultSet rs, int i) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("name", rs.getString("name"));
        m.put("url", rs.getString("url"));
        m.put("weight", rs.getInt("weight"));
        m.put("isDefault", rs.getInt("is_default") == 1);
        m.put("enabled", rs.getInt("enabled") == 1);
        m.put("remark", rs.getString("remark"));
        m.put("lastCheckAt", Db.dt(rs, "last_check_at"));
        m.put("lastCheckOk", Db.intOrNull(rs, "last_check_ok"));
        m.put("createdAt", Db.dt(rs, "created_at"));
        return m;
    }

    private static String require(Object v, String label) {
        if (v == null || String.valueOf(v).isBlank()) {
            throw BizException.badRequest(label + " 不能为空");
        }
        return String.valueOf(v).trim();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intOf(Object v, int def) {
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return "1".equals(String.valueOf(v)) || "true".equalsIgnoreCase(String.valueOf(v));
    }
}
