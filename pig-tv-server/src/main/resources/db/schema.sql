-- 猪猪影视 服务端表结构
-- 兼容 H2(MODE=MySQL) 与 MySQL 8：不使用 ENGINE / CHARSET / 内联 KEY 语法
-- 生产环境建议在 MySQL 上额外补的索引见文件末尾注释

CREATE TABLE IF NOT EXISTS app_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(32)  NOT NULL,
    password_hash   VARCHAR(72)  NOT NULL,
    nickname        VARCHAR(32),
    status          TINYINT      NOT NULL DEFAULT 0,
    ban_until       DATETIME,
    ban_reason      VARCHAR(200),
    ban_type        VARCHAR(12)  NOT NULL DEFAULT 'manual',
    ban_evidence    TEXT,
    locked_segments VARCHAR(512),
    device_limit    TINYINT      NOT NULL DEFAULT 5,
    register_ip     VARCHAR(45),
    last_ip         VARCHAR(45),
    last_login_at   DATETIME,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    deleted_at      DATETIME,
    CONSTRAINT uk_user_name UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS user_session (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    jti          VARCHAR(36) NOT NULL,
    device_id    VARCHAR(64) NOT NULL,
    device_name  VARCHAR(64),
    platform     VARCHAR(8),
    app_version  INT,
    ip           VARCHAR(45),
    ip_seg       VARCHAR(64),
    last_seen_at DATETIME    NOT NULL,
    created_at   DATETIME    NOT NULL,
    revoked      TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_session_jti UNIQUE (jti)
);

CREATE TABLE IF NOT EXISTS device_code (
    device_code VARCHAR(36) PRIMARY KEY,
    code        VARCHAR(6)  NOT NULL,
    user_id     BIGINT,
    bound       TINYINT     NOT NULL DEFAULT 0,
    expire_at   DATETIME    NOT NULL,
    created_at  DATETIME    NOT NULL
);

CREATE TABLE IF NOT EXISTS app_version (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_code               INT          NOT NULL,
    version_name               VARCHAR(32)  NOT NULL,
    platform                   VARCHAR(8)   NOT NULL,
    abi                        VARCHAR(16)  NOT NULL,
    package_name               VARCHAR(64)  NOT NULL,
    file_url                   VARCHAR(512) NOT NULL,
    -- 相对路径（202610/xxx.apk）。下载地址在读取时按当前 STORAGE_BASE_URL 现拼，
    -- 否则换域名/换端口后老记录的 file_url 还指着旧地址，客户端下载 404。
    file_path                  VARCHAR(255),
    file_size                  BIGINT       NOT NULL,
    sha256                     VARCHAR(64)  NOT NULL,
    changelog                  TEXT,
    force_update               TINYINT      NOT NULL DEFAULT 0,
    min_supported_version_code INT          NOT NULL DEFAULT 0,
    status                     VARCHAR(12)  NOT NULL DEFAULT 'draft',
    download_count             BIGINT       NOT NULL DEFAULT 0,
    published_at               DATETIME,
    created_by                 BIGINT,
    created_at                 DATETIME     NOT NULL,
    updated_at                 DATETIME     NOT NULL,
    CONSTRAINT uk_version UNIQUE (version_code, platform, abi)
);

CREATE TABLE IF NOT EXISTS source_config (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL,
    url           VARCHAR(512) NOT NULL,
    weight        INT          NOT NULL DEFAULT 100,
    is_default    TINYINT      NOT NULL DEFAULT 0,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    remark        VARCHAR(200),
    last_check_at DATETIME,
    last_check_ok TINYINT,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL
);

CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32) NOT NULL,
    password_hash VARCHAR(72) NOT NULL,
    role          VARCHAR(16) NOT NULL DEFAULT 'admin',
    last_login_at DATETIME,
    created_at    DATETIME    NOT NULL,
    CONSTRAINT uk_admin_name UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    admin_id   BIGINT      NOT NULL,
    admin_name VARCHAR(32),
    action     VARCHAR(32) NOT NULL,
    target     VARCHAR(64),
    detail     TEXT,
    ip         VARCHAR(45),
    created_at DATETIME    NOT NULL
);

CREATE TABLE IF NOT EXISTS ip_rule (
    id                TINYINT PRIMARY KEY,
    enabled           TINYINT NOT NULL DEFAULT 1,
    max_segments      TINYINT NOT NULL DEFAULT 3,
    ban_hours         INT     NOT NULL DEFAULT 72,
    same_device_grace INT     NOT NULL DEFAULT 60,
    updated_at        DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS ip_list (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    cidr       VARCHAR(64) NOT NULL,
    list_type  VARCHAR(8)  NOT NULL,
    remark     VARCHAR(200),
    created_at DATETIME    NOT NULL
);

CREATE TABLE IF NOT EXISTS login_ban_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    reason      VARCHAR(32) NOT NULL,
    segments    TEXT        NOT NULL,
    devices     TEXT,
    ban_until   DATETIME    NOT NULL,
    auto_unban  TINYINT     NOT NULL DEFAULT 1,
    unbanned_by BIGINT,
    created_at  DATETIME    NOT NULL
);

CREATE TABLE IF NOT EXISTS user_collect (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    source_key VARCHAR(64)  NOT NULL,
    vod_id     VARCHAR(64)  NOT NULL,
    name       VARCHAR(128),
    pic        VARCHAR(512),
    updated_at DATETIME     NOT NULL,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_collect UNIQUE (user_id, source_key, vod_id)
);

CREATE TABLE IF NOT EXISTS user_history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    source_key  VARCHAR(64) NOT NULL,
    vod_id      VARCHAR(64) NOT NULL,
    data_json   TEXT,
    play_flag   VARCHAR(64),
    play_index  INT,
    position_ms BIGINT,
    duration_ms BIGINT,
    updated_at  DATETIME    NOT NULL,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_history UNIQUE (user_id, source_key, vod_id)
);

CREATE TABLE IF NOT EXISTS user_setting (
    user_id    BIGINT      NOT NULL,
    skey       VARCHAR(64) NOT NULL,
    svalue     TEXT,
    updated_at DATETIME    NOT NULL,
    PRIMARY KEY (user_id, skey)
);

-- MySQL 生产环境建议补充（H2 不支持内联 KEY，故单独列出）：
-- CREATE INDEX idx_session_seg    ON user_session (user_id, ip_seg, last_seen_at);
-- CREATE INDEX idx_session_seen   ON user_session (last_seen_at);
-- CREATE INDEX idx_collect_upd    ON user_collect (user_id, updated_at);
-- CREATE INDEX idx_history_upd    ON user_history (user_id, updated_at);
-- CREATE INDEX idx_ban_user       ON login_ban_log (user_id, created_at);
-- CREATE INDEX idx_audit_time     ON admin_audit_log (created_at);
