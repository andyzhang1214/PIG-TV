package com.pigtv.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigtv.server.common.R;
import com.pigtv.server.config.AppProps;
import com.pigtv.server.db.Db;
import com.pigtv.server.service.RiskService;
import com.pigtv.server.util.Web;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtService jwt;
    private final Db db;
    private final RiskService risk;
    private final ObjectMapper om;
    private final AppProps props;

    public AuthInterceptor(JwtService jwt, Db db, RiskService risk, ObjectMapper om, AppProps props) {
        this.jwt = jwt;
        this.db = db;
        this.risk = risk;
        this.om = om;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        String token = Web.bearer(req);
        if (token == null) {
            return reject(resp, 401, "未登录");
        }
        Claims c;
        try {
            c = jwt.parse(token);
        } catch (Exception e) {
            return reject(resp, 401, "登录已过期，请重新登录");
        }
        if (!"user".equals(c.get("typ", String.class))) {
            return reject(resp, 401, "登录状态无效");
        }

        long userId = Long.parseLong(c.getSubject());
        String jti = c.getId();

        // 先查用户：封禁判断必须先于会话判断。
        // 否则被风控封禁后会话已携销，用户只会看到「登录已失效」，永远看不到封禁原因与解封时间。
        Map<String, Object> user = db.one(
                "select id, username, status, ban_until, ban_reason, ban_type, deleted_at, locked_segments "
                        + "from app_user where id = ?",
                (rs, i) -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("username", rs.getString("username"));
                    m.put("status", rs.getInt("status"));
                    m.put("ban_until", Db.dt(rs, "ban_until"));
                    m.put("ban_reason", rs.getString("ban_reason"));
                    m.put("ban_type", rs.getString("ban_type"));
                    m.put("deleted_at", Db.dt(rs, "deleted_at"));
                    m.put("locked_segments", rs.getString("locked_segments"));
                    return m;
                }, userId);
        if (user == null || user.get("deleted_at") != null) {
            return reject(resp, 401, "账号不存在");
        }

        LocalDateTime banUntil = (LocalDateTime) user.get("ban_until");
        if (((Number) user.get("status")).intValue() == 1 && banUntil != null && banUntil.isAfter(LocalDateTime.now())) {
            return rejectBan(resp, banUntil, (String) user.get("ban_reason"), (String) user.get("ban_type"));
        }

        Map<String, Object> session = db.one(
                "select id, revoked, device_id from user_session where jti = ?", (rs, i) -> Map.of(
                        "id", rs.getLong("id"),
                        "revoked", rs.getInt("revoked"),
                        "device_id", rs.getString("device_id") == null ? "" : rs.getString("device_id")
                ), jti);
        if (session == null || ((Number) session.get("revoked")).intValue() == 1) {
            return reject(resp, 401, "登录状态已失效，请重新登录");
        }

        String ip = Web.clientIp(req);
        String deviceId = (String) session.get("device_id");

        // 锁网段校验（后台手动给账号绑定允许网段时生效）
        String locked = (String) user.get("locked_segments");
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
                return reject(resp, 403, "当前网络不在该账号允许的范围内");
            }
        }

        Ctx.set(new Ctx.Who(userId, (String) user.get("username"), "user", jti, ip, deviceId));

        // 心跳节流：同一会话 60 秒内不重复写库
        db.update("update user_session set last_seen_at = ? where jti = ? and last_seen_at < ?",
                LocalDateTime.now(), jti, LocalDateTime.now().minusSeconds(60));

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp, Object handler, Exception ex) {
        Ctx.clear();
    }

    private boolean reject(HttpServletResponse resp, int code, String msg) throws Exception {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write(om.writeValueAsString(R.fail(code, msg)));
        return false;
    }

    private boolean rejectBan(HttpServletResponse resp, LocalDateTime until, String reason, String type) throws Exception {
        resp.setStatus(403);
        resp.setContentType("application/json;charset=UTF-8");
        String msg = "auto_ip".equals(type)
                ? "登录地点异常，账号已被临时封禁"
                : "账号已被封禁";
        resp.getWriter().write(om.writeValueAsString(R.fail(403, msg, Map.of(
                "banUntil", until.toString(),
                "reason", reason == null ? "" : reason,
                "banType", type == null ? "manual" : type
        ))));
        return false;
    }
}
