package com.pigtv.server.controller.admin;

import com.pigtv.server.common.R;
import com.pigtv.server.config.AppProps;
import com.pigtv.server.security.Ctx;
import com.pigtv.server.service.RiskService;
import com.pigtv.server.service.UserAdminService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserAdminService users;
    private final RiskService risk;
    private final AppProps props;

    public AdminUserController(UserAdminService users, RiskService risk, AppProps props) {
        this.users = users;
        this.risk = risk;
        this.props = props;
    }

    @GetMapping
    public R page(@RequestParam(required = false) String keyword,
                  @RequestParam(required = false) String state,
                  @RequestParam(defaultValue = "1") int page,
                  @RequestParam(defaultValue = "20") int size) {
        return R.ok(users.page(keyword, state, Math.max(1, page), Math.min(size, 100),
                props.getRisk().getOnlineWindowMinutes()));
    }

    @GetMapping("/{id}")
    public R detail(@PathVariable long id) {
        return R.ok(users.detail(id, props.getRisk().getOnlineWindowMinutes()));
    }

    @GetMapping("/{id}/segments")
    public R segments(@PathVariable long id) {
        return R.ok(risk.recentSegments(id, 20));
    }

    @PatchMapping("/{id}/password")
    public R resetPassword(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Ctx.Who who = Ctx.get();
        String pwd = body.get("newPassword") == null ? null : String.valueOf(body.get("newPassword"));
        users.resetPassword(id, pwd, who.id(), who.name(), who.ip());
        return R.ok();
    }

    @PatchMapping("/{id}/status")
    public R setStatus(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Ctx.Who who = Ctx.get();
        String action = String.valueOf(body.getOrDefault("action", "ban"));
        if ("unban".equals(action)) {
            users.unban(id, who.id(), who.name(), who.ip());
            return R.ok();
        }
        Integer hours = null;
        boolean permanent = Boolean.TRUE.equals(body.get("permanent"))
                || "permanent".equals(String.valueOf(body.get("hours")));
        if (!permanent && body.get("hours") != null) {
            try {
                hours = Integer.parseInt(String.valueOf(body.get("hours")));
            } catch (NumberFormatException e) {
                hours = 72;
            }
        }
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        users.ban(id, hours, reason, who.id(), who.name(), who.ip());
        return R.ok();
    }

    @PostMapping("/{id}/unban")
    public R unban(@PathVariable long id) {
        Ctx.Who who = Ctx.get();
        users.unban(id, who.id(), who.name(), who.ip());
        return R.ok();
    }

    @PostMapping("/{id}/lock-segment")
    public R lockSegment(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Ctx.Who who = Ctx.get();
        String segments = body.get("segments") == null ? null : String.valueOf(body.get("segments"));
        users.lockSegments(id, segments, who.id(), who.name(), who.ip());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R remove(@PathVariable long id) {
        Ctx.Who who = Ctx.get();
        users.remove(id, who.id(), who.name(), who.ip());
        return R.ok();
    }
}
