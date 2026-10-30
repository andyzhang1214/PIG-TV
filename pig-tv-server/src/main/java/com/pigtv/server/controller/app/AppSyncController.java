package com.pigtv.server.controller.app;

import com.pigtv.server.common.R;
import com.pigtv.server.config.AppProps;
import com.pigtv.server.security.Ctx;
import com.pigtv.server.service.RiskService;
import com.pigtv.server.service.SyncService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AppSyncController {

    private final SyncService sync;
    private final RiskService risk;
    private final AppProps props;

    public AppSyncController(SyncService sync, RiskService risk, AppProps props) {
        this.sync = sync;
        this.risk = risk;
        this.props = props;
    }

    public record PushReq(List<Map<String, Object>> collect,
                          List<Map<String, Object>> history,
                          Object setting) {
    }

    @PostMapping("/sync/push")
    public R push(@RequestBody PushReq req) {
        return R.ok(sync.push(Ctx.userId(), req.collect(), req.history(),
                SyncService.normalizeSetting(req.setting())));
    }

    @GetMapping("/sync/pull")
    public R pull(@RequestParam(required = false) String since,
                  @RequestParam(required = false) String types,
                  @RequestParam(defaultValue = "300") int limit) {
        return R.ok(sync.pull(Ctx.userId(),
                since == null || since.isBlank() ? null : SyncService.parseTime(since),
                types, Math.min(limit, 1000)));
    }

    @PostMapping("/heartbeat")
    public R heartbeat(@RequestBody(required = false) Map<String, Object> body) {
        Ctx.Who who = Ctx.get();
        risk.touch(who.id(), who.jti(), who.ip());
        return R.ok(Map.of(
                "serverTime", java.time.LocalDateTime.now().toString(),
                "onlineWindowMinutes", props.getRisk().getOnlineWindowMinutes()
        ));
    }
}
