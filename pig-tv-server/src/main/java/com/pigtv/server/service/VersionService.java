package com.pigtv.server.service;

import com.pigtv.server.common.BizException;
import com.pigtv.server.common.PageData;
import com.pigtv.server.db.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VersionService {

    private final Db db;
    private final StorageService storage;

    public VersionService(Db db, StorageService storage) {
        this.db = db;
        this.storage = storage;
    }

    // ---------------- 客户端 ----------------

    public Map<String, Object> check(String platform, String abi, int clientVersionCode) {
        Map<String, Object> latest = latestFor(platform, abi);
        Map<String, Object> out = new LinkedHashMap<>();
        if (latest == null) {
            out.put("hasUpdate", false);
            return out;
        }
        int code = ((Number) latest.get("versionCode")).intValue();
        int minSupported = ((Number) latest.get("minSupportedVersionCode")).intValue();
        boolean force = ((Number) latest.get("forceUpdate")).intValue() == 1 || clientVersionCode < minSupported;

        out.put("hasUpdate", code > clientVersionCode);
        out.put("versionCode", code);
        out.put("versionName", latest.get("versionName"));
        out.put("fileUrl", latest.get("fileUrl"));
        out.put("fileSize", latest.get("fileSize"));
        out.put("sha256", latest.get("sha256"));
        out.put("changelog", latest.get("changelog"));
        out.put("force", force);
        return out;
    }

    /** 先找精确 ABI，再退 universal；两者都取已发布的最新 */
    private Map<String, Object> latestFor(String platform, String abi) {
        Map<String, Object> exact = null;
        if (abi != null && !abi.isBlank()) {
            exact = queryLatest(platform, abi);
        }
        Map<String, Object> universal = queryLatest(platform, "universal");
        if (exact == null) {
            return universal;
        }
        if (universal == null) {
            return exact;
        }
        int a = ((Number) exact.get("versionCode")).intValue();
        int b = ((Number) universal.get("versionCode")).intValue();
        return a >= b ? exact : universal;
    }

    private Map<String, Object> queryLatest(String platform, String abi) {
        return db.one("""
                select * from app_version
                where status = 'published'
                  and (platform = ? or platform = 'all')
                  and abi = ?
                order by version_code desc
                limit 1
                """, this::mapRow, platform, abi);
    }

    public void incDownload(long id) {
        db.update("update app_version set download_count = download_count + 1 where id = ?", id);
    }

    // ---------------- 管理端 ----------------

    public PageData<Map<String, Object>> page(int page, int size, String platform, String status) {
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (platform != null && !platform.isBlank()) {
            where.append(" and platform = ?");
            args.add(platform);
        }
        if (status != null && !status.isBlank()) {
            where.append(" and status = ?");
            args.add(status);
        }
        long total = db.count("select count(*) from app_version" + where, args.toArray());
        args.add(size);
        args.add((long) (page - 1) * size);
        List<Map<String, Object>> items = db.list(
                "select * from app_version" + where + " order by version_code desc, id desc limit ? offset ?",
                this::mapRow, args.toArray());
        return new PageData<>(items, total, page, size);
    }

    @Transactional
    public Map<String, Object> create(MultipartFile file, Map<String, String> form, long adminId) throws IOException {
        int versionCode = parseInt(form.get("versionCode"), "版本号(versionCode)");
        String versionName = require(form.get("versionName"), "版本名称");
        String platform = form.getOrDefault("platform", "all");
        String abi = form.getOrDefault("abi", "universal");
        String packageName = form.getOrDefault("packageName", "com.github.tvbox.osc.tk");

        if (!List.of("phone", "tv", "all").contains(platform)) {
            throw BizException.badRequest("platform 只能是 phone / tv / all");
        }
        if (!List.of("arm64", "armeabi", "universal").contains(abi)) {
            throw BizException.badRequest("abi 只能是 arm64 / armeabi / universal");
        }
        long dup = db.count("select count(*) from app_version where version_code=? and platform=? and abi=?",
                versionCode, platform, abi);
        if (dup > 0) {
            throw BizException.conflict("同一平台/ABI 下该版本号已存在");
        }

        Map<String, Object> saved = storage.save(file);
        LocalDateTime now = LocalDateTime.now();

        long id = db.insert("""
                insert into app_version (version_code, version_name, platform, abi, package_name, file_url, file_path, file_size,
                                         sha256, changelog, force_update, min_supported_version_code, status,
                                         download_count, published_at, created_by, created_at, updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)
                """, versionCode, versionName, platform, abi, packageName,
                saved.get("url"), saved.get("relativePath"), saved.get("size"), saved.get("sha256"),
                form.getOrDefault("changelog", ""),
                "1".equals(form.get("forceUpdate")) ? 1 : 0,
                form.get("minSupportedVersionCode") == null ? 0 : Integer.parseInt(form.get("minSupportedVersionCode")),
                form.getOrDefault("status", "draft"),
                "published".equals(form.getOrDefault("status", "draft")) ? now : null,
                adminId, now, now);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("fileUrl", saved.get("url"));
        out.put("sha256", saved.get("sha256"));
        out.put("size", saved.get("size"));
        return out;
    }

    public void update(long id, Map<String, Object> body) {
        Map<String, Object> row = db.one("select id from app_version where id = ?",
                (rs, i) -> Map.of("id", rs.getLong(1)), id);
        if (row == null) {
            throw BizException.notFound("版本不存在");
        }
        StringBuilder set = new StringBuilder("update app_version set updated_at = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(LocalDateTime.now());

        if (body.containsKey("changelog")) {
            set.append(", changelog = ?");
            args.add(body.get("changelog"));
        }
        if (body.containsKey("forceUpdate")) {
            set.append(", force_update = ?");
            args.add(truthy(body.get("forceUpdate")) ? 1 : 0);
        }
        if (body.containsKey("minSupportedVersionCode")) {
            set.append(", min_supported_version_code = ?");
            args.add(Integer.parseInt(String.valueOf(body.get("minSupportedVersionCode"))));
        }
        if (body.containsKey("status")) {
            String st = String.valueOf(body.get("status"));
            if (!List.of("draft", "published", "offline").contains(st)) {
                throw BizException.badRequest("状态不合法");
            }
            set.append(", status = ?");
            args.add(st);
            if ("published".equals(st)) {
                set.append(", published_at = ?");
                args.add(LocalDateTime.now());
            }
        }
        set.append(" where id = ?");
        args.add(id);
        db.update(set.toString(), args.toArray());
    }

    public void delete(long id) {
        Map<String, Object> row = db.one("select file_url from app_version where id = ?",
                (rs, i) -> Map.of("file_url", rs.getString(1)), id);
        if (row == null) {
            throw BizException.notFound("版本不存在");
        }
        db.update("update app_version set status='offline', updated_at=? where id=?", LocalDateTime.now(), id);
    }

    private Map<String, Object> mapRow(ResultSet rs, int i) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("versionCode", rs.getInt("version_code"));
        m.put("versionName", rs.getString("version_name"));
        m.put("platform", rs.getString("platform"));
        m.put("abi", rs.getString("abi"));
        m.put("packageName", rs.getString("package_name"));
        // 有相对路径就现拼（换域名立即生效），没有则退回库里存的绝对地址（老数据）
        String path = rs.getString("file_path");
        String resolved = storage.urlOf(path);
        m.put("fileUrl", resolved != null ? resolved : rs.getString("file_url"));
        m.put("fileSize", rs.getLong("file_size"));
        m.put("sha256", rs.getString("sha256"));
        m.put("changelog", rs.getString("changelog"));
        m.put("forceUpdate", rs.getInt("force_update"));
        m.put("minSupportedVersionCode", rs.getInt("min_supported_version_code"));
        m.put("status", rs.getString("status"));
        m.put("downloadCount", rs.getLong("download_count"));
        m.put("publishedAt", Db.dt(rs, "published_at"));
        m.put("createdAt", Db.dt(rs, "created_at"));
        return m;
    }

    private static int parseInt(String v, String label) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            throw BizException.badRequest(label + " 必须是数字");
        }
    }

    private static String require(String v, String label) {
        if (v == null || v.isBlank()) {
            throw BizException.badRequest(label + " 不能为空");
        }
        return v.trim();
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
