package com.pigtv.server.controller.admin;

import com.pigtv.server.common.R;
import com.pigtv.server.security.Ctx;
import com.pigtv.server.service.AuditService;
import com.pigtv.server.service.SourceService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/sources")
public class AdminSourceController {

    private final SourceService sources;
    private final AuditService audit;

    public AdminSourceController(SourceService sources, AuditService audit) {
        this.sources = sources;
        this.audit = audit;
    }

    @GetMapping
    public R list() {
        return R.ok(sources.listAll());
    }

    @PostMapping
    public R create(@RequestBody Map<String, Object> body) {
        Ctx.Who who = Ctx.get();
        long id = sources.create(body);
        audit.log(who.id(), who.name(), "source_create", String.valueOf(body.get("name")), body, who.ip());
        return R.ok(Map.of("id", id));
    }

    @PatchMapping("/{id}")
    public R update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Ctx.Who who = Ctx.get();
        sources.update(id, body);
        audit.log(who.id(), who.name(), "source_edit", String.valueOf(id), body, who.ip());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R delete(@PathVariable long id) {
        Ctx.Who who = Ctx.get();
        sources.delete(id);
        audit.log(who.id(), who.name(), "source_delete", String.valueOf(id), null, who.ip());
        return R.ok();
    }

    @PostMapping("/{id}/test")
    public R test(@PathVariable long id) {
        return R.ok(sources.test(id));
    }
}
