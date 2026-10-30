package com.pigtv.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigtv.server.common.R;
import com.pigtv.server.util.Web;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final JwtService jwt;
    private final ObjectMapper om;

    public AdminInterceptor(JwtService jwt, ObjectMapper om) {
        this.jwt = jwt;
        this.om = om;
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
            return reject(resp, 401, "登录已过期");
        }
        if (!"admin".equals(c.get("typ", String.class))) {
            return reject(resp, 401, "无后台权限");
        }
        Ctx.set(new Ctx.Who(Long.parseLong(c.getSubject()), c.get("name", String.class),
                "admin", c.getId(), Web.clientIp(req), null));
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
}
