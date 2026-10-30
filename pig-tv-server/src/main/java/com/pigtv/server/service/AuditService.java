package com.pigtv.server.service;

import com.pigtv.server.db.Db;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuditService {

    private final Db db;

    public AuditService(Db db) {
        this.db = db;
    }

    public void log(long adminId, String adminName, String action, String target, Map<String, Object> detail, String ip) {
        String json = null;
        if (detail != null) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : detail.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                Object v = e.getValue();
                if (v == null) {
                    sb.append("null");
                } else if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append('"').append(String.valueOf(v).replace("\"", "\\\"")).append('"');
                }
            }
            sb.append('}');
            json = sb.toString();
        }
        db.insert("insert into admin_audit_log (admin_id, admin_name, action, target, detail, ip, created_at) values (?,?,?,?,?,?,?)",
                adminId, adminName, action, target, json, ip, LocalDateTime.now());
    }
}
