package com.pigtv.server.controller.app;

import com.pigtv.server.common.BizException;
import com.pigtv.server.common.R;
import com.pigtv.server.security.Ctx;
import com.pigtv.server.service.AuthService;
import com.pigtv.server.util.Web;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AppAuthController {

    private final AuthService auth;

    public AppAuthController(AuthService auth) {
        this.auth = auth;
    }

    public record RegisterReq(
            @NotBlank String username,
            @NotBlank String password,
            String deviceId,
            String deviceName,
            String platform,
            Integer appVersion) {
    }

    public record LoginReq(
            @NotBlank String username,
            @NotBlank String password,
            String deviceId,
            String deviceName,
            String platform,
            Integer appVersion) {
    }

    public record ChangePwdReq(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    public record BindReq(@NotBlank String code) {
    }

    @PostMapping("/auth/register")
    public R register(@Valid @RequestBody RegisterReq req, HttpServletRequest http) {
        return R.ok(auth.register(req.username(), req.password(), req.deviceId(), req.deviceName(),
                req.platform(), req.appVersion(), Web.clientIp(http)));
    }

    @PostMapping("/auth/login")
    public R login(@Valid @RequestBody LoginReq req, HttpServletRequest http) {
        return R.ok(auth.login(req.username(), req.password(), req.deviceId(), req.deviceName(),
                req.platform(), req.appVersion(), Web.clientIp(http)));
    }

    @PostMapping("/auth/logout")
    public R logout() {
        auth.logout(Ctx.get().jti());
        return R.ok();
    }

    @PostMapping("/auth/change-password")
    public R changePassword(@Valid @RequestBody ChangePwdReq req) {
        auth.changePassword(Ctx.userId(), req.oldPassword(), req.newPassword());
        return R.ok();
    }

    @GetMapping("/user/profile")
    public R profile() {
        return R.ok(auth.profile(Ctx.userId()));
    }

    @PostMapping("/device/code")
    public R deviceCode(@RequestParam(defaultValue = "tv") String platform) {
        return R.ok(auth.createDeviceCode(platform));
    }

    @GetMapping("/device/poll")
    public R devicePoll(@RequestParam String deviceCode, HttpServletRequest http) {
        return R.ok(auth.pollDeviceCode(deviceCode, Web.clientIp(http)));
    }

    @PostMapping("/device/bind")
    public R deviceBind(@Valid @RequestBody BindReq req, HttpServletRequest http) {
        Ctx.Who who = Ctx.get();
        if (who == null) {
            throw BizException.unauthorized("未登录");
        }
        return R.ok(auth.bindDeviceCode(who.id(), who.name(), req.code(), Web.clientIp(http)));
    }
}
