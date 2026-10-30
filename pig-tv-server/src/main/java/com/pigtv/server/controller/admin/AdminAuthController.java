package com.pigtv.server.controller.admin;

import com.pigtv.server.common.BizException;
import com.pigtv.server.common.R;
import com.pigtv.server.db.Db;
import com.pigtv.server.security.Ctx;
import com.pigtv.server.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    private final Db db;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminAuthController(Db db, JwtService jwt) {
        this.db = db;
        this.jwt = jwt;
    }

    public record LoginReq(@NotBlank String username, @NotBlank String password) {
    }

    @PostMapping("/login")
    public R login(@Valid @RequestBody LoginReq req) {
        Map<String, Object> admin = db.one(
                "select id, username, password_hash, role from admin_user where username = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("username", rs.getString("username"));
                    m.put("password_hash", rs.getString("password_hash"));
                    m.put("role", rs.getString("role"));
                    return m;
                }, req.username());
        if (admin == null || !encoder.matches(req.password(), (String) admin.get("password_hash"))) {
            throw BizException.unauthorized("账号或密码错误");
        }
        db.update("update admin_user set last_login_at = ? where id = ?", LocalDateTime.now(), admin.get("id"));

        long id = ((Number) admin.get("id")).longValue();
        String token = jwt.issue("admin", id, (String) admin.get("username"), jwt.newJti());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("username", admin.get("username"));
        out.put("role", admin.get("role"));
        return R.ok(out);
    }

    @GetMapping("/profile")
    public R profile() {
        Ctx.Who who = Ctx.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", who.id());
        out.put("username", who.name());
        out.put("role", "admin");
        return R.ok(out);
    }

    public record ChangePwdReq(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    @PostMapping("/change-password")
    public R changePassword(@Valid @RequestBody ChangePwdReq req) {
        Ctx.Who who = Ctx.get();
        Map<String, Object> admin = db.one("select password_hash from admin_user where id = ?",
                (rs, i) -> Map.of("password_hash", rs.getString(1)), who.id());
        if (admin == null || !encoder.matches(req.oldPassword(), (String) admin.get("password_hash"))) {
            throw BizException.badRequest("原密码不正确");
        }
        if (req.newPassword().length() < 8) {
            throw BizException.badRequest("新密码至少 8 位");
        }
        db.update("update admin_user set password_hash = ? where id = ?",
                encoder.encode(req.newPassword()), who.id());
        return R.ok();
    }
}
