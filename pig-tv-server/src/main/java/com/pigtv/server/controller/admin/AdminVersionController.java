package com.pigtv.server.controller.admin;

import com.pigtv.server.common.R;
import com.pigtv.server.security.Ctx;
import com.pigtv.server.service.AuditService;
import com.pigtv.server.service.VersionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/versions")
public class AdminVersionController {

    private final VersionService versions;
    private final AuditService audit;

    public AdminVersionController(VersionService versions, AuditService audit) {
        this.versions = versions;
        this.audit = audit;
    }

    @GetMapping
    public R page(@RequestParam(defaultValue = "1") int page,
                  @RequestParam(defaultValue = "20") int size,
                  @RequestParam(required = false) String platform,
                  @RequestParam(required = false) String status) {
        return R.ok(versions.page(Math.max(1, page), Math.min(size, 100), platform, status));
    }

    @PostMapping
    public R create(@RequestParam("file") MultipartFile file,
                    @RequestParam Map<String, String> form) throws IOException {
        Ctx.Who who = Ctx.get();
        Map<String, Object> out = versions.create(file, form, who.id());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("versionCode", form.get("versionCode"));
        detail.put("versionName", form.get("versionName"));
        detail.put("platform", form.get("platform"));
        detail.put("abi", form.get("abi"));
        detail.put("size", out.get("size"));
        audit.log(who.id(), who.name(), "publish", "v" + form.get("versionName"), detail, who.ip());
        return R.ok(out);
    }

    @PatchMapping("/{id}")
    public R update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Ctx.Who who = Ctx.get();
        versions.update(id, body);
        audit.log(who.id(), who.name(), "version_edit", String.valueOf(id), body, who.ip());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R delete(@PathVariable long id) {
        Ctx.Who who = Ctx.get();
        versions.delete(id);
        audit.log(who.id(), who.name(), "version_offline", String.valueOf(id), null, who.ip());
        return R.ok();
    }
}
