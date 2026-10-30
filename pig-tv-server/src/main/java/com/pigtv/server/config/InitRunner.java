package com.pigtv.server.config;

import com.pigtv.server.db.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 启动时保证：有一个管理员账号、有一条网段规则 */
@Component
public class InitRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitRunner.class);

    private final Db db;
    private final AppProps props;
    private final Environment env;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public InitRunner(Db db, AppProps props, Environment env) {
        this.db = db;
        this.props = props;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        warnIfDevProfile();

        long admins = db.count("select count(*) from admin_user");
        if (admins == 0) {
            String user = props.getAdmin().getInitUsername();
            String pwd = props.getAdmin().getInitPassword();
            db.insert("insert into admin_user (username, password_hash, role, created_at) values (?,?,?,?)",
                    user, encoder.encode(pwd), "admin", LocalDateTime.now());
            log.warn("已创建初始管理员 {} / {} —— 请立即修改密码", user, pwd);
        }

        long rules = db.count("select count(*) from ip_rule");
        if (rules == 0) {
            db.insert("insert into ip_rule (id, enabled, max_segments, ban_hours, same_device_grace, updated_at) values (1,1,?,?,?,?)",
                    props.getRisk().getMaxSegments(),
                    props.getRisk().getBanHours(),
                    props.getRisk().getSameDeviceGraceMinutes(),
                    LocalDateTime.now());
        }
    }

    /**
     * dev 用的是 H2 内存库，进程一停全部数据消失。
     * 部署到服务器时如果忘了 SPRING_PROFILES_ACTIVE=prod，服务照样能起来、能注册用户，
     * 直到重启那天才发现用户全没了 —— 所以在启动日志里大声提示。
     */
    private void warnIfDevProfile() {
        String profiles = String.join(",", env.getActiveProfiles());
        if (profiles.contains("prod")) {
            return;
        }
        log.warn("================================================================");
        log.warn("当前 profile = [{}] —— 用的是 H2 内存库，重启后数据全部清空！", profiles);
        log.warn("生产部署请设置环境变量 SPRING_PROFILES_ACTIVE=prod");
        log.warn("================================================================");
    }
}
