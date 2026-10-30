package com.pigtv.server.controller.admin;

import com.pigtv.server.common.BizException;
import com.pigtv.server.common.PageData;
import com.pigtv.server.common.R;
import com.pigtv.server.db.Db;
import com.pigtv.server.security.Ctx;
import com.pigtv.server.service.AuditService;
import com.pigtv.server.service.RiskService;
import com.pigtv.server.service.StatsService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminOpsController {

    private final StatsService stats;
    private final RiskService risk;
    private final Db db;
    private final AuditService audit;

    public AdminOpsController(StatsService stats, RiskService risk, Db db, AuditService audit) {
        this.stats = stats;
        this.risk = risk;
        this.db = db;
        this.audit = audit;
    }

    @GetMapping("/stats/overview")
    public R overview() {
        return R.ok(stats.overview());
    }

    // ---------------- 网段风控 ----------------

    @GetMapping("/ip-rule")
    public R getRule() {
        return R.ok(risk.rule());
    }

    @PutMapping("/ip-rule")
    public R saveRule(@RequestBody Map<String, Object> body) {
        Ctx.Who who = Ctx.get();
        Boolean enabled = body.get("enabled") == null ? null : Boolean.valueOf(String.valueOf(body.get("enabled")));
        Integer maxSegments = body.get("maxSegments") == null ? null : Integer.valueOf(String.valueOf(body.get("maxSegments")));
        Integer banHours = body.get("banHours") == null ? null : Integer.valueOf(String.valueOf(body.get("banHours")));
        Integer grace = body.get("sameDeviceGrace") == null ? null : Integer.valueOf(String.valueOf(body.get("sameDeviceGrace")));

        if (maxSegments != null && (maxSegments < 1 || maxSegments > 20)) {
            throw BizException.badRequest("网段上限需在 1-20 之间");
        }
        if (banHours != null && (banHours < 1 || banHours > 8760)) {
            throw BizException.badRequest("封禁时长需在 1-8760 小时之间");
        }
        risk.saveRule(enabled, maxSegments, banHours, grace);
        audit.log(who.id(), who.name(), "ip_rule_edit", "ip_rule", body, who.ip());
        return R.ok(risk.rule());
    }

    @GetMapping("/ip-list")
    public R ipList(@RequestParam(required = false) String type) {
        String sql = "select id, cidr, list_type, remark, created_at from ip_list"
                + (type == null || type.isBlank() ? "" : " where list_type = ?")
                + " order by id desc";
        Object[] args = (type == null || type.isBlank()) ? new Object[]{} : new Object[]{type};
        return R.ok(db.list(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("cidr", rs.getString("cidr"));
            m.put("listType", rs.getString("list_type"));
            m.put("remark", rs.getString("remark"));
            m.put("createdAt", Db.dt(rs, "created_at"));
            return m;
        }, args));
    }

    @PostMapping("/ip-list")
    public R addIp(@RequestBody Map<String, Object> body) {
        Ctx.Who who = Ctx.get();
        String cidr = body.get("cidr") == null ? "" : String.valueOf(body.get("cidr")).trim();
        String type = body.getOrDefault("listType", "allow").toString();
        if (cidr.isEmpty()) {
            throw BizException.badRequest("IP 或网段不能为空");
        }
        if (!"allow".equals(type) && !"deny".equals(type)) {
            throw BizException.badRequest("类型只能是 allow 或 deny");
        }
        long id = db.insert("insert into ip_list (cidr, list_type, remark, created_at) values (?,?,?,?)",
                cidr, type, body.get("remark"), LocalDateTime.now());
        audit.log(who.id(), who.name(), "ip_list_add", cidr, Map.of("type", type), who.ip());
        return R.ok(Map.of("id", id));
    }

    @DeleteMapping("/ip-list/{id}")
    public R removeIp(@PathVariable long id) {
        Ctx.Who who = Ctx.get();
        db.update("delete from ip_list where id = ?", id);
        audit.log(who.id(), who.name(), "ip_list_delete", String.valueOf(id), null, who.ip());
        return R.ok();
    }

    // ---------------- 审计日志 ----------------

    @GetMapping("/audit-logs")
    public R auditLogs(@RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "20") int size,
                       @RequestParam(required = false) String action,
                       @RequestParam(required = false) String keyword) {
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            where.append(" and action = ?");
            args.add(action);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (target like ? or admin_name like ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        long total = db.count("select count(*) from admin_audit_log" + where, args.toArray());
        List<Object> q = new ArrayList<>(args);
        q.add(Math.min(size, 100));
        q.add((long) (Math.max(page, 1) - 1) * Math.min(size, 100));

        List<Map<String, Object>> items = db.list(
                "select * from admin_audit_log" + where + " order by id desc limit ? offset ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("adminName", rs.getString("admin_name"));
                    m.put("action", rs.getString("action"));
                    m.put("target", rs.getString("target"));
                    m.put("detail", rs.getString("detail"));
                    m.put("ip", rs.getString("ip"));
                    m.put("createdAt", Db.dt(rs, "created_at"));
                    return m;
                }, q.toArray());

        return R.ok(new PageData<>(items, total, Math.max(page, 1), Math.min(size, 100)));
    }
}
