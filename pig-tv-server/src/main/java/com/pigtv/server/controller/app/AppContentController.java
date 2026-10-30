package com.pigtv.server.controller.app;

import com.pigtv.server.common.R;
import com.pigtv.server.service.RiskService;
import com.pigtv.server.service.SourceService;
import com.pigtv.server.service.VersionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AppContentController {

    private final VersionService versions;
    private final SourceService sources;
    private final RiskService risk;

    public AppContentController(VersionService versions, SourceService sources, RiskService risk) {
        this.versions = versions;
        this.sources = sources;
        this.risk = risk;
    }

    @GetMapping("/update/check")
    public R check(@RequestParam(defaultValue = "phone") String platform,
                   @RequestParam(defaultValue = "universal") String abi,
                   @RequestParam(defaultValue = "0") int versionCode) {
        return R.ok(versions.check(platform, abi, versionCode));
    }

    @PostMapping("/update/hit")
    public R hit(@RequestBody Map<String, Object> body) {
        Object id = body.get("versionId");
        if (id != null) {
            versions.incDownload(Long.parseLong(String.valueOf(id)));
        }
        return R.ok();
    }

    @GetMapping("/sources")
    public R sources() {
        return R.ok(sources.listForClient());
    }

    @GetMapping("/health")
    public R health() {
        return R.ok(Map.of("status", "up", "time", java.time.LocalDateTime.now().toString()));
    }
}
